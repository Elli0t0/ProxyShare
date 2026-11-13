//! SOCKS5 (RFC 1928): method negotiation and CONNECT.
//!
//! No authentication is offered yet: the only method the engine accepts
//! is 0x00 (no auth). Only CMD CONNECT is implemented. The handler
//! reads the request, dials the target through the `Outbound` trait,
//! sends the reply and relays the two directions. It never looks at the
//! payload: only the target host/port and the byte counts are recorded.
//!
//! Reply codes: 0x00 success, 0x04 host unreachable (the name did not
//! resolve), 0x05 connection refused (no address accepted the
//! connection), 0x07 command not supported (anything other than
//! CONNECT), 0x08 address type not supported.

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::Config;
use crate::connector::{ConnectError, Connector};
use crate::engine::ConnRecord;
use crate::relay;

/// How long a negotiation read (greeting, request) may take. The
/// sniffer already waited 5 s for the first byte; the rest of the
/// greeting and the request must arrive promptly after it.
const NEGOTIATE_TIMEOUT: Duration = Duration::from_secs(10);

const REP_SUCCESS: u8 = 0x00;
const REP_HOST_UNREACHABLE: u8 = 0x04;
const REP_CONNECTION_REFUSED: u8 = 0x05;
const REP_COMMAND_NOT_SUPPORTED: u8 = 0x07;
const REP_ADDRESS_NOT_SUPPORTED: u8 = 0x08;
const REP_NO_ACCEPTABLE_METHOD: u8 = 0xFF;

/// The target a CONNECT request asks for.
enum Target {
    Ip(IpAddr),
    Domain(String),
}

impl Target {
    fn host(&self) -> String {
        match self {
            Target::Ip(ip) => ip.to_string(),
            Target::Domain(domain) => domain.clone(),
        }
    }
}

/// Read exactly `buf.len()` bytes, or fail after the negotiation timeout.
async fn read_exact_timeout(client: &mut TcpStream, buf: &mut [u8]) -> io::Result<()> {
    tokio::time::timeout(NEGOTIATE_TIMEOUT, client.read_exact(buf))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "SOCKS5 negotiation timed out"))??;
    Ok(())
}

/// Send a SOCKS5 reply: [VER, REP, RSV, ATYP, BND.ADDR, BND.PORT].
async fn send_reply(client: &mut TcpStream, rep: u8, bind: SocketAddr) -> io::Result<()> {
    let mut buf = vec![0x05, rep, 0x00];
    match bind.ip() {
        IpAddr::V4(v4) => {
            buf.push(0x01);
            buf.extend_from_slice(&v4.octets());
        }
        IpAddr::V6(v6) => {
            buf.push(0x04);
            buf.extend_from_slice(&v6.octets());
        }
    }
    buf.extend_from_slice(&bind.port().to_be_bytes());
    client.write_all(&buf).await
}

/// An error reply uses a zeroed IPv4 bound address.
async fn send_error_reply(client: &mut TcpStream, rep: u8) -> io::Result<()> {
    send_reply(
        client,
        rep,
        SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),
    )
    .await
}

/// Read the method greeting and answer it. Only no-auth (0x00) is
/// accepted; any other list gets [0x05, 0xFF] and the connection closes.
async fn negotiate(client: &mut TcpStream) -> io::Result<()> {
    let mut head = [0u8; 2];
    read_exact_timeout(client, &mut head).await?;
    let (ver, nmethods) = (head[0], head[1]);
    if ver != 0x05 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("bad SOCKS version {ver}"),
        ));
    }
    if nmethods == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "SOCKS5 greeting lists no methods",
        ));
    }
    let mut methods = vec![0u8; nmethods as usize];
    read_exact_timeout(client, &mut methods).await?;
    if methods.contains(&0x00) {
        client.write_all(&[0x05, 0x00]).await
    } else {
        let _ = client.write_all(&[0x05, REP_NO_ACCEPTABLE_METHOD]).await;
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "client offers no acceptable authentication method",
        ))
    }
}

