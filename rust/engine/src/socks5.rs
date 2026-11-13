//! SOCKS5 (RFC 1928): method negotiation, CONNECT and UDP ASSOCIATE.
//!
//! No authentication is offered yet: the only method the engine accepts
//! is 0x00 (no auth). CMD CONNECT and UDP ASSOCIATE (0x03) are
//! implemented. The CONNECT handler reads the request, dials the target
//! through the `Outbound` trait, sends the reply and relays the two
//! directions. It never looks at the payload: only the target host/port
//! and the byte counts are recorded.
//!
//! Reply codes: 0x00 success, 0x04 host unreachable (the name did not
//! resolve), 0x05 connection refused (no address accepted the
//! connection), 0x07 command not supported (anything other than
//! CONNECT), 0x08 address type not supported.

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::Ordering;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

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
#[derive(Debug, PartialEq)]
pub(crate) enum Target {
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

/// Read the method greeting and answer it. Without auth, only no-auth
/// (0x00) is accepted. With auth, only username/password (0x02) is
/// accepted, followed by the RFC 1929 subnegotiation; any other list
/// gets [0x05, 0xFF] and the connection closes.
async fn negotiate(client: &mut TcpStream, config: &Config) -> io::Result<()> {
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
    if crate::auth::required(config) {
        if methods.contains(&0x02) {
            client.write_all(&[0x05, 0x02]).await?;
            authenticate(client, config).await
        } else {
            let _ = client.write_all(&[0x05, REP_NO_ACCEPTABLE_METHOD]).await;
            Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "client offers no acceptable authentication method",
            ))
        }
    } else if methods.contains(&0x00) {
        client.write_all(&[0x05, 0x00]).await
    } else {
        let _ = client.write_all(&[0x05, REP_NO_ACCEPTABLE_METHOD]).await;
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "client offers no acceptable authentication method",
        ))
    }
}

/// RFC 1929 username/password subnegotiation: [0x01, ulen, user, plen,
/// pass]. Reply [0x01, 0x00] on success, [0x01, 0x01] on failure and
/// close. The attempted password is never logged or included in the
/// error.
async fn authenticate(client: &mut TcpStream, config: &Config) -> io::Result<()> {
    let mut head = [0u8; 2];
    read_exact_timeout(client, &mut head).await?;
    if head[0] != 0x01 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "bad username/password subnegotiation version",
        ));
    }
    let mut user = vec![0u8; head[1] as usize];
    read_exact_timeout(client, &mut user).await?;
    let mut plen = [0u8; 1];
    read_exact_timeout(client, &mut plen).await?;
    let mut pass = vec![0u8; plen[0] as usize];
    read_exact_timeout(client, &mut pass).await?;
    if crate::auth::check(config, &user, &pass) {
        client.write_all(&[0x01, 0x00]).await
    } else {
        let _ = client.write_all(&[0x01, 0x01]).await;
        Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "SOCKS5 username/password authentication failed",
        ))
    }
}

/// Read a SOCKS5 request and return its command, target and port. The
/// address is read before the command is checked, so a request with a
/// known address type but the wrong command still closes cleanly (all
/// its bytes were read). A bad address type cannot be read, so its error
/// reply goes out with the rest of the request unread — that close may
/// be a reset rather than a clean FIN, but the client has the reply
/// code by then.
async fn read_request(client: &mut TcpStream) -> io::Result<(u8, Target, u16)> {
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
    Ok((cmd, target, port))
}

/// Handle one SOCKS5 connection: negotiate the method, read the request,
/// then CONNECT (dial the target and relay both directions until either
/// side closes, errors, or sits idle for `idle_timeout_s`) or UDP
/// ASSOCIATE (relay datagrams until the control connection closes or
/// the UDP socket sits idle).
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
    negotiate(&mut client, config).await?;
    let (cmd, target, port) = read_request(&mut client).await?;
    match cmd {
        0x01 => connect(client, outbound, config, record, target, port).await,
        0x03 => associate(client, config, record).await,
        other => {
            let _ = send_error_reply(&mut client, REP_COMMAND_NOT_SUPPORTED).await;
            Err(io::Error::new(
                io::ErrorKind::Unsupported,
                format!("SOCKS5 command {other} is not supported (CONNECT and UDP ASSOCIATE only)"),
            ))
        }
    }
}

/// CONNECT: dial the target and relay both directions.
async fn connect(
    mut client: TcpStream,
    outbound: &Connector,
    config: &Config,
    record: &std::sync::Arc<ConnRecord>,
    target: Target,
    port: u16,
) -> io::Result<()> {
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

/// UDP ASSOCIATE: the request's DST fields are read and ignored. One
/// UDP socket is bound on the TCP connection's local IP; its address
/// goes back in the success reply. Datagrams then flow both ways until
/// the TCP control connection closes or the UDP socket sits idle:
///
/// - A datagram from the client's IP is a client datagram: its header
///   is parsed (dropped when it fails), the target is resolved, the
///   DATA is sent to the target from the same socket, and the client's
///   UDP source address is remembered. Only the IP that opened the
///   association may send.
/// - A datagram from anyone else is a target reply: the sender's
///   address is prepended as a UDP header and the datagram is sent to
///   the remembered client address. Everything is dropped while no
///   client address is known yet.
///
/// Only metadata is recorded: the destination host:port and the byte
/// counts (whole datagrams read from / written to the client).
async fn associate(
    mut client: TcpStream,
    config: &Config,
    record: &std::sync::Arc<ConnRecord>,
) -> io::Result<()> {
    let client_ip = client.peer_addr()?.ip();
    let udp = UdpSocket::bind(SocketAddr::new(client.local_addr()?.ip(), 0)).await?;
    send_reply(&mut client, REP_SUCCESS, udp.local_addr()?).await?;
    let idle = Duration::from_secs(config.idle_timeout_s);
    let mut buf = vec![0u8; 64 * 1024];
    let mut ctl = [0u8; 1];
    let mut client_udp: Option<SocketAddr> = None;
    loop {
        tokio::select! {
            r = client.read(&mut ctl) => match r {
                Ok(0) | Err(_) => break,
                Ok(_) => {}
            },
            r = tokio::time::timeout(idle, udp.recv_from(&mut buf)) => {
                let (n, from) = match r {
                    Ok(Ok(v)) => v,
                    _ => break,
                };
                if from.ip() == client_ip {
                    let Some((target, port, offset)) =
                        crate::udp::parse_udp_header(&buf[..n])
                    else {
                        continue;
                    };
                    let host = target.host();
                    let addr = match &target {
                        Target::Ip(ip) => SocketAddr::new(*ip, port),
                        Target::Domain(_) => {
                            let mut addrs =
                                match tokio::net::lookup_host((host.as_str(), port)).await {
                                    Ok(a) => a,
                                    Err(_) => continue,
                                };
                            match addrs.next() {
                                Some(a) => a,
                                None => continue,
                            }
                        }
                    };
                    record.set_target(host, port);
                    client_udp = Some(from);
                    let data = &buf[offset..n];
                    udp.send_to(data, addr).await?;
                    record.up.fetch_add(n as u64, Ordering::Relaxed);
                } else if let Some(client_addr) = client_udp {
                    let mut out = crate::udp::build_udp_header(from);
                    out.extend_from_slice(&buf[..n]);
                    udp.send_to(&out, client_addr).await?;
                    record.down.fetch_add(out.len() as u64, Ordering::Relaxed);
                }
                // No client address known yet: drop the datagram.
            }
        }
    }
    Ok(())
}
