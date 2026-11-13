//! SOCKS4/4a: CONNECT only.
//!
//! The request is VN(1) = 4, CD(1), DSTPORT(2), DSTIP(4), USERID
//! (null-terminated), and for 4a — DSTIP is 0.0.0.x with x != 0 — a
//! null-terminated hostname after the USERID. USERID is read and
//! ignored. Only CD = 1 (CONNECT) is implemented; anything else gets a
//! 0x5B reply. The reply is [0x00, 0x5A, 0, 0, 0, 0, 0, 0] on success
//! and [0x00, 0x5B, ...] on failure. It never looks at the payload:
//! only the target host/port and the byte counts are recorded.

use std::io;
use std::net::Ipv4Addr;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::Config;
use crate::connector::Connector;
use crate::engine::ConnRecord;
use crate::relay;

/// How long a negotiation read (head, USERID, hostname) may take.
const READ_TIMEOUT: Duration = Duration::from_secs(10);

const REP_SUCCESS: u8 = 0x5A;
const REP_FAILURE: u8 = 0x5B;

/// Read exactly `buf.len()` bytes, or fail after the read timeout.
async fn read_exact_timeout(client: &mut TcpStream, buf: &mut [u8]) -> io::Result<()> {
    tokio::time::timeout(READ_TIMEOUT, client.read_exact(buf))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "SOCKS4 request timed out"))??;
    Ok(())
}

async fn read_cstring(client: &mut TcpStream, max: usize) -> io::Result<String> {
    let mut out = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        read_exact_timeout(client, &mut byte).await?;
        if byte[0] == 0 {
            break;
        }
        if out.len() >= max {
            return Err(io::ErrorKind::InvalidData.into());
        }
        out.push(byte[0]);
    }
    String::from_utf8(out).map_err(|_| io::ErrorKind::InvalidData.into())
}

/// Send a SOCKS4 reply: [0x00, REP, 0, 0, 0, 0, 0, 0].
async fn send_reply(client: &mut TcpStream, rep: u8) -> io::Result<()> {
    client.write_all(&[0x00, rep, 0, 0, 0, 0, 0, 0]).await
}

/// Handle one SOCKS4/4a connection: read the request, dial the target
/// and relay both directions until either side closes, errors, or sits
/// idle for `idle_timeout_s`.
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
    let mut head = [0u8; 8];
    read_exact_timeout(&mut client, &mut head).await?;
    let (vn, cd) = (head[0], head[1]);
    if vn != 0x04 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("bad SOCKS version {vn}"),
        ));
    }
    let port = u16::from_be_bytes([head[2], head[3]]);
    let ip = [head[4], head[5], head[6], head[7]];
    let _userid = read_cstring(&mut client, 255).await?;
    let is_4a = ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0;
    let host = if is_4a {
        read_cstring(&mut client, 255).await?
    } else {
        Ipv4Addr::from(ip).to_string()
    };
    // The whole request was read first, so a wrong command still
    // closes cleanly (all its bytes were read).
    if cd != 0x01 {
        let _ = send_reply(&mut client, REP_FAILURE).await;
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("SOCKS4 command {cd} is not supported (CONNECT only)"),
        ));
    }
    if crate::auth::required(config) {
        // SOCKS4 has no authentication: refuse it when auth is required.
        let _ = send_reply(&mut client, REP_FAILURE).await;
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "SOCKS4 has no authentication",
        ));
    }
    record.set_target(host.clone(), port);
    let upstream = match outbound
        .connect(&host, port, Duration::from_secs(config.connect_timeout_s))
        .await
    {
        Ok(stream) => stream,
        Err(e) => {
            let _ = send_reply(&mut client, REP_FAILURE).await;
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("could not reach {host}:{port}: {e}"),
            ));
        }
    };
    send_reply(&mut client, REP_SUCCESS).await?;
    relay::relay(
        client,
        upstream,
        &record.up,
        &record.down,
        Duration::from_secs(config.idle_timeout_s),
    )
    .await
}
