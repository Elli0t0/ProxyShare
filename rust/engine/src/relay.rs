//! Copying bytes between the client and the target.
//!
//! Two pump tasks move bytes in opposite directions, each counting live
//! into the connection record's counters (`up` for client -> target,
//! `down` for target -> client), so the snapshot and the totals update
//! during a transfer, not only at the end. A pump ends when its read
//! side hits EOF, errors, or sits idle for `idle`; it then shuts down
//! its write half, so the peer sees EOF and its own pump ends too.
//!
//! A pump may take each chunk from a shared [`RateLimiter`]: the
//! configured speed limit applies to the TOTAL of all connections in
//! one direction, not per connection.
//!
//! The relay never looks at the bytes: it only counts them.

use std::io;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::Instant;

/// Token bucket shared by every connection in one direction: the
/// configured limit applies to the TOTAL of all connections, not per
/// connection. Tokens refill at `rate` bytes per second, capped at
/// max(rate, next chunk) so a chunk bigger than the rate can never
/// deadlock. `rate` must be > 0; unlimited is "no limiter" (None),
/// never a zero-rate bucket.
///
/// The clock is tokio's Instant, not std's: the two agree in
/// production, but only tokio's moves under tokio::time::pause, which
/// is what the unit test uses to prove the 2 s wait without waiting.
pub struct RateLimiter {
    rate: f64,
    state: Mutex<(f64, Instant)>,
}

impl RateLimiter {
    pub fn new(bytes_per_second: u64) -> Self {
        Self {
            rate: bytes_per_second as f64,
            state: Mutex::new((0.0, Instant::now())),
        }
    }

    /// Wait until `n` bytes may pass. Returns immediately when the
    /// bucket holds enough tokens.
    pub async fn take(&self, n: usize) {
        loop {
            let wait = {
                let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
                let now = Instant::now();
                let cap = self.rate.max(n as f64); // never deadlock when n > rate
                s.0 = (s.0 + now.duration_since(s.1).as_secs_f64() * self.rate).min(cap);
                s.1 = now;
                if s.0 >= n as f64 {
                    s.0 -= n as f64;
                    None
                } else {
                    Some((n as f64 - s.0) / self.rate)
                }
            };
            match wait {
                None => return,
                Some(secs) => tokio::time::sleep(Duration::from_secs_f64(secs)).await,
            }
        }
    }
}

/// Copy bytes from `r` to `w` until the read side hits EOF, errors, or
/// sits idle for `idle`. Every copied chunk is counted live into `ctr`.
/// On EOF the write half is shut down, so the peer sees a clean FIN.
/// When `limiter` is set, each chunk waits for its tokens first. The
/// buffer starts at 8 KB and doubles up to 64 KB whenever a read fills
/// it, so idle or tiny connections don't pay for a 32 KB buffer up
/// front.
pub async fn pump<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    mut r: R,
    mut w: W,
    ctr: &AtomicU64,
    idle: Duration,
    limiter: Option<&Arc<RateLimiter>>,
) -> io::Result<()> {
    let mut buf = vec![0u8; 8 * 1024];
    loop {
        let n = tokio::time::timeout(idle, r.read(&mut buf))
            .await
            .map_err(|_| io::ErrorKind::TimedOut)??;
        if n == 0 {
            let _ = w.shutdown().await;
            return Ok(());
        }
        if n == buf.len() && buf.len() < 64 * 1024 {
            let new_len = buf.len() * 2;
            buf.resize(new_len, 0);
        }
        if let Some(limiter) = limiter {
            limiter.take(n).await;
        }
        w.write_all(&buf[..n]).await?;
        ctr.fetch_add(n as u64, Ordering::Relaxed);
    }
}

/// Relay a connected client/upstream pair: one pump per direction, run
/// with `tokio::join!`. When either pump ends, the other ends with it
/// (its write half was shut down, so the peer closes and the other
/// pump's read hits EOF); in the worst case the idle timeout bounds a
/// peer that ignores the FIN. The first error wins. `up_limiter`
/// (client -> target) and `down_limiter` (target -> client) are shared
/// across all connections; None means unlimited in that direction.
pub async fn relay(
    client: TcpStream,
    upstream: TcpStream,
    up: &AtomicU64,
    down: &AtomicU64,
    idle: Duration,
    up_limiter: Option<&Arc<RateLimiter>>,
    down_limiter: Option<&Arc<RateLimiter>>,
) -> io::Result<()> {
    let (client_r, client_w) = client.into_split();
    let (up_r, up_w) = upstream.into_split();
    let (r1, r2) = tokio::join!(
        pump(client_r, up_w, up, idle, up_limiter),
        pump(up_r, client_w, down, idle, down_limiter),
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
            pump(relay_in, relay_out, &ctr, Duration::from_secs(5), None),
            async {
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
    async fn pump_grows_its_buffer_for_large_chunks() {
        // A 20 KB write fills the 8 KB start buffer, so the pump must
        // grow it (to 16 KB, then it drains) instead of stalling.
        let (mut client_end, relay_in) = tokio::io::duplex(64 * 1024);
        let (relay_out, mut server_end) = tokio::io::duplex(64 * 1024);
        let ctr = AtomicU64::new(0);
        let payload = vec![7u8; 20 * 1024];
        let expected = payload.clone();
        let (result, _) = tokio::join!(
            pump(relay_in, relay_out, &ctr, Duration::from_secs(5), None),
            async {
                client_end.write_all(&payload).await.expect("write");
                let mut buf = vec![0u8; 20 * 1024];
                server_end.read_exact(&mut buf).await.expect("read");
                assert_eq!(buf, expected);
                assert_eq!(ctr.load(Ordering::Relaxed), 20 * 1024);
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
        let err = pump(relay_in, relay_out, &ctr, Duration::from_millis(50), None)
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
            relay(
                relay_client,
                upstream,
                &up,
                &down,
                Duration::from_secs(5),
                None,
                None
            ),
            async {
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

    #[tokio::test]
    async fn limiter_small_take_returns_quickly() {
        let limiter = RateLimiter::new(1024 * 1024);
        let start = Instant::now();
        limiter.take(1024).await;
        assert!(start.elapsed() < Duration::from_millis(100));
    }

    #[tokio::test(start_paused = true)]
    async fn limiter_one_megabyte_at_512k_takes_about_two_seconds() {
        let limiter = RateLimiter::new(512 * 1024);
        let start = tokio::time::Instant::now();
        let taking = tokio::spawn(async move { limiter.take(1024 * 1024).await });
        tokio::time::advance(Duration::from_millis(1900)).await;
        assert!(!taking.is_finished(), "1 MB must not pass in 1.9 s at 512 KB/s");
        tokio::time::advance(Duration::from_millis(200)).await;
        taking.await.expect("take completes");
        let elapsed = tokio::time::Instant::now() - start;
        assert!(elapsed >= Duration::from_millis(2000), "took {elapsed:?}");
        assert!(elapsed < Duration::from_millis(2500), "took {elapsed:?}");
    }
}
