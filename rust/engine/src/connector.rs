//! Outbound connections: how the engine reaches the target.
//!
//! `Outbound` is the seam a later step uses to bind sockets to an
//! Android Network: the SOCKS5 handler only ever talks to the trait,
//! never to a concrete socket. `TcpOutbound` is the plain
//! implementation: resolve (on the outbound network when one is set),
//! try the addresses IPv4 first with each attempt capped at 5 s, apply
//! the connect timeout, set `TCP_NODELAY`.
//!
//! A connect failure is one of two kinds, so the handler can answer
//! with the right SOCKS5 reply code: the name did not resolve (`Dns`,
//! reply 0x04) or no address accepted the connection (`Connect`,
//! reply 0x05).

use std::fmt;
use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

#[cfg(target_os = "android")]
use std::ffi::CString;
#[cfg(target_os = "android")]
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
#[cfg(target_os = "android")]
use std::os::raw::{c_char, c_int};
#[cfg(target_os = "android")]
use std::sync::OnceLock;

use tokio::net::{lookup_host, TcpSocket, TcpStream};

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

/// Opens outbound TCP connections: resolve (on the outbound network
/// when one is set), try the addresses IPv4 first with each attempt
/// capped at 5 s, apply the connect timeout, set `TCP_NODELAY`.
/// Holds the Android Network handle for outbound sockets (0 = default
/// network); 0.1.6.
#[derive(Default)]
pub struct Connector {
    /// Android net_handle_t for outbound sockets; 0 = default network.
    network: AtomicU64,
}

impl Connector {
    pub fn set_network(&self, handle: u64) {
        self.network.store(handle, Ordering::Relaxed);
    }
    pub fn network(&self) -> u64 {
        self.network.load(Ordering::Relaxed)
    }

