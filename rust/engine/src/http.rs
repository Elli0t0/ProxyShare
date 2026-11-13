//! HTTP proxying: CONNECT tunnels and plain HTTP forwarding.
//!
//! A CONNECT request opens a tunnel to `host:port` (the host may be an
//! IPv6 literal in brackets): the record's target is set as soon as the
//! target is parsed, the target is dialed (DNS or connect failure gets
//! a 502), success replies `HTTP/1.1 200 Connection Established`, any
//! bytes the client already sent after the head are forwarded, then
//! both directions are relayed untouched. Any other method must carry
//! an absolute URI (`http://host[:port]/path`); anything else gets a
//! 400. The request is rewritten to origin-form, the hop-by-hop
//! headers are dropped, `Connection: close` is added (no keep-alive in
//! this version), the engine connects to `host:port` (default 80) and
//! sends the rewritten head plus any body bytes already read, then
//! relays both directions untouched. A target that cannot be reached
//! gets a 502. Bodies are never parsed or logged: only the target
//! host/port and the byte counts are recorded.

use std::io;
use std::sync::atomic::Ordering;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::Config;
use crate::connector::Connector;
use crate::engine::ConnRecord;
use crate::relay;

/// How long the whole request head may take to arrive.
const HEAD_TIMEOUT: Duration = Duration::from_secs(10);
/// Largest request head accepted (headers only, not the body).
const MAX_HEAD_BYTES: usize = 16 * 1024;

/// Hop-by-hop headers, dropped by the rewrite (matched case-insensitively).
const HOP_BY_HOP: [&str; 7] = [
    "proxy-connection",
    "proxy-authorization",
    "connection",
    "keep-alive",
    "te",
    "trailer",
    "upgrade",
];

/// Returns (host, port, rewritten head) or None when the request is not an absolute-URI http request.
fn rewrite_head(head: &str) -> Option<(String, u16, String)> {
    let mut lines = head.split("\r\n");
    let first = lines.next()?;
    let mut parts = first.split(' ');
    let (method, uri, version) = (parts.next()?, parts.next()?, parts.next()?);
    let rest = uri.strip_prefix("http://")?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((h, p)) => (h, p.parse().ok()?),
        None => (authority, 80u16),
    };
    let mut out = format!("{method} {path} {version}\r\n");
    for line in lines {
        if line.is_empty() {
            continue;
        }
        let name = line.split(':').next().unwrap_or("").trim().to_ascii_lowercase();
        if HOP_BY_HOP.contains(&name.as_str()) {
            continue;
        }
        out.push_str(line);
        out.push_str("\r\n");
    }
    out.push_str("Connection: close\r\n\r\n");
    Some((host.to_string(), port, out))
}

/// Split a CONNECT target `host:port` (the host may be an IPv6 literal
/// in brackets) into (host without brackets, port).
fn split_host_port(target: &str) -> Option<(String, u16)> {
    let (host, port) = target.rsplit_once(':')?;
    let port: u16 = port.parse().ok()?;
    Some((
        host.trim_start_matches('[').trim_end_matches(']').to_string(),
        port,
    ))
}

/// Index just past the blank line (`\r\n\r\n`) that ends the head, if present.
fn find_head_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|i| i + 4)
}

/// Read the request head (up to the blank line) and any body bytes that
/// arrived in the same reads. Returns (head text, body prefix).
async fn read_head(client: &mut TcpStream) -> io::Result<(String, Vec<u8>)> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        let n = client.read(&mut chunk).await?;
        if n == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "client closed before sending the request head",
            ));
        }
        buf.extend_from_slice(&chunk[..n]);
        if buf.len() > MAX_HEAD_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "request head is too large",
            ));
        }
        if let Some(end) = find_head_end(&buf) {
            let head = String::from_utf8(buf[..end].to_vec()).map_err(|_| {
                io::Error::new(io::ErrorKind::InvalidData, "request head is not valid UTF-8")
            })?;
            let body = buf[end..].to_vec();
            return Ok((head, body));
        }
    }
}

