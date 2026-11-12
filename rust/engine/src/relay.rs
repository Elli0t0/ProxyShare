//! Copying bytes between the client and the target.
//!
//! Two pump tasks move bytes in opposite directions, each counting live
//! into the connection record's counters (`up` for client -> target,
//! `down` for target -> client), so the snapshot and the totals update
//! during a transfer, not only at the end. A pump ends when its read
//! side hits EOF, errors, or sits idle for `idle`; it then shuts down
//! its write half, so the peer sees EOF and its own pump ends too.
//!
//! The relay never looks at the bytes: it only counts them.

use std::io;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

/// Copy bytes from `r` to `w` until the read side hits EOF, errors, or
/// sits idle for `idle`. Every copied chunk is counted live into `ctr`.
/// On EOF the write half is shut down, so the peer sees a clean FIN.
pub async fn pump<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    mut r: R,
    mut w: W,
    ctr: &AtomicU64,
    idle: Duration,
) -> io::Result<()> {
    let mut buf = vec![0u8; 16 * 1024];
    loop {
        let n = tokio::time::timeout(idle, r.read(&mut buf))
            .await
            .map_err(|_| io::ErrorKind::TimedOut)??;
        if n == 0 {
            let _ = w.shutdown().await;
            return Ok(());
        }
        w.write_all(&buf[..n]).await?;
        ctr.fetch_add(n as u64, Ordering::Relaxed);
    }
}

/// Relay a connected client/upstream pair: one pump per direction, run
/// with `tokio::join!`. When either pump ends, the other ends with it
/// (its write half was shut down, so the peer closes and the other
/// pump's read hits EOF); in the worst case the idle timeout bounds a
/// peer that ignores the FIN. The first error wins.
pub async fn relay(
    client: TcpStream,
    upstream: TcpStream,
    up: &AtomicU64,
    down: &AtomicU64,
    idle: Duration,
) -> io::Result<()> {
    let (client_r, client_w) = client.into_split();
    let (up_r, up_w) = upstream.into_split();
    let (r1, r2) = tokio::join!(
        pump(client_r, up_w, up, idle),
        pump(up_r, client_w, down, idle),
    );
    match (r1, r2) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(e), _) => Err(e),
        (_, Err(e)) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn pump_counts_bytes_live_and_eofs_cleanly() {
        let (mut client_end, relay_in) = tokio::io::duplex(64);
        let (relay_out, mut server_end) = tokio::io::duplex(64);
        let ctr = AtomicU64::new(0);
        let (result, _) = tokio::join!(
            pump(relay_in, relay_out, &ctr, Duration::from_secs(5)),
            async move {
                client_end.write_all(b"hello").await.expect("write");
                let mut buf = [0u8; 5];
                server_end.read_exact(&mut buf).await.expect("read");
                assert_eq!(&buf, b"hello");
                // The counter moved while the transfer was still running.
                assert_eq!(ctr.load(Ordering::Relaxed), 5);
                drop(client_end);
                drop(server_end);
            },
        );
        result.expect("pump ends cleanly on EOF");
    }

    #[tokio::test]
    async fn pump_times_out_when_idle() {
        let (_a, relay_in) = tokio::io::duplex(64);
        let (_b, relay_out) = tokio::io::duplex(64);
        let ctr = AtomicU64::new(0);
        let err = pump(relay_in, relay_out, &ctr, Duration::from_millis(50))
            .await
            .expect_err("an idle pump must time out");
        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert_eq!(ctr.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn relay_over_real_sockets_echoes() {
        // A local echo server: whatever it reads, it writes back.
        let echo = TcpListener::bind("127.0.0.1:0").await.expect("bind echo");
        let echo_port = echo.local_addr().expect("echo addr").port();
        tokio::spawn(async move {
            if let Ok((stream, _)) = echo.accept().await {
                let (mut r, mut w) = stream.into_split();
                let _ = tokio::io::copy(&mut r, &mut w).await;
            }
        });
        // A local listener stands in for the client side of the relay.
        let front = TcpListener::bind("127.0.0.1:0").await.expect("bind front");
        let front_port = front.local_addr().expect("front addr").port();
        let upstream = TcpStream::connect(("127.0.0.1", echo_port))
            .await
            .expect("connect echo");
        let mut client_side = TcpStream::connect(("127.0.0.1", front_port))
            .await
            .expect("connect front");
        let (relay_client, _) = front.accept().await.expect("accept front");
        let up = AtomicU64::new(0);
        let down = AtomicU64::new(0);
        let (result, _) = tokio::join!(
            relay(relay_client, upstream, &up, &down, Duration::from_secs(5)),
            async move {
                client_side.write_all(b"ping").await.expect("write");
                let mut buf = [0u8; 4];
                client_side.read_exact(&mut buf).await.expect("read echo");
                assert_eq!(&buf, b"ping");
                assert_eq!(up.load(Ordering::Relaxed), 4);
                assert_eq!(down.load(Ordering::Relaxed), 4);
                drop(client_side);
            },
        );
        result.expect("relay ends cleanly when the client closes");
    }
}