    /// Connect to `host:port` within `timeout` (the timeout bounds the
    /// DNS lookup; each connect attempt is capped at 5 s). Addresses
    /// are tried IPv4 first.
    pub async fn connect(
        &self,
        host: &str,
        port: u16,
        timeout: Duration,
    ) -> Result<TcpStream, ConnectError> {
        let mut addrs = self.resolve(host, port, timeout).await?;
        if addrs.is_empty() {
            return Err(ConnectError::Dns(io::Error::new(
                io::ErrorKind::NotFound,
                format!("{host} resolved to no addresses"),
            )));
        }
        order_addrs(&mut addrs);
        let attempt_timeout = attempt_timeout(timeout);
        let mut last_err =
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no address was tried");
        for addr in addrs {
            match tokio::time::timeout(attempt_timeout, connect_addr(addr, self.network())).await {
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

    /// Resolve host:port. On Android with a non-default network, DNS
    /// runs on that same network (see resolve_on_network); otherwise,
    /// or when the network resolver is unavailable, the default lookup
    /// runs.
    async fn resolve(
        &self,
        host: &str,
        port: u16,
        timeout: Duration,
    ) -> Result<Vec<SocketAddr>, ConnectError> {
        let network = self.network();
        if network != 0 {
            let host_owned = host.to_string();
            let outcome = tokio::time::timeout(
                timeout,
                tokio::task::spawn_blocking(move || {
                    resolve_on_network(&host_owned, port, network)
                }),
            )
            .await;
            match outcome {
                Ok(Ok(NetworkDns::Done(result))) => return result.map_err(ConnectError::Dns),
                Ok(Ok(NetworkDns::Fallback)) => { /* fall through to the default lookup */ }
                Ok(Err(_)) => {
                    return Err(ConnectError::Dns(io::Error::new(
                        io::ErrorKind::Other,
                        "DNS resolver task failed",
                    )))
                }
                Err(_) => {
                    return Err(ConnectError::Dns(io::Error::new(
                        io::ErrorKind::TimedOut,
                        format!("DNS lookup for {host} timed out"),
                    )))
                }
            }
        }
        lookup_default(host, port, timeout).await
    }
}

/// The plain lookup_host path, bounded by `timeout`.
async fn lookup_default(
    host: &str,
    port: u16,
    timeout: Duration,
) -> Result<Vec<SocketAddr>, ConnectError> {
    match tokio::time::timeout(timeout, lookup_host((host, port))).await {
        Ok(Ok(addrs)) => Ok(addrs.collect()),
        Ok(Err(e)) => Err(ConnectError::Dns(e)),
        Err(_) => Err(ConnectError::Dns(io::Error::new(
            io::ErrorKind::TimedOut,
            format!("DNS lookup for {host} timed out"),
        ))),
    }
}

/// Order resolved addresses: IPv4 first (a VPN is often IPv4-only, and
/// a dead AAAA must not burn the whole connect timeout). Stable: the
/// order within each family is kept.
fn order_addrs(addrs: &mut [SocketAddr]) {
    addrs.sort_by_key(|a| a.is_ipv6());
}

/// One connect attempt gets at most 5 s, even when the configured
/// timeout is longer, so a dead address can't stall a page load.
fn attempt_timeout(timeout: Duration) -> Duration {
    timeout.min(Duration::from_secs(5))
}

// --- DNS on the outbound network (Android only) ---

/// What the network resolver decided.
pub(crate) enum NetworkDns {
    /// Use the default resolver (no network, device API < 29, or the
    /// android_getaddrinfofornetwork symbol is missing).
    Fallback,
    /// The network resolver ran; this result is authoritative.
    Done(io::Result<Vec<SocketAddr>>),
}

/// Resolve host:port on the Android network `network` (net_handle_t),
/// so DNS does not leak through the default network in Wi-Fi/Mobile
/// outbound mode. Runs on a blocking thread (called from
/// spawn_blocking): android_getaddrinfofornetwork is a synchronous C
/// call. Returns Fallback when there is no network, the device is
/// below API 29, or the symbol is missing; the caller then uses
/// lookup_host.
#[cfg(target_os = "android")]
pub(crate) fn resolve_on_network(host: &str, port: u16, network: u64) -> NetworkDns {
    if network == 0 || device_api_level() < 29 {
        return NetworkDns::Fallback;
    }
    let Some(func) = getaddrinfo_fn() else {
        return NetworkDns::Fallback;
    };
    let Ok(host_c) = CString::new(host) else {
        return NetworkDns::Done(Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "host contains a NUL byte",
        )));
    };
    let service_c = CString::new(port.to_string()).expect("a port number has no NUL byte");
    let hints = libc::addrinfo {
        ai_flags: 0,
        ai_family: libc::AF_UNSPEC,
        ai_socktype: libc::SOCK_STREAM,
        ai_protocol: 0,
        ai_addrlen: 0,
        ai_addr: std::ptr::null_mut(),
        ai_canonname: std::ptr::null_mut(),
        ai_next: std::ptr::null_mut(),
    };
    let mut res: *mut libc::addrinfo = std::ptr::null_mut();
    // SAFETY: func is the real android_getaddrinfofornetwork from
    // libandroid (loaded by dlsym); host/service are valid
    // NUL-terminated strings, hints is a valid addrinfo, res is a
    // valid out-pointer.
    let rc = unsafe { func(network, host_c.as_ptr(), service_c.as_ptr(), &hints, &mut res) };
    if rc != 0 {
        return NetworkDns::Done(Err(io::Error::new(
            io::ErrorKind::Other,
            format!("android_getaddrinfofornetwork failed ({rc})"),
        )));
    }
    let mut out = Vec::new();
    let mut cur = res;
    while !cur.is_null() {
        // SAFETY: cur points into the linked list returned by the call.
        unsafe {
            if let Some(addr) = sockaddr_to_socketaddr((*cur).ai_addr) {
                out.push(addr);
            }
            cur = (*cur).ai_next;
        }
    }
    // SAFETY: res is the list returned by the call; it must be freed
    // exactly once, on every path after a successful call.
    unsafe { libc::freeaddrinfo(res) };
    NetworkDns::Done(Ok(out))
}

/// Non-Android builds have no libandroid: always fall back.
#[cfg(not(target_os = "android"))]
pub(crate) fn resolve_on_network(
    _host: &str,
    _port: u16,
    _network: u64,
) -> NetworkDns {
    NetworkDns::Fallback
}

#[cfg(target_os = "android")]
type GetAddrInfoForNetworkFn = unsafe extern "C" fn(
    u64,
    *const c_char,
    *const c_char,
    *const libc::addrinfo,
    *mut *mut libc::addrinfo,
) -> c_int;

#[cfg(target_os = "android")]
type GetDeviceApiLevelFn = unsafe extern "C" fn() -> c_int;

#[cfg(target_os = "android")]
static GETADDRINFO_FN: OnceLock<Option<GetAddrInfoForNetworkFn>> = OnceLock::new();

#[cfg(target_os = "android")]
static API_LEVEL_FN: OnceLock<Option<GetDeviceApiLevelFn>> = OnceLock::new();

/// Load a symbol from libandroid.so at runtime, or None when it is
/// missing (older device). The handle is never closed: the function
/// pointer must stay valid for the process lifetime.
#[cfg(target_os = "android")]
fn load_libandroid_fn<T>(name: &str) -> Option<T> {
    let cname = CString::new(name).ok()?;
    // SAFETY: dlopen/dlsym are called with valid NUL-terminated names;
    // the returned pointer is transmuted to the caller's function type,
    // which must match the real symbol (see the type aliases above).
    unsafe {
        let handle = libc::dlopen(
            b"libandroid.so\0".as_ptr() as *const c_char,
            libc::RTLD_NOW,
        );
        if handle.is_null() {
            return None;
        }
        let sym = libc::dlsym(handle, cname.as_ptr());
        if sym.is_null() {
            return None;
        }
        Some(std::mem::transmute_copy(&sym))
    }
}

#[cfg(target_os = "android")]
fn getaddrinfo_fn() -> Option<GetAddrInfoForNetworkFn> {
    *GETADDRINFO_FN.get_or_init(|| load_libandroid_fn("android_getaddrinfofornetwork"))
}

#[cfg(target_os = "android")]
fn device_api_level() -> i32 {
    let Some(func) = *API_LEVEL_FN
        .get_or_init(|| load_libandroid_fn("android_get_device_api_level"))
    else {
        return 0;
    };
    // SAFETY: func is the real android_get_device_api_level from libandroid.
    unsafe { func() }
}

/// Convert one sockaddr from a getaddrinfo result to a SocketAddr.
/// The port and the IPv4 address are in network byte order.
#[cfg(target_os = "android")]
unsafe fn sockaddr_to_socketaddr(sa: *const libc::sockaddr) -> Option<SocketAddr> {
    if sa.is_null() {
        return None;
    }
    // SAFETY: the caller guarantees sa points at a valid sockaddr from
    // the getaddrinfo result, whose family says which struct it is.
    unsafe {
        match (*sa).sa_family as c_int {
            libc::AF_INET => {
                let sin = &*(sa as *const libc::sockaddr_in);
                Some(SocketAddr::new(
                    IpAddr::V4(Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr))),
                    u16::from_be(sin.sin_port),
                ))
            }
            libc::AF_INET6 => {
                let sin6 = &*(sa as *const libc::sockaddr_in6);
                Some(SocketAddr::new(
                    IpAddr::V6(Ipv6Addr::from(sin6.sin6_addr.s6_addr)),
                    u16::from_be(sin6.sin6_port),
                ))
            }
            _ => None,
        }
    }
}

