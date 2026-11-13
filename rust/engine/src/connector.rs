//! Outbound connections: how the engine reaches the target.
//!
//! `Outbound` is the seam a later step uses to bind sockets to an
//! Android Network: the SOCKS5 handler only ever talks to the trait,
//! never to a concrete socket. `TcpOutbound` is the plain
//! implementation: resolve with `lookup_host`, try the addresses in
//! order, apply the connect timeout, set `TCP_NODELAY`.
//!
//! A connect failure is one of two kinds, so the handler can answer
//! with the right SOCKS5 reply code: the name did not resolve (`Dns`,
//! reply 0x04) or no address accepted the connection (`Connect`,
//! reply 0x05).

use std::fmt;
use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use tokio::net::{lookup_host, TcpStream};

/// Why a connect failed: the name did not resolve, or no address
/// accepted the connection.
#[derive(Debug)]
pub enum ConnectError {
    /// The host name could not be resolved (or resolved to nothing).
    Dns(io::Error),
    /// Resolution worked (or the target was an IP literal), but every
    /// address refused or timed out.
    Connect(io::Error),
}

impl fmt::Display for ConnectError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConnectError::Dns(e) => write!(f, "could not resolve the target: {e}"),
            ConnectError::Connect(e) => write!(f, "could not connect to the target: {e}"),
        }
    }
}

impl std::error::Error for ConnectError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            ConnectError::Dns(e) | ConnectError::Connect(e) => Some(e),
        }
    }
}

impl From<ConnectError> for io::Error {
    fn from(err: ConnectError) -> io::Error {
        match err {
            ConnectError::Dns(e) | ConnectError::Connect(e) => e,
        }
    }
}

/// Opens outbound TCP connections: resolve with `lookup_host`, try the
/// addresses in order, apply the connect timeout, set `TCP_NODELAY`.
/// A plain struct; 0.1.1 adds a network-handle field to it.
#[derive(Default)]
pub struct Connector;

impl Connector {
    /// Connect to `host:port` within `timeout` (the timeout bounds the
    /// DNS lookup and each connect attempt).
    pub async fn connect(
        &self,
        host: &str,
        port: u16,
        timeout: Duration,
    ) -> Result<TcpStream, ConnectError> {
        let addrs: Vec<SocketAddr> =
            match tokio::time::timeout(timeout, lookup_host((host, port))).await {
                Ok(Ok(addrs)) => addrs.collect(),
                Ok(Err(e)) => return Err(ConnectError::Dns(e)),
                Err(_) => {
                    return Err(ConnectError::Dns(io::Error::new(
                        io::ErrorKind::TimedOut,
                        format!("DNS lookup for {host} timed out"),
                    )))
                }
            };
        if addrs.is_empty() {
            return Err(ConnectError::Dns(io::Error::new(
                io::ErrorKind::NotFound,
                format!("{host} resolved to no addresses"),
            )));
        }
        let mut last_err =
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no address was tried");
        for addr in addrs {
            match tokio::time::timeout(timeout, TcpStream::connect(addr)).await {
                Ok(Ok(stream)) => {
                    stream
                        .set_nodelay(true)
                        .map_err(ConnectError::Connect)?;
                    return Ok(stream);
                }
                Ok(Err(e)) => last_err = e,
                Err(_) => {
                    last_err = io::Error::new(
                        io::ErrorKind::TimedOut,
                        format!("connect to {addr} timed out"),
                    )
                }
            }
        }
        Err(ConnectError::Connect(last_err))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn connects_to_a_local_listener() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let stream = Connector
            .connect("127.0.0.1", port, Duration::from_secs(2))
            .await
            .expect("connect to a listening socket");
        assert!(stream.nodelay().expect("read nodelay"), "TCP_NODELAY is set");
        assert_eq!(stream.peer_addr().expect("peer").port(), port);
    }

    #[tokio::test]
    async fn connect_failure_is_a_connect_error() {
        // A port that is free right now, so nothing listens on it.
        let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("probe bind");
        let port = probe.local_addr().expect("probe addr").port();
        drop(probe);
        let err = Connector
            .connect("127.0.0.1", port, Duration::from_secs(2))
            .await
            .expect_err("nothing listens on the port");
        assert!(
            matches!(err, ConnectError::Connect(_)),
            "a refused connect is a Connect error, got: {err}"
        );
    }

    #[tokio::test]
    async fn bad_domain_is_a_dns_error() {
        // ".invalid" never resolves (RFC 2606), with or without network.
        let err = Connector
            .connect("nonexistent.invalid", 80, Duration::from_secs(2))
            .await
            .expect_err("the domain does not resolve");
        assert!(
            matches!(err, ConnectError::Dns(_)),
            "a failed lookup is a Dns error, got: {err}"
        );
    }
}
