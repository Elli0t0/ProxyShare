//! Outbound connections: how the engine reaches the target.
//!
//! `Outbound` is the seam a later step uses to bind sockets to an
//! Android Network: the SOCKS5 handler only ever talks to the trait,
//! never to a concrete socket. `TcpOutbound` is the plain
//! implementation: resolve (on the outbound network when one is set,
//! cached per network + host — see `DnsCache`), try the addresses
//! IPv4 first, staggered 300 ms apart (RFC 8305, simplified), each
//! attempt capped at 5 s, apply the connect timeout, set
//! `TCP_NODELAY`.
//!
//! A connect failure is one of three kinds, so the handler can answer
//! with the right SOCKS5 reply code: the name did not resolve (`Dns`,
//! reply 0x04), no address accepted the connection (`Connect`, reply
//! 0x05), or the target is IPv6-only while the IPv6 breaker is down
//! (`Unreachable`, reply 0x03).

use std::collections::HashMap;
use std::fmt;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

#[cfg(target_os = "android")]
use std::ffi::CString;
#[cfg(target_os = "android")]
use std::net::{Ipv4Addr, Ipv6Addr};
#[cfg(target_os = "android")]
use std::os::raw::{c_char, c_int};
#[cfg(target_os = "android")]
use std::sync::OnceLock;

use tokio::net::{lookup_host, TcpSocket, TcpStream};
use tokio::task::JoinSet;

/// Why a connect failed: the name did not resolve, no address
/// accepted the connection, or the IPv6 breaker refused an IPv6-only
/// target at once.
#[derive(Debug)]
pub enum ConnectError {
    /// The host name could not be resolved (or resolved to nothing).
    Dns(io::Error),
    /// Resolution worked (or the target was an IP literal), but every
    /// address refused or timed out.
    Connect(io::Error),
    /// The target is IPv6-only and IPv6 recently failed (the breaker
    /// is down), so the attempt is refused immediately instead of
    /// waiting out the 5 s cap. Handlers map it to SOCKS5 0x03 and
    /// HTTP 502.
    Unreachable(io::Error),
}

impl fmt::Display for ConnectError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConnectError::Dns(e) => write!(f, "could not resolve the target: {e}"),
            ConnectError::Connect(e) => write!(f, "could not connect to the target: {e}"),
            ConnectError::Unreachable(e) => {
                write!(f, "IPv6 is down, target unreachable at once: {e}")
            }
        }
    }
}

impl std::error::Error for ConnectError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            ConnectError::Dns(e) | ConnectError::Connect(e) | ConnectError::Unreachable(e) => {
                Some(e)
            }
        }
    }
}

impl From<ConnectError> for io::Error {
    fn from(err: ConnectError) -> io::Error {
        match err {
            ConnectError::Dns(e) | ConnectError::Connect(e) | ConnectError::Unreachable(e) => e,
        }
    }
}

/// Opens outbound TCP connections: resolve (on the outbound network
/// when one is set; results are cached — see `DnsCache`), try the
/// addresses IPv4 first, staggered 300 ms apart (RFC 8305,
/// simplified), each attempt capped at 5 s, apply the connect timeout,
/// set `TCP_NODELAY`. Holds the Android Network handle for outbound
/// sockets (0 = default network); 0.1.6.
#[derive(Default)]
pub struct Connector {
    /// Android net_handle_t for outbound sockets; 0 = default network.
    network: AtomicU64,
}

impl Connector {
    pub fn set_network(&self, handle: u64) {
        self.network.store(handle, Ordering::Relaxed);
        // A new network means new addresses: drop cached DNS and
        // reset the IPv6 breaker so the next attempt is judged fresh.
        DNS_CACHE.clear();
        mark_v6_up();
    }
    pub fn network(&self) -> u64 {
        self.network.load(Ordering::Relaxed)
    }

    /// Drop the cached DNS entries (engine start does this, so a fresh
    /// run re-resolves everything instead of trusting the old network).
    pub fn flush_dns_cache(&self) {
        DNS_CACHE.clear();
    }