/// Open one outbound TCP connection to `addr`, bound to the Android
/// Network `network` (0 = default network).
async fn connect_addr(addr: SocketAddr, network: u64) -> io::Result<TcpStream> {
    let socket = if addr.is_ipv4() {
        TcpSocket::new_v4()?
    } else {
        TcpSocket::new_v6()?
    };
    crate::netbind::bind_socket_to_network(socket.as_raw_fd(), network)?;
    socket.connect(addr).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn connects_to_a_local_listener() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let stream = Connector::default()
            .connect("127.0.0.1", port, Duration::from_secs(2))
            .await
            .expect("connect to a listening socket");
        assert!(stream.nodelay().expect("read nodelay"), "TCP_NODELAY is set");
        assert_eq!(stream.peer_addr().expect("peer").port(), port);
    }

    #[tokio::test]
    async fn connects_with_a_network_handle_set() {
        // A handle is a no-op on the host, so the connect still works.
        let connector = Connector::default();
        connector.set_network(7);
        assert_eq!(connector.network(), 7);
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let stream = connector
            .connect("127.0.0.1", port, Duration::from_secs(2))
            .await
            .expect("connect with a network handle set");
        assert!(stream.nodelay().expect("read nodelay"), "TCP_NODELAY is set");
        assert_eq!(stream.peer_addr().expect("peer").port(), port);
    }

    #[tokio::test]
    async fn connect_failure_is_a_connect_error() {
        // A port that is free right now, so nothing listens on it.
        let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("probe bind");
        let port = probe.local_addr().expect("probe addr").port();
        drop(probe);
        let err = Connector::default()
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
        let err = Connector::default()
            .connect("nonexistent.invalid", 80, Duration::from_secs(2))
            .await
            .expect_err("the domain does not resolve");
        assert!(
            matches!(err, ConnectError::Dns(_)),
            "a failed lookup is a Dns error, got: {err}"
        );
    }

    #[test]
    fn ipv4_sorts_before_ipv6_and_keeps_order() {
        let mut addrs: Vec<SocketAddr> = vec![
            "[2001:db8::1]:80".parse().expect("v6"),
            "192.0.2.1:80".parse().expect("v4"),
            "[2001:db8::2]:80".parse().expect("v6"),
            "198.51.100.1:80".parse().expect("v4"),
        ];
        order_addrs(&mut addrs);
        assert!(addrs[0].is_ipv4() && addrs[1].is_ipv4());
        assert!(addrs[2].is_ipv6() && addrs[3].is_ipv6());
        // Stable: the v4s and the v6s each keep their relative order.
        assert_eq!(addrs[0], "192.0.2.1:80".parse().expect("v4"));
        assert_eq!(addrs[2], "[2001:db8::1]:80".parse().expect("v6"));
    }

    #[test]
    fn attempt_timeout_caps_at_five_seconds() {
        assert_eq!(attempt_timeout(Duration::from_secs(10)), Duration::from_secs(5));
        assert_eq!(attempt_timeout(Duration::from_secs(2)), Duration::from_secs(2));
        assert_eq!(attempt_timeout(Duration::from_secs(5)), Duration::from_secs(5));
    }

    /// Host builds have no libandroid, so the network resolver always
    /// falls back to lookup_host.
    #[cfg(not(target_os = "android"))]
    #[test]
    fn network_dns_falls_back_on_host() {
        assert!(matches!(
            resolve_on_network("example.com", 80, 7),
            NetworkDns::Fallback
        ));
    }
}