/// Read a CONNECT request and return its target and port. The address
/// is read before the command is checked, so a request with a known
/// address type but the wrong command still closes cleanly (all its
/// bytes were read). A bad address type cannot be read, so its error
/// reply goes out with the rest of the request unread — that close may
/// be a reset rather than a clean FIN, but the client has the reply
/// code by then.
async fn read_request(client: &mut TcpStream) -> io::Result<(Target, u16)> {
    let mut head = [0u8; 4];
    read_exact_timeout(client, &mut head).await?;
    let (ver, cmd, atyp) = (head[0], head[1], head[3]);
    if ver != 0x05 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("bad SOCKS version {ver}"),
        ));
    }
    let target = match atyp {
        0x01 => {
            let mut addr = [0u8; 4];
            read_exact_timeout(client, &mut addr).await?;
            Target::Ip(IpAddr::V4(Ipv4Addr::from(addr)))
        }
        0x03 => {
            let mut len = [0u8; 1];
            read_exact_timeout(client, &mut len).await?;
            if len[0] == 0 {
                let _ = send_error_reply(client, REP_ADDRESS_NOT_SUPPORTED).await;
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "SOCKS5 domain is empty",
                ));
            }
            let mut bytes = vec![0u8; len[0] as usize];
            read_exact_timeout(client, &mut bytes).await?;
            match String::from_utf8(bytes) {
                Ok(domain) => Target::Domain(domain),
                Err(_) => {
                    let _ = send_error_reply(client, REP_ADDRESS_NOT_SUPPORTED).await;
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "SOCKS5 domain is not valid UTF-8",
                    ));
                }
            }
        }
        0x04 => {
            let mut addr = [0u8; 16];
            read_exact_timeout(client, &mut addr).await?;
            Target::Ip(IpAddr::V6(addr.into()))
        }
        other => {
            let _ = send_error_reply(client, REP_ADDRESS_NOT_SUPPORTED).await;
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS5 address type {other} is not supported"),
            ));
        }
    };
    let mut port_bytes = [0u8; 2];
    read_exact_timeout(client, &mut port_bytes).await?;
    let port = u16::from_be_bytes(port_bytes);
    if cmd != 0x01 {
        let _ = send_error_reply(client, REP_COMMAND_NOT_SUPPORTED).await;
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("SOCKS5 command {cmd} is not supported (CONNECT only)"),
        ));
    }
    Ok((target, port))
}

/// Handle one SOCKS5 connection: negotiate the method, read the CONNECT
/// request, dial the target and relay both directions until either side
/// closes, errors, or sits idle for `idle_timeout_s`.
///
/// The connection record's target is set as soon as the request is
/// parsed, and the relay counts live into its up/down counters. The
/// client stream is consumed: on any outcome the connection is closed
/// when this returns.
pub async fn handle(
    mut client: TcpStream,
    outbound: &Connector,
    config: &Config,
    record: &std::sync::Arc<ConnRecord>,
) -> io::Result<()> {
    negotiate(&mut client).await?;
    let (target, port) = read_request(&mut client).await?;
    let host = target.host();
    record.set_target(host.clone(), port);
    let upstream = match outbound
        .connect(
            &host,
            port,
            Duration::from_secs(config.connect_timeout_s),
        )
        .await
    {
        Ok(stream) => stream,
        Err(ConnectError::Dns(_)) => {
            let _ = send_error_reply(&mut client, REP_HOST_UNREACHABLE).await;
            return Err(io::Error::new(
                io::ErrorKind::AddrNotAvailable,
                format!("could not resolve {host}"),
            ));
        }
        Err(ConnectError::Connect(_)) => {
            let _ = send_error_reply(&mut client, REP_CONNECTION_REFUSED).await;
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("could not connect to {host}:{port}"),
            ));
        }
    };
    let bind = upstream.local_addr()?;
    send_reply(&mut client, REP_SUCCESS, bind).await?;
    relay::relay(
        client,
        upstream,
        &record.up,
        &record.down,
        Duration::from_secs(config.idle_timeout_s),
    )
    .await
}