    /// Connect to `host:port` within `timeout` (the timeout bounds the
    /// DNS lookup; each connect attempt is capped at 5 s). Addresses
    /// are tried IPv4 first, staggered 300 ms apart; the first success
    /// wins and the losers are dropped.
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
        // While the IPv6 breaker is down, IPv6 addresses are skipped
        // when IPv4 ones exist; an IPv6-only target is refused at
        // once instead of burning the 5 s cap.
        let down_until = V6_DOWN_UNTIL_MS.load(Ordering::Relaxed);
        let addrs = match v6_filter(addrs, down_until, now_ms()) {
            Ok(addrs) => addrs,
            Err(()) => {
                return Err(ConnectError::Unreachable(io::Error::new(
                    io::ErrorKind::AddrNotAvailable,
                    format!("IPv6 is down (breaker tripped): {host}"),
                )))
            }
        };
        let cap = attempt_timeout(timeout);
        let network = self.network();
        let mut pending = JoinSet::new();
        let mut next = 1usize;
        let mut last_err =
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no address was tried");
        pending.spawn(connect_one(addrs[0], network, cap));
        loop {
            tokio::select! {
                _ = tokio::time::sleep(STAGGER), if next < addrs.len() => {
                    pending.spawn(connect_one(addrs[next], network, cap));
                    next += 1;
                }
                Some(result) = pending.join_next() => {
                    match result {
                        Ok(Ok((addr, stream))) => {
                            if addr.is_ipv6() {
                                mark_v6_up();
                            }
                            stream
                                .set_nodelay(true)
                                .map_err(ConnectError::Connect)?;
                            return Ok(stream);
                        }
                        Ok(Err((addr, e))) => {
                            if addr.is_ipv6() {
                                mark_v6_down();
                            }
                            last_err = e;
                        }
                        Err(_) => {
                            // A spawned attempt panicked; treat it as
                            // a failed attempt and keep trying the rest.
                        }
                    }
                }
                else => break,
            }
        }
        Err(ConnectError::Connect(last_err))
    }

    /// Resolve host:port, using the DNS cache when the host is not an
    /// IP literal. On Android with a non-default network, DNS runs on
    /// that same network (see resolve_on_network); otherwise, or when
    /// the network resolver is unavailable, the default lookup runs.
    async fn resolve(
        &self,
        host: &str,
        port: u16,
        timeout: Duration,
    ) -> Result<Vec<SocketAddr>, ConnectError> {
        // IP literals need no lookup and are never cached.
        let key: Option<DnsKey> = if host.parse::<IpAddr>().is_ok() {
            None
        } else {
            Some((self.network(), host.to_lowercase(), port))
        };
        if let Some(key) = &key {
            if let Some(cached) = DNS_CACHE.get(key, Instant::now()) {
                return cached.map_err(|()| {
                    ConnectError::Dns(io::Error::new(
                        io::ErrorKind::NotFound,
                        format!("{host} (cached DNS failure)"),
                    ))
                });
            }
        }
        let result = self.resolve_uncached(host, port, timeout).await;
        if let Some(key) = key {
            let cached: Result<Vec<SocketAddr>, ()> = match &result {
                Ok(addrs) => Ok(addrs.clone()),
                Err(_) => Err(()),
            };
            DNS_CACHE.put(key, cached, Instant::now());
        }
        result
    }

    /// The uncached lookup: network resolver first, then the default.
    async fn resolve_uncached(
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
                    return Err(ConnectError::Dns(io::Error::other(
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

/// Gap between staggered connect attempts (RFC 8305, simplified):
/// the next address starts this long after the previous one when
/// nothing has connected yet.
const STAGGER: Duration = Duration::from_millis(300);

/// One staggered attempt: connect to `addr` (bound to `network`)
/// with the per-attempt cap. The address travels with the outcome so
/// the caller can feed the IPv6 breaker.
async fn connect_one(
    addr: SocketAddr,
    network: u64,
    cap: Duration,
) -> Result<(SocketAddr, TcpStream), (SocketAddr, io::Error)> {
    match tokio::time::timeout(cap, connect_addr(addr, network)).await {
        Ok(Ok(stream)) => Ok((addr, stream)),
        Ok(Err(e)) => Err((addr, e)),
        Err(_) => Err((
            addr,
            io::Error::new(
                io::ErrorKind::TimedOut,
                format!("connect to {addr} timed out"),
            ),
        )),
    }
}

// --- IPv6 fail-fast breaker (0.4.5) ---

/// Process-wide IPv6 fail-fast breaker: while it is down, IPv6
/// addresses are skipped (or refused outright for IPv6-only targets)
/// instead of burning the 5 s attempt cap each time. Tripped by a
/// failed or timed-out IPv6 attempt, cleared by a successful one or
/// by a network change; a trip lasts 60 s.
static V6_DOWN_UNTIL_MS: AtomicU64 = AtomicU64::new(0);
const V6_DOWN_FOR_MS: u64 = 60_000;

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// True when the breaker is down at `now` (the clock is a parameter
/// so tests can move time without sleeping).
fn v6_is_down_at(down_until_ms: u64, now: u64) -> bool {
    now < down_until_ms
}

fn mark_v6_down_at(now: u64) {
    V6_DOWN_UNTIL_MS.store(now + V6_DOWN_FOR_MS, Ordering::Relaxed);
}

fn mark_v6_down() {
    mark_v6_down_at(now_ms());
}

fn mark_v6_up() {
    V6_DOWN_UNTIL_MS.store(0, Ordering::Relaxed);
}

/// While the breaker is down at `now` (deadline passed in, so tests
/// stay pure), drop IPv6 addresses when IPv4 ones exist; when every
/// address is IPv6 there is nothing to try.
fn v6_filter(
    addrs: Vec<SocketAddr>,
    down_until_ms: u64,
    now: u64,
) -> Result<Vec<SocketAddr>, ()> {
    if !v6_is_down_at(down_until_ms, now) {
        return Ok(addrs);
    }
    if addrs.iter().any(|a| a.is_ipv4()) {
        Ok(addrs.into_iter().filter(|a| a.is_ipv4()).collect())
    } else {
        Err(())
    }
}

// --- DNS cache (0.4.5) ---

/// DNS cache: resolved addresses per (network handle, lowercase host,
/// port). 60 s positive TTL, 5 s negative TTL, max 512 entries; when
/// full, expired entries are dropped first, then the whole map is
/// cleared. IP literals are never cached (they need no lookup). The
/// port is part of the key because the cached SocketAddrs carry the
/// port they were resolved with.
const DNS_POSITIVE_TTL: Duration = Duration::from_secs(60);
const DNS_NEGATIVE_TTL: Duration = Duration::from_secs(5);
const DNS_MAX_ENTRIES: usize = 512;

type DnsKey = (u64, String, u16);
type DnsValue = (Instant, Result<Vec<SocketAddr>, ()>);

struct DnsCache {
    map: Mutex<HashMap<DnsKey, DnsValue>>,
}

impl DnsCache {
    fn new() -> Self {
        Self {
            map: Mutex::new(HashMap::new()),
        }
    }

    fn ttl(result: &Result<Vec<SocketAddr>, ()>) -> Duration {
        if result.is_ok() {
            DNS_POSITIVE_TTL
        } else {
            DNS_NEGATIVE_TTL
        }
    }

    fn age(stored: Instant, now: Instant) -> Duration {
        now.checked_duration_since(stored)
            .unwrap_or(Duration::ZERO)
    }

    fn get(&self, key: &DnsKey, now: Instant) -> Option<Result<Vec<SocketAddr>, ()>> {
        let mut map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        let entry = map.get(key)?.clone();
        if Self::age(entry.0, now) >= Self::ttl(&entry.1) {
            map.remove(key);
            None
        } else {
            Some(entry.1)
        }
    }

    fn put(&self, key: DnsKey, value: Result<Vec<SocketAddr>, ()>, now: Instant) {
        let mut map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        if map.len() >= DNS_MAX_ENTRIES {
            map.retain(|_, (stored, result)| Self::age(*stored, now) < Self::ttl(result));
            if map.len() >= DNS_MAX_ENTRIES {
                map.clear();
            }
        }
        map.insert(key, (now, value));
    }

    fn clear(&self) {
        self.map.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.map.lock().unwrap_or_else(|e| e.into_inner()).len()
    }
}

static DNS_CACHE: LazyLock<DnsCache> = LazyLock::new(DnsCache::new);

// --- DNS on the outbound network (Android only) ---

/// What the network resolver decided.
pub(crate) enum NetworkDns {
    /// Use the default resolver (no network, device API < 29, or the
    /// android_getaddrinfofornetwork symbol is missing).
    Fallback,
    /// The network resolver ran; this result is authoritative.
    /// (Constructed only on Android; host builds always fall back.)
    #[allow(dead_code)]
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
        return NetworkDns::Done(Err(io::Error::other(format!(
            "android_getaddrinfofornetwork failed ({rc})"
        ))));
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

    #[test]
    fn v6_breaker_skips_v6_when_v4_exists() {
        let addrs: Vec<SocketAddr> = vec![
            "192.0.2.1:80".parse().expect("v4"),
            "[2001:db8::1]:80".parse().expect("v6"),
            "198.51.100.1:80".parse().expect("v4"),
        ];
        // Breaker up (deadline 0): everything is tried.
        assert_eq!(v6_filter(addrs.clone(), 0, 1_000).expect("up"), addrs);
        // Breaker down until 61_000: only the IPv4 addresses survive.
        let kept = v6_filter(addrs.clone(), 61_000, 1_000).expect("v4s kept");
        assert!(kept.iter().all(|a| a.is_ipv4()));
        assert_eq!(kept.len(), 2);
        // Past the deadline the trip has expired.
        assert_eq!(
            v6_filter(addrs.clone(), 61_000, 61_001).expect("expired"),
            addrs
        );
    }

    #[test]
    fn v6_breaker_refuses_v6_only_targets() {
        let addrs: Vec<SocketAddr> = vec!["[2001:db8::1]:80".parse().expect("v6")];
        assert!(v6_filter(addrs.clone(), 61_000, 1_000).is_err());
        assert!(v6_filter(addrs, 61_000, 61_001).is_ok());
    }

    #[test]
    fn v6_breaker_deadline_is_checked_against_the_clock() {
        assert!(!v6_is_down_at(0, 1_000));
        assert!(v6_is_down_at(61_000, 1_000));
        assert!(!v6_is_down_at(61_000, 61_000));
    }

    fn cache_key() -> DnsKey {
        (0, "example.com".to_string(), 80)
    }

    fn cached_addr() -> SocketAddr {
        "93.184.216.34:80".parse().expect("addr")
    }

    #[test]
    fn dns_cache_returns_a_hit_and_misses_unknown_hosts() {
        let cache = DnsCache::new();
        let now = Instant::now();
        assert!(cache.get(&cache_key(), now).is_none());
        cache.put(cache_key(), Ok(vec![cached_addr()]), now);
        assert_eq!(
            cache.get(&cache_key(), now).expect("hit").expect("positive"),
            vec![cached_addr()]
        );
        assert!(cache.get(&(0, "other.com".to_string(), 80), now).is_none());
    }

    #[test]
    fn dns_cache_positive_entry_expires_after_sixty_seconds() {
        let cache = DnsCache::new();
        let now = Instant::now();
        cache.put(cache_key(), Ok(vec![cached_addr()]), now);
        assert!(cache
            .get(&cache_key(), now + Duration::from_secs(59))
            .is_some());
        assert!(cache
            .get(&cache_key(), now + Duration::from_secs(61))
            .is_none());
    }

    #[test]
    fn dns_cache_negative_entry_expires_after_five_seconds() {
        let cache = DnsCache::new();
        let now = Instant::now();
        cache.put(cache_key(), Err(()), now);
        assert!(cache
            .get(&cache_key(), now + Duration::from_secs(4))
            .is_some());
        assert!(cache
            .get(&cache_key(), now + Duration::from_secs(6))
            .is_none());
    }

    #[test]
    fn dns_cache_flush_clears_it() {
        let cache = DnsCache::new();
        let now = Instant::now();
        cache.put(cache_key(), Ok(vec![cached_addr()]), now);
        cache.clear();
        assert!(cache.get(&cache_key(), now).is_none());
        assert_eq!(cache.len(), 0);
    }

    #[test]
    fn dns_cache_cap_drops_expired_entries_first() {
        let cache = DnsCache::new();
        let now = Instant::now();
        // One entry that is already expired at `now`, the rest fresh.
        cache.put(
            (0, "old.com".to_string(), 80),
            Ok(vec![cached_addr()]),
            now - Duration::from_secs(61),
        );
        for i in 0..(DNS_MAX_ENTRIES - 1) {
            cache.put(
                (0, format!("host{i}.com"), 80),
                Ok(vec![cached_addr()]),
                now,
            );
        }
        assert_eq!(cache.len(), DNS_MAX_ENTRIES);
        cache.put((0, "new.com".to_string(), 80), Ok(vec![cached_addr()]), now);
        // The expired entry was dropped, so the map stays at the cap
        // and a fresh entry survives.
        assert_eq!(cache.len(), DNS_MAX_ENTRIES);
        assert!(cache.get(&(0, "old.com".to_string(), 80), now).is_none());
        assert!(cache.get(&(0, "new.com".to_string(), 80), now).is_some());
        assert!(cache
            .get(&(0, "host0.com".to_string(), 80), now)
            .is_some());
    }

    #[test]
    fn dns_cache_cap_clears_when_nothing_is_expired() {
        let cache = DnsCache::new();
        let now = Instant::now();
        for i in 0..DNS_MAX_ENTRIES {
            cache.put(
                (0, format!("host{i}.com"), 80),
                Ok(vec![cached_addr()]),
                now,
            );
        }
        assert_eq!(cache.len(), DNS_MAX_ENTRIES);
        cache.put((0, "new.com".to_string(), 80), Ok(vec![cached_addr()]), now);
        // Nothing was expired, so the whole map was cleared first.
        assert_eq!(cache.len(), 1);
        assert!(cache.get(&(0, "new.com".to_string(), 80), now).is_some());
        assert!(cache
            .get(&(0, "host0.com".to_string(), 80), now)
            .is_none());
    }

    /// The IPv6 breaker is process-wide, so this one test runs the
    /// down and up scenarios in sequence: no other test can clear the
    /// breaker mid-check (Connector::set_network resets it).
    #[tokio::test]
    async fn v6_breaker_refuses_v6_only_target_and_stagger_reaches_ipv6() {
        // No IPv6 loopback on this machine: nothing to test.
        let Ok(probe) = std::net::TcpListener::bind("[::1]:0") else {
            return;
        };
        let free_port = probe.local_addr().expect("probe addr").port();
        drop(probe);
        // While the breaker is down, an IPv6-only target is refused at
        // once instead of waiting out the 5 s cap. Re-arm up to 3
        // times: another test calling set_network in this window would
        // clear the breaker and turn the instant refusal into a plain
        // refused connect.
        let mut last_err = String::new();
        for _ in 0..3 {
            mark_v6_down_at(now_ms());
            match Connector::default()
                .connect("::1", free_port, Duration::from_secs(2))
                .await
            {
                Err(ConnectError::Unreachable(_)) => {
                    last_err.clear();
                    break;
                }
                Err(e) => last_err = format!("{e}"),
                Ok(_) => last_err = "connected".to_string(),
            }
        }
        mark_v6_up();
        assert!(
            last_err.is_empty(),
            "an IPv6-only target must be refused at once while the breaker is down, got: {last_err}"
        );
        // Stagger: a listener on IPv6 loopback only. "localhost"
        // resolves to 127.0.0.1 (refused — nothing listens there) and
        // ::1 (live); IPv4 sorts first, so the first attempt fails and
        // the second starts after the 300 ms stagger.
        let listener = TcpListener::bind("[::1]:0").await.expect("bind ::1");
        let port = listener.local_addr().expect("addr").port();
        mark_v6_up();
        let start = std::time::Instant::now();
        let stream = Connector::default()
            .connect("localhost", port, Duration::from_secs(5))
            .await
            .expect("staggered connect reaches the live address");
        assert_eq!(stream.peer_addr().expect("peer").ip().to_string(), "::1");
        assert!(
            start.elapsed() >= Duration::from_millis(200),
            "the second attempt waits for the stagger interval"
        );
        assert!(
            start.elapsed() < Duration::from_secs(2),
            "the stagger must not stall the connect"
        );
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