/// Send a minimal response with an empty body and close.
async fn send_status(client: &mut TcpStream, code: u16, reason: &str) -> io::Result<()> {
    client
        .write_all(
            format!("HTTP/1.1 {code} {reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
                .as_bytes(),
        )
        .await
}

/// Handle one HTTP connection. A CONNECT request opens a tunnel to
/// `host:port`: the record's target is set as soon as it is parsed,
/// the target is dialed (DNS or connect failure gets a 502), success
/// replies `200 Connection Established`, any bytes the client already
/// sent after the head are forwarded, then both directions are relayed
/// untouched. Any other method is plain HTTP forwarding: the request
/// line must carry an absolute URI; anything else gets a 400. A target
/// that cannot be reached gets a 502. Bodies are never parsed or
/// logged: the bytes after the head are sent on untouched, and the
/// relay only counts them.
///
/// The connection record's target is set as soon as the request is
/// parsed, and the relay counts live into its up/down counters. The
/// rewritten head is not counted; body bytes already read are counted
/// as `up`. The client stream is consumed: on any outcome the
/// connection is closed when this returns.
pub async fn handle(
    mut client: TcpStream,
    outbound: &Connector,
    config: &Config,
    record: &std::sync::Arc<ConnRecord>,
) -> io::Result<()> {
    let (head, body_prefix) =
        match tokio::time::timeout(HEAD_TIMEOUT, read_head(&mut client)).await {
            Ok(Ok(v)) => v,
            _ => {
                let _ = send_status(&mut client, 400, "Bad Request").await;
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "bad HTTP request head",
                ));
            }
        };
    let mut first_parts = head.split(' ');
    if first_parts.next().unwrap_or("") == "CONNECT" {
        let target = first_parts.next().unwrap_or("");
        let (host, port) = match split_host_port(target) {
            Some(v) => v,
            None => {
                let _ = send_status(&mut client, 400, "Bad Request").await;
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "CONNECT target is not host:port",
                ));
            }
        };
        record.set_target(host.clone(), port);
        let mut upstream = match outbound
            .connect(&host, port, Duration::from_secs(config.connect_timeout_s))
            .await
        {
            Ok(stream) => stream,
            Err(e) => {
                let _ = send_status(&mut client, 502, "Bad Gateway").await;
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    format!("could not reach {host}:{port}: {e}"),
                ));
            }
        };
        upstream
            .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .await?;
        if !body_prefix.is_empty() {
            upstream.write_all(&body_prefix).await?;
            record
                .up
                .fetch_add(body_prefix.len() as u64, Ordering::Relaxed);
        }
        return relay::relay(
            client,
            upstream,
            &record.up,
            &record.down,
            Duration::from_secs(config.idle_timeout_s),
        )
        .await;
    }
    let (host, port, rewritten) = match rewrite_head(&head) {
        Some(v) => v,
        None => {
            let _ = send_status(&mut client, 400, "Bad Request").await;
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "request line is not an absolute-URI http request",
            ));
        }
    };
    record.set_target(host.clone(), port);
    let mut upstream = match outbound
        .connect(&host, port, Duration::from_secs(config.connect_timeout_s))
        .await
    {
        Ok(stream) => stream,
        Err(e) => {
            let _ = send_status(&mut client, 502, "Bad Gateway").await;
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("could not reach {host}:{port}: {e}"),
            ));
        }
    };
    upstream.write_all(rewritten.as_bytes()).await?;
    if !body_prefix.is_empty() {
        upstream.write_all(&body_prefix).await?;
        record
            .up
            .fetch_add(body_prefix.len() as u64, Ordering::Relaxed);
    }
    relay::relay(
        client,
        upstream,
        &record.up,
        &record.down,
        Duration::from_secs(config.idle_timeout_s),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rewrite_absolute_get() {
        let (host, port, head) = rewrite_head(
            "GET http://example.com:8080/a/b?x=1 HTTP/1.1\r\nHost: example.com:8080\r\nProxy-Connection: keep-alive\r\nX-A: b\r\n\r\n",
        )
        .expect("absolute URI rewrites");
        assert_eq!(host, "example.com");
        assert_eq!(port, 8080);
        assert_eq!(
            head,
            "GET /a/b?x=1 HTTP/1.1\r\nHost: example.com:8080\r\nX-A: b\r\nConnection: close\r\n\r\n"
        );
    }

    #[test]
    fn rewrite_defaults_to_port_80_and_root_path() {
        let (host, port, head) =
            rewrite_head("GET http://example.com HTTP/1.1\r\nHost: example.com\r\n\r\n")
                .expect("rewrites");
        assert_eq!((host.as_str(), port), ("example.com", 80));
        assert_eq!(
            head,
            "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
        );
    }

    #[test]
    fn rewrite_drops_all_hop_by_hop_headers() {
        let head = rewrite_head(
            "GET http://example.com/ HTTP/1.1\r\nProxy-Connection: a\r\nProxy-Authorization: b\r\nConnection: c\r\nKeep-Alive: d\r\nTE: e\r\nTrailer: f\r\nUpgrade: g\r\nX-Keep: h\r\n\r\n",
        )
        .expect("rewrites")
        .2;
        assert_eq!(
            head,
            "GET / HTTP/1.1\r\nX-Keep: h\r\nConnection: close\r\n\r\n"
        );
    }

    #[test]
    fn rewrite_rejects_non_absolute_uris() {
        assert!(rewrite_head("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n").is_none());
        assert!(rewrite_head("CONNECT example.com:443 HTTP/1.1\r\nHost: x\r\n\r\n").is_none());
        assert!(rewrite_head("GET https://example.com/ HTTP/1.1\r\nHost: x\r\n\r\n").is_none());
        assert!(
            rewrite_head("GET http://example.com:abc/ HTTP/1.1\r\nHost: x\r\n\r\n").is_none()
        );
        assert!(rewrite_head("garbage").is_none());
    }

    #[test]
    fn split_host_port_parses() {
        assert_eq!(
            split_host_port("example.com:443"),
            Some(("example.com".to_string(), 443))
        );
        assert_eq!(
            split_host_port("[::1]:8080"),
            Some(("::1".to_string(), 8080))
        );
        assert_eq!(
            split_host_port("127.0.0.1:1080"),
            Some(("127.0.0.1".to_string(), 1080))
        );
        assert_eq!(split_host_port("example.com"), None);
        assert_eq!(split_host_port("example.com:abc"), None);
        assert_eq!(split_host_port("example.com:"), None);
    }

    #[test]
    fn find_head_end_finds_the_blank_line() {
        assert_eq!(find_head_end(b"a\r\n\r\n"), Some(5));
        assert_eq!(find_head_end(b"a\r\n\r"), None);
        assert_eq!(find_head_end(b""), None);
    }
}
