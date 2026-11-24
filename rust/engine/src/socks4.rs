//! SOCKS4/4a: CONNECT only.
//!
//! The request is VN(1) = 4, CD(1), DSTPORT(2), DSTIP(4), USERID
//! (null-terminated), and for 4a — DSTIP is 0.0.0.x with x != 0 — a
//! null-terminated hostname after the USERID. USERID is read and
//! ignored. Only CD = 1 (CONNECT) is implemented; anything else gets a
//! 0x5B reply. A target on the `blocked_domains` list also gets a 0x5B
//! reply, before any outbound connect, and the connection is recorded
//! as blocked. The reply is [0x00, 0x5A, 0, 0, 0, 0, 0, 0] on success
//! and [0x00, 0x5B, ...] on failure. It never looks at the payload:
//! only the target host/port and the byte counts are recorded.

use std::io;
use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::Config;
use crate::connector::{ConnectError, Connector};
use crate::engine::ConnRecord;
use crate::relay;
use crate::relay::RateLimiter;

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
    rules: &crate::rules::RuleSet,
    record: &std::sync::Arc<ConnRecord>,
    vpn_down: bool,
    up_limiter: Option<&Arc<RateLimiter>>,
    down_limiter: Option<&Arc<RateLimiter>>,
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
    if crate::config::domain_blocked(&config.blocked_domains, &host) {
        record.mark_blocked();
        crate::diag::log_info(format!("blocked {host} from {}", record.client_ip));
        let _ = send_reply(&mut client, REP_FAILURE).await;
        return Ok(());
    }
    // Routing rules (0.6.6), checked after blocked_domains and
    // before connect: a Block rule refuses like a blocked domain.
    // The route is recorded now (from the domain rule) and corrected
    // after connect when the resolved address decided it.
    let action = rules.lookup(&host);
    if action == Some(crate::rules::Action::Block) {
        record.mark_blocked();
        record.set_route(crate::rules::Action::Block);
        crate::diag::log_info(format!(
            "blocked {host} from {} (rule)",
            record.client_ip
        ));
        let _ = send_reply(&mut client, REP_FAILURE).await;
        return Ok(());
    }
    record.set_route(action.unwrap_or(crate::rules::Action::Proxy));
    if vpn_down {
        crate::diag::log_info(format!(
            "refused {host}:{port}: VPN is down (require_vpn)"
        ));
        let _ = send_reply(&mut client, REP_FAILURE).await;
        return Ok(());
    }
    let (upstream, route) = match outbound
        .connect_routed(
            &host,
            port,
            Duration::from_secs(config.connect_timeout_s),
            config.block_private,
            action,
            rules,
        )
        .await
    {
        Ok(v) => v,
        Err(ConnectError::RuleBlocked(_)) => {
            record.mark_blocked();
            record.set_route(crate::rules::Action::Block);
            crate::diag::log_info(format!(
                "blocked {host}:{port} from {} (rule)",
                record.client_ip
            ));
            let _ = send_reply(&mut client, REP_FAILURE).await;
            return Ok(());
        }
        Err(ConnectError::Blocked(_)) => {
            record.mark_blocked();
            crate::diag::log_info(format!(
                "blocked {host}:{port} from {} (private target)",
                record.client_ip
            ));
            let _ = send_reply(&mut client, REP_FAILURE).await;
            return Ok(());
        }
        Err(e) => {
            crate::diag::log_warn(format!("connect to {host}:{port} failed: {e}"));
            let _ = send_reply(&mut client, REP_FAILURE).await;
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("could not reach {host}:{port}: {e}"),
            ));
        }
    };
    record.set_route(route);
    send_reply(&mut client, REP_SUCCESS).await?;
    relay::relay(
        client,
        upstream,
        record,
        Duration::from_secs(config.idle_timeout_s),
        up_limiter,
        down_limiter,
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    /// Drive `read_cstring` over a localhost pair: write `bytes`,
    /// then close the client so a truncated read ends in EOF (not
    /// the 10 s read timeout), and return what the read produced
    /// (within 2 s).
    async fn read_cstring_case(bytes: &[u8]) -> io::Result<String> {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let addr = listener.local_addr().expect("addr");
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("accept");
            read_cstring(&mut stream, 255).await
        });
        let mut client = TcpStream::connect(addr).await.expect("connect");
        client.write_all(bytes).await.expect("write");
        drop(client);
        tokio::time::timeout(Duration::from_secs(2), server)
            .await
            .expect("read finished within 2 s")
            .expect("server task")
    }

    #[tokio::test]
    async fn garbage_userid_truncated_at_every_boundary() {
        let full = b"user\0";
        for len in 0..full.len() {
            assert!(
                read_cstring_case(&full[..len]).await.is_err(),
                "truncated at {len} must fail"
            );
        }
    }

    #[tokio::test]
    async fn garbage_userid_oversized_and_invalid_utf8() {
        // 300 bytes, no NUL: over the 255 cap.
        let over = vec![b'a'; 300];
        assert!(read_cstring_case(&over).await.is_err());
        // Invalid UTF-8 before the NUL.
        assert!(read_cstring_case(&[0xFF, 0xFE, 0x00]).await.is_err());
    }

    #[tokio::test]
    async fn empty_userid_is_ok() {
        // Pins the valid case: a bare NUL reads as an empty userid.
        assert_eq!(
            read_cstring_case(&[0x00]).await.expect("empty userid reads"),
            ""
        );
    }
}
