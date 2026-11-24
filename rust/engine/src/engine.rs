//! The proxy engine: a tokio runtime, listening sockets, accept loops,
//! a connection registry and an event sink.
//!
//! Each engine lives in the global handle table (see `create_engine` /
//! `destroy_engine`) and is owned by Kotlin through a handle (i64).
//! Kotlin talks to it only through the JNI functions in lib.rs; this file
//! has no JNI in it.
//!
//! Each protocol has a handler (see `socks5`, `socks4`, `http`).
//! Events go out through the `EventSink` the JNI listener registers;
//! the sink is never called while the engine lock is held.

use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use socket2::{Domain, Protocol as SockProtocol, Socket, Type};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tokio::sync::watch;
use tokio::task::{AbortHandle, JoinHandle};

use crate::config::Config;
use crate::connector::Connector;
use crate::model::{snapshot_text, ConnInfo, EngineState, Event, EventSink, Protocol};
use crate::relay::RateLimiter;
use crate::sniffer;

/// The global tokio runtime: 2 worker threads named "ps-worker".
/// Built once, on the first engine start.
static RUNTIME: std::sync::OnceLock<Result<Runtime, String>> = std::sync::OnceLock::new();

fn runtime() -> Result<&'static Runtime, String> {
    match RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("ps-worker")
            .enable_all()
            .build()
            .map_err(|e| format!("failed to start the engine runtime: {e}"))
    }) {
        Ok(rt) => Ok(rt),
        Err(e) => Err(e.clone()),
    }
}

/// One connection the engine knows about. Counters are atomic because a
/// connection task updates them while the snapshot reads them. The
/// target is behind one mutex so a handler can set it (once the request
/// is parsed) while the snapshot reads it.
pub struct ConnRecord {
    pub id: u64,
    pub client_ip: IpAddr,
    /// Target host and port. Empty / 0 until a handler parses the request.
    pub target: Mutex<(String, u16)>,
    pub protocol: Protocol,
    /// When the connection opened, unix time in milliseconds.
    pub started_ms: u64,
    /// Bytes read from the client so far.
    pub up: AtomicU64,
    /// Bytes written to the client so far.
    pub down: AtomicU64,
    /// True when the connection was refused for a blocked domain.
    pub blocked: AtomicBool,
    /// Engine-wide live totals (shared with every connection): every
    /// byte counted here is also counted there, so the snapshot's
    /// liveUp/liveDown are monotonic and include closed connections.
    pub live_up: std::sync::Arc<AtomicU64>,
    pub live_down: std::sync::Arc<AtomicU64>,
}

impl ConnRecord {
    /// Fill in the target once a handler has parsed the request.
    pub fn set_target(&self, host: String, port: u16) {
        *self.target.lock().unwrap_or_else(|e| e.into_inner()) = (host, port);
    }

    /// Mark this connection as refused for a blocked domain.
    pub fn mark_blocked(&self) {
        self.blocked.store(true, Ordering::Relaxed);
    }

    /// Count bytes read from the client, into the record and the
    /// engine-wide live total. Use for bytes that bypass the relay
    /// pumps (UDP datagrams, an HTTP body prefix already written).
    pub fn count_up(&self, n: u64) {
        self.up.fetch_add(n, Ordering::Relaxed);
        self.live_up.fetch_add(n, Ordering::Relaxed);
    }

    /// Count bytes written to the client (see count_up).
    pub fn count_down(&self, n: u64) {
        self.down.fetch_add(n, Ordering::Relaxed);
        self.live_down.fetch_add(n, Ordering::Relaxed);
    }

    fn to_conn_info(&self) -> ConnInfo {
        let (host, port) = self.target.lock().unwrap_or_else(|e| e.into_inner()).clone();
        ConnInfo {
            id: self.id,
            client_ip: self.client_ip,
            host,
            port,
            protocol: self.protocol,
            started_ms: self.started_ms,
            up: self.up.load(Ordering::Relaxed),
            down: self.down.load(Ordering::Relaxed),
            blocked: self.blocked.load(Ordering::Relaxed),
        }
    }
}

struct EngineInner {
    config: Option<Config>,
    sink: Option<std::sync::Arc<dyn EventSink>>,
    state: EngineState,
    accept_tasks: Vec<JoinHandle<()>>,
    shutdown_tx: Option<watch::Sender<bool>>,
    connections: HashMap<u64, std::sync::Arc<ConnRecord>>,
    /// Abort handles of connection tasks, with the client IP: closing a
    /// client aborts its tasks, including ones still sniffing (no
    /// record yet). Keyed by the pre-assigned connection id. The
    /// handle is None between the slot reservation and the spawn
    /// returning it (see try_spawn_connection).
    handles: HashMap<u64, (Option<AbortHandle>, IpAddr)>,
    /// Clients refused until the proxy restarts. Runtime-only, never
    /// persisted; cleared by stop().
    denied: HashSet<IpAddr>,
    /// The connection limit in force: min(config.max_connections,
    /// (nofile_soft - 128) / 2), computed at start().
    effective_max: usize,
    /// Distinct client IPs with their open-connection counts: one
    /// device per IP, however many connections it holds.
    devices: HashMap<IpAddr, usize>,
    /// Whether the PAC server is up: true when it is off (pac_port=0)
    /// or bound, false when the bind failed. Read by snapshot().
    pac_ok: bool,
    /// Last 120 seconds of speed, sampled while busy.
    history: crate::history::History,
    /// Last 500 closed connections, with monotonically increasing ids.
    session_log: crate::sessionlog::SessionLog,
    /// The sampler task, while it runs (started on the idle->busy
    /// edge, ends itself after 2 empty ticks).
    sampler_task: Option<JoinHandle<()>>,
    /// The idle-stop countdown, while it runs (started on the
    /// busy->idle edge, cancelled on Busy or stop()).
    idle_stop_task: Option<JoinHandle<()>>,
    /// Shared speed limiters, one per direction (None = unlimited).
    up_limiter: Option<std::sync::Arc<RateLimiter>>,
    down_limiter: Option<std::sync::Arc<RateLimiter>>,
    /// Whether a VPN network is active right now (pushed from Kotlin
    /// via set_vpn_active). False by default: fail-closed when the
    /// config sets require_vpn.
    vpn_active: bool,
    next_conn_id: u64,
    total_up: u64,
    total_down: u64,
}

/// The engine. The inner state is behind one mutex, held only for short
/// synchronous sections — never across an await, and never while an event
/// is emitted (the sink may call back into the engine).
pub struct Engine {
    inner: Mutex<EngineInner>,
    /// Connection tasks currently running (accepted, sniffing or closing).
    /// `max_connections` is enforced on this count, so in-flight sniffs
    /// count too, not only registered connections.
    active: AtomicUsize,
    /// Connections refused by reason: the connection limit, a denied
    /// client, a client outside allowed_clients. Exposed in the
    /// snapshot; each also logs at most once per 5 s (see
    /// note_rejection).
    rejected_limit: RejectStats,
    rejected_denied: RejectStats,
    rejected_not_allowed: RejectStats,
    /// Engine-wide live byte totals: every pump chunk lands here as
    /// well as in its connection record, so the snapshot's liveUp/
    /// liveDown are monotonic and include closed connections. Never
    /// reset: Kotlin baselines them per session, like the totals.
    live_up: std::sync::Arc<AtomicU64>,
    live_down: std::sync::Arc<AtomicU64>,
    /// The outbound used to dial targets. It holds the Android Network
    /// handle for outbound sockets (0 = default network).
    outbound: std::sync::Arc<Connector>,
    /// Failed-auth throttle: 5 failures in 60 s block an IP for 5 min.
    auth_guard: crate::auth::AuthGuard,
}

fn lock_inner(inner: &Mutex<EngineInner>) -> MutexGuard<'_, EngineInner> {
    inner.lock().unwrap_or_else(|e| e.into_inner())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Wall-clock seconds since the epoch, for history ring timestamps.
fn now_unix_sec() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Raise the soft RLIMIT_NOFILE to min(hard, 65536) so the engine can
/// hold many connections, and return the resulting soft limit. None
/// when the limit cannot be read (then the connection limit is not
/// capped by fds). Unix only.
///
/// The `as u64` casts are no-ops on 64-bit targets (host clippy
/// flags them) but required on 32-bit Android (armeabi-v7a), where
/// rlim_t is u32 — hence the allow, not a removal.
#[cfg(unix)]
#[allow(clippy::unnecessary_cast)]
pub fn raise_nofile_limit() -> Option<u64> {
    unsafe {
        let mut rl = libc::rlimit { rlim_cur: 0, rlim_max: 0 };
        if libc::getrlimit(libc::RLIMIT_NOFILE, &mut rl) != 0 {
            return None;
        }
        let target = rl.rlim_max.min(65_536 as libc::rlim_t);
        if rl.rlim_cur < target {
            let new = libc::rlimit { rlim_cur: target, rlim_max: rl.rlim_max };
            if libc::setrlimit(libc::RLIMIT_NOFILE, &new) == 0 {
                return Some(target as u64);
            }
        }
        Some(rl.rlim_cur as u64)
    }
}

/// No fd budget to raise off Unix: the connection limit is not capped.
#[cfg(not(unix))]
pub fn raise_nofile_limit() -> Option<u64> {
    None
}

/// Rejection counters for one reason: a running total (exposed in the
/// snapshot) plus a 5 s window count for the throttled WARN log.
struct RejectStats {
    total: AtomicU64,
    window: AtomicU64,
    window_start_ms: AtomicU64,
}

impl RejectStats {
    fn new() -> Self {
        Self {
            total: AtomicU64::new(0),
            window: AtomicU64::new(0),
            window_start_ms: AtomicU64::new(0),
        }
    }
}

/// Why a connection was refused in try_spawn_connection.
#[derive(Clone, Copy)]
enum RejectReason {
    Limit,
    Denied,
    NotAllowed,
}

/// True when the address is private, loopback or link-local.
/// (IpAddr itself has no is_private: the check is per family.)
fn is_private_or_local(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_private() || v4.is_loopback() || v4.is_link_local(),
        IpAddr::V6(v6) => v6.is_unique_local() || v6.is_loopback() || v6.is_unicast_link_local(),
    }
}

/// A client may connect when it is in `allowed_clients` (or when that
/// list is empty) and, when `private_only` holds, is private or local.
/// Shared by the proxy accept path and the PAC server (pac.rs).
pub(crate) fn client_allowed(config: &Config, ip: &IpAddr) -> bool {
    if !config.allowed_clients.is_empty()
        && !config.allowed_clients.iter().any(|c| c.contains(ip))
    {
        return false;
    }
    if config.private_only && !is_private_or_local(ip) {
        return false;
    }
    true
}

impl Default for Engine {
    fn default() -> Self {
        Self::new()
    }
}

impl Engine {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(EngineInner {
                config: None,
                sink: None,
                state: EngineState::Stopped,
                accept_tasks: Vec::new(),
                shutdown_tx: None,
                connections: HashMap::new(),
                handles: HashMap::new(),
                denied: HashSet::new(),
                effective_max: 256,
                devices: HashMap::new(),
                pac_ok: true,
                history: crate::history::History::new(),
                session_log: crate::sessionlog::SessionLog::new(),
                sampler_task: None,
                idle_stop_task: None,
                up_limiter: None,
                down_limiter: None,
                vpn_active: false,
                next_conn_id: 1,
                total_up: 0,
                total_down: 0,
            }),
            active: AtomicUsize::new(0),
            rejected_limit: RejectStats::new(),
            rejected_denied: RejectStats::new(),
            rejected_not_allowed: RejectStats::new(),
            live_up: std::sync::Arc::new(AtomicU64::new(0)),
            live_down: std::sync::Arc::new(AtomicU64::new(0)),
            outbound: std::sync::Arc::new(Connector::default()),
            auth_guard: crate::auth::AuthGuard::default(),
        }
    }

    /// Current state, for tests and the snapshot.
    pub fn state(&self) -> EngineState {
        lock_inner(&self.inner).state.clone()
    }

    /// Register the event sink (the JNI listener, or a test sink).
    pub fn set_sink(&self, sink: std::sync::Arc<dyn EventSink>) {
        lock_inner(&self.inner).sink = Some(sink);
    }

    /// Set the Android Network handle (net_handle_t) for outbound TCP
    /// connections. 0 means the default network. Applies to connections
    /// opened after the call.
    pub fn set_outbound_network(&self, handle: u64) {
        self.outbound.set_network(handle);
    }

    /// Tell the engine whether a VPN network is active right now.
    /// Only consulted when the config sets require_vpn.
    pub fn set_vpn_active(&self, active: bool) {
        lock_inner(&self.inner).vpn_active = active;
    }

    /// True when new connections must be refused: require_vpn is on
    /// and no VPN is active. Existing connections are unaffected.
    pub fn vpn_down_refused(&self) -> bool {
        let inner = lock_inner(&self.inner);
        matches!(inner.config.as_ref(), Some(c) if c.require_vpn) && !inner.vpn_active
    }

    /// Send an event to the sink, if one is registered. Never called with
    /// the engine lock held.
    fn emit(&self, event: Event) {
        let sink = lock_inner(&self.inner).sink.clone();
        if let Some(sink) = sink {
            sink.on_event(event);
        }
    }

    /// Move to the error state and report it. Used for start failures
    /// (bad config, bad bind, runtime failure).
    fn transition_to_error(&self, message: String) {
        {
            let mut inner = lock_inner(&self.inner);
            inner.state = EngineState::Error(message.clone());
        }
        self.emit(Event::StateChanged(EngineState::Error(message)));
    }

    /// Bind the listening socket with SO_REUSEADDR and a backlog of 128.
    /// Returns the plain std listener: turning it into a tokio listener
    /// (from_std) needs a runtime context, so that happens inside the
    /// accept task, not here on the caller's thread.
    fn bind_listener(bind_addr: IpAddr, port: u16) -> Result<std::net::TcpListener, String> {
        let addr = SocketAddr::new(bind_addr, port);
        let domain = if bind_addr.is_ipv4() {
            Domain::IPV4
        } else {
            Domain::IPV6
        };
        let socket = Socket::new(domain, Type::STREAM, Some(SockProtocol::TCP))
            .map_err(|e| format!("failed to create socket for {addr}: {e}"))?;
        socket
            .set_reuse_address(true)
            .map_err(|e| format!("failed to set SO_REUSEADDR on {addr}: {e}"))?;
        socket
            .bind(&socket2::SockAddr::from(addr))
            .map_err(|e| format!("failed to bind {addr}: {e}"))?;
        socket
            .listen(128)
            .map_err(|e| format!("failed to listen on {addr}: {e}"))?;
        socket
            .set_nonblocking(true)
            .map_err(|e| format!("failed to set nonblocking on {addr}: {e}"))?;
        let std_listener: std::net::TcpListener = socket.into();
        Ok(std_listener)
    }

    /// Start listening. Idempotent: starting an already starting or
    /// running engine is a no-op. A start from the error or stopped state
    /// retries the bind. On failure the state becomes Error and the
    /// error text is returned.
    pub fn start(self: &std::sync::Arc<Self>, config_text: &str) -> Result<(), String> {
        let config = match Config::parse(config_text) {
            Ok(config) => config,
            Err(e) => {
                crate::diag::log_error(format!("start failed: {e}"));
                self.transition_to_error(e.clone());
                return Err(e);
            }
        };
        // Raise the fd limit first: the effective connection limit
        // depends on the soft nofile budget.
        let nofile = raise_nofile_limit();
        let effective_max = match nofile {
            Some(soft) => crate::config::effective_max_connections(config.max_connections, soft),
            None => config.max_connections,
        };
        // A fresh start re-resolves everything: drop cached DNS from
        // the previous run (0.4.5), so stale entries can't point at
        // the old network.
        self.outbound.flush_dns_cache();
        {
            let mut inner = lock_inner(&self.inner);
            match inner.state {
                EngineState::Starting | EngineState::Running => return Ok(()),
                _ => inner.state = EngineState::Starting,
            }
            inner.effective_max = effective_max as usize;
            // A fresh start gets a fresh history: the old samples
            // belong to the previous session. Any sampler or idle-stop
            // countdown left over from it is aborted (its ticks would
            // push into the new session's ring); the next Busy edge
            // starts a new sampler. The PAC flag resets too: a failed
            // bind from the previous run must not stick.
            inner.pac_ok = true;
            inner.history.clear();
            if let Some(task) = inner.sampler_task.take() {
                task.abort();
            }
            if let Some(task) = inner.idle_stop_task.take() {
                task.abort();
            }
        }
        self.emit(Event::StateChanged(EngineState::Starting));

        let specs = match listener_specs(&config) {
            Ok(specs) => specs,
            Err(e) => {
                crate::diag::log_error(format!("start failed: {e}"));
                self.transition_to_error(e.clone());
                return Err(e);
            }
        };
        let rt = match runtime() {
            Ok(rt) => rt,
            Err(e) => {
                crate::diag::log_error(format!("start failed: {e}"));
                self.transition_to_error(e.clone());
                return Err(e);
            }
        };
        // Bind every listener before going Running. A failure releases
        // the ones already bound (the Vec drops) and names the port:
        // bind_listener's error carries the full address.
        let mut std_listeners = Vec::new();
        for spec in &specs {
            match Self::bind_listener(config.bind_addr, spec.port) {
                Ok(listener) => std_listeners.push(listener),
                Err(e) => {
                    crate::diag::log_error(format!("bind failed: {e}"));
                    self.transition_to_error(e.clone());
                    return Err(e);
                }
            }
        }

        let mut inner = lock_inner(&self.inner);
        if inner.state != EngineState::Starting {
            // A stop() ran while we were binding: drop the listeners and
            // stay stopped instead of starting behind the caller's back.
            return Ok(());
        }
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let mut accept_tasks = Vec::new();
        for (std_listener, spec) in std_listeners.into_iter().zip(specs) {
            let engine = std::sync::Arc::clone(self);
            let task_config = config.clone();
            let shutdown = shutdown_rx.clone();
            let allowed = std::sync::Arc::new(spec.allowed);
            // The std listener becomes a tokio listener inside this task,
            // where a runtime context exists. If that fails, the engine
            // reports an error instead of listening.
            accept_tasks.push(rt.spawn(async move {
                match TcpListener::from_std(std_listener) {
                    Ok(listener) => {
                        accept_loop(engine, std::sync::Arc::new(listener), task_config, allowed, shutdown)
                            .await;
                    }
                    Err(e) => {
                        crate::diag::log_error(format!("failed to wrap listener: {e}"));
                        engine.transition_to_error(format!("failed to wrap listener: {e}"));
                    }
                }
            }));
        }
        // The PAC server is best-effort: a bind failure must not stop
        // the proxy. It is reported in the snapshot (pac_ok=false) and
        // the diagnostics log, and the proxy keeps running.
        if config.pac_port != 0 {
            match Self::bind_listener(config.bind_addr, config.pac_port) {
                Ok(std_pac_listener) => {
                    let (http, socks) = crate::pac::pac_ports(&config);
                    let pac_config = config.clone();
                    let shutdown = shutdown_rx.clone();
                    accept_tasks.push(rt.spawn(async move {
                        match TcpListener::from_std(std_pac_listener) {
                            Ok(listener) => {
                                crate::pac::pac_accept_loop(
                                    listener, pac_config, http, socks, shutdown,
                                )
                                .await;
                            }
                            Err(e) => {
                                crate::diag::log_error(format!(
                                    "failed to wrap pac listener: {e}"
                                ));
                            }
                        }
                    }));
                }
                Err(e) => {
                    inner.pac_ok = false;
                    crate::diag::log_warn(format!("pac bind failed: {e}"));
                }
            }
        }
        inner.accept_tasks = accept_tasks;
        inner.shutdown_tx = Some(shutdown_tx);
        let summary = config.summary();
        // One shared bucket per direction (None = unlimited). Built
        // from the config before it moves into the inner state.
        let up_limiter = if config.limit_up_kbps > 0 {
            Some(std::sync::Arc::new(RateLimiter::new(
                config.limit_up_kbps.saturating_mul(1024),
            )))
        } else {
            None
        };
        let down_limiter = if config.limit_down_kbps > 0 {
            Some(std::sync::Arc::new(RateLimiter::new(
                config.limit_down_kbps.saturating_mul(1024),
            )))
        } else {
            None
        };
        inner.up_limiter = up_limiter;
        inner.down_limiter = down_limiter;
        inner.config = Some(config);
        inner.state = EngineState::Running;
        drop(inner);
        crate::diag::log_info(format!("started: {summary}"));
        if let Some(soft) = nofile {
            crate::diag::log_info(format!(
                "fd limit: nofile soft {soft}, effective max connections {effective_max}"
            ));
        }
        self.emit(Event::StateChanged(EngineState::Running));
        Ok(())
    }

    /// Stop listening, cancel the accept tasks and close the sockets.
    /// Idempotent: stopping an already stopped engine is a no-op. A
    /// connection task stuck waiting for a first byte is woken by the
    /// shutdown signal. Every accept task is waited for (at most 2 s
    /// each) so its listener socket is closed before this returns:
    /// aborting alone leaves the socket bound until the task is dropped,
    /// and a restart on the same port would fail with
    /// "Address already in use".
    pub fn stop(&self) {
        let (accept_tasks, was_running);
        {
            let mut inner = lock_inner(&self.inner);
            was_running = inner.state != EngineState::Stopped;
            if let Some(tx) = inner.shutdown_tx.take() {
                let _ = tx.send(true);
            }
            accept_tasks = std::mem::take(&mut inner.accept_tasks);
            for task in &accept_tasks {
                task.abort();
            }
            // Drain first, then add the counters: iterating the drain
            // borrows the map, and the totals live in the same struct.
            let drained: Vec<_> = inner.connections.drain().map(|(_, r)| r).collect();
            for record in drained {
                inner.total_up += record.up.load(Ordering::Relaxed);
                inner.total_down += record.down.load(Ordering::Relaxed);
            }
            // A block lasts until the proxy restarts: a stop clears it.
            inner.denied.clear();
            inner.devices.clear();
            if let Some(task) = inner.sampler_task.take() {
                task.abort();
            }
            if let Some(task) = inner.idle_stop_task.take() {
                task.abort();
            }
            inner.state = EngineState::Stopped;
        }
        for task in accept_tasks {
            wait_for_task(task);
        }
        if was_running {
            crate::diag::log_info("stopped".to_string());
            self.emit(Event::StateChanged(EngineState::Stopped));
        }
    }

    /// The snapshot text (see docs/ENGINE_API.md).
    pub fn snapshot(&self) -> String {
        let (state, total_up, total_down, rejected, live, devices, speed, pac_ok, mut conns);
        {
            let inner = lock_inner(&self.inner);
            state = inner.state.clone();
            total_up = inner.total_up;
            total_down = inner.total_down;
            rejected = (
                self.rejected_limit.total.load(Ordering::Relaxed),
                self.rejected_denied.total.load(Ordering::Relaxed),
                self.rejected_not_allowed.total.load(Ordering::Relaxed),
            );
            live = (
                self.live_up.load(Ordering::Relaxed),
                self.live_down.load(Ordering::Relaxed),
            );
            devices = inner.devices.len();
            pac_ok = inner.pac_ok;
            let latest = inner.history.latest();
            speed = (latest.up_bps, latest.down_bps);
            conns = inner
                .connections
                .values()
                .map(|r| r.to_conn_info())
                .collect::<Vec<_>>();
        }
        conns.sort_by_key(|c| c.id);
        snapshot_text(
            &state,
            (total_up, total_down),
            &conns,
            rejected,
            live,
            devices,
            speed,
            pac_ok,
        )
    }

    /// The last 120 seconds of speed as "up,down;up,down;…" oldest
    /// first (see docs/ENGINE_API.md). All zeros when the engine
    /// never ran.
    pub fn history(&self) -> String {
        lock_inner(&self.inner).history.render(now_unix_sec())
    }

    /// Session-log records newer than `since_id`, one
    /// "log_id\t<snapshot line>" per line, oldest first.
    pub fn session_log_since(&self, since_id: u64) -> String {
        lock_inner(&self.inner).session_log.since(since_id)
    }

    /// Drop every session-log record. Ids keep increasing.
    pub fn clear_session_log(&self) {
        lock_inner(&self.inner).session_log.clear();
    }

    /// The connection limit in force (see start()).
    fn effective_max(&self) -> usize {
        lock_inner(&self.inner).effective_max
    }

    /// Count a refused connection and log at most once per 5 s per
    /// reason: "connection limit reached: N rejected in the last 5 s
    /// (max M)". The first rejection always logs (the window starts
    /// at 0). Never drops a rejection silently, never spams the log.
    fn note_rejection(&self, reason: RejectReason) {
        let stats = match reason {
            RejectReason::Limit => &self.rejected_limit,
            RejectReason::Denied => &self.rejected_denied,
            RejectReason::NotAllowed => &self.rejected_not_allowed,
        };
        stats.total.fetch_add(1, Ordering::Relaxed);
        let n = stats.window.fetch_add(1, Ordering::Relaxed) + 1;
        let now = now_ms();
        let start = stats.window_start_ms.load(Ordering::Relaxed);
        if now.saturating_sub(start) >= 5_000
            && stats
                .window_start_ms
                .compare_exchange(start, now, Ordering::Relaxed, Ordering::Relaxed)
                .is_ok()
        {
            stats.window.store(0, Ordering::Relaxed);
            match reason {
                RejectReason::Limit => crate::diag::log_warn(format!(
                    "connection limit reached: {n} rejected in the last 5 s (max {})",
                    self.effective_max()
                )),
                RejectReason::Denied => crate::diag::log_warn(format!(
                    "denied client connections rejected: {n} in the last 5 s"
                )),
                RejectReason::NotAllowed => crate::diag::log_warn(format!(
                    "connections from disallowed clients rejected: {n} in the last 5 s"
                )),
            }
        }
    }

    /// Accept a connection if the client is allowed and the connection
    /// limit is not reached; otherwise drop it, count the refusal and
    /// log it (throttled to once per 5 s per reason). The limit counts
    /// every connection task currently running.
    fn try_spawn_connection(
        self: &std::sync::Arc<Self>,
        stream: TcpStream,
        peer: SocketAddr,
        config: &Config,
        allowed: std::sync::Arc<HashSet<Protocol>>,
        shutdown: watch::Receiver<bool>,
    ) {
        if self.is_denied(&peer.ip()) {
            self.note_rejection(RejectReason::Denied);
            return; // stream dropped: client blocked until restart, no event
        }
        if !client_allowed(config, &peer.ip()) {
            self.note_rejection(RejectReason::NotAllowed);
            return; // stream dropped: client not allowed, no event
        }
        let prev = self.active.fetch_add(1, Ordering::SeqCst);
        if prev >= self.effective_max() {
            self.active.fetch_sub(1, Ordering::SeqCst);
            self.note_rejection(RejectReason::Limit);
            return; // stream dropped: at the limit, no event
        }
        if prev == 0 {
            self.on_busy();
        }
        // The id is reserved here so the abort handle can be stored
        // against it before the task registers (or ever registers).
        let id = self.reserve_id();
        // Reserve the slot BEFORE spawning: a task that finishes fast
        // calls clear_handle before the insert otherwise, leaving a
        // stale entry forever.
        {
            let mut inner = lock_inner(&self.inner);
            inner.handles.insert(id, (None, peer.ip()));
        }
        let engine = std::sync::Arc::clone(self);
        let cleanup = std::sync::Arc::clone(self);
        let join = tokio::task::spawn(async move {
            handle_connection(engine, stream, peer, allowed, shutdown, id).await;
            cleanup.clear_handle(id);
        });
        {
            let mut inner = lock_inner(&self.inner);
            if let Some(slot) = inner.handles.get_mut(&id) {
                slot.0 = Some(join.abort_handle());
            }
            // else: the task already finished and cleared the slot.
        }
    }

    /// The idle->busy edge (active 0 -> 1): cancel a pending
    /// idle-stop countdown, start the speed sampler unless one is
    /// already running, and tell the UI the proxy is busy.
    fn on_busy(self: &std::sync::Arc<Self>) {
        // Cancel a pending idle-stop countdown: traffic is back.
        let idle_task = {
            let mut inner = lock_inner(&self.inner);
            inner.idle_stop_task.take()
        };
        if let Some(task) = idle_task {
            task.abort();
        }
        // Start the sampler unless one is already running. The check
        // and the store happen under one lock, so two concurrent Busy
        // edges cannot start two samplers.
        {
            let mut inner = lock_inner(&self.inner);
            let running = inner
                .sampler_task
                .as_ref()
                .is_some_and(|h| !h.is_finished());
            if !running {
                inner.sampler_task = Some(tokio::task::spawn(sampler_loop(
                    std::sync::Arc::clone(self),
                )));
            }
        }
        self.emit(Event::Busy);
    }

    /// The busy->idle edge (active 1 -> 0): tell the UI the proxy is
    /// idle and, when the config sets idle_stop_secs, start the
    /// countdown that ends with IdleStop. The countdown is cancelled
    /// by the next Busy edge or by stop(). No-op while stopped: a
    /// connection task ending during stop() must not arm a countdown.
    fn on_idle(self: &std::sync::Arc<Self>) {
        let secs = {
            let inner = lock_inner(&self.inner);
            if !matches!(inner.state, EngineState::Running) {
                return;
            }
            inner.config.as_ref().map_or(0, |c| c.idle_stop_secs)
        };
        self.emit(Event::Idle);
        if secs == 0 {
            return;
        }
        let old = {
            let mut inner = lock_inner(&self.inner);
            inner.idle_stop_task.take()
        };
        if let Some(old) = old {
            old.abort();
        }
        let engine = std::sync::Arc::clone(self);
        let task = tokio::task::spawn(async move {
            tokio::time::sleep(Duration::from_secs(secs)).await;
            engine.emit(Event::IdleStop);
            engine.clear_idle_stop_task();
        });
        lock_inner(&self.inner).idle_stop_task = Some(task);
    }

    /// Clear the stored sampler handle once the task ended (it ends
    /// itself after 2 empty ticks). Called from the sampler task
    /// itself: the slot still holds this task's handle, and clearing
    /// it cannot drop a newer one — on_busy only spawns a new
    /// sampler when the stored handle is finished or absent, and
    /// the check and the store happen under one lock. (The old
    /// is_finished() guard could never pass here: a task is not
    /// finished while it is still running its own end, so the slot
    /// was never cleared and the sampler looked alive forever.)
    fn clear_sampler_task(&self) {
        lock_inner(&self.inner).sampler_task = None;
    }

    /// Clear the stored idle-stop handle once it fired. Only clears
    /// a finished handle, so a newer countdown is never dropped.
    fn clear_idle_stop_task(&self) {
        let mut inner = lock_inner(&self.inner);
        if inner
            .idle_stop_task
            .as_ref()
            .is_some_and(|h| h.is_finished())
        {
            inner.idle_stop_task = None;
        }
    }

    /// Reserve the next connection id (also the abort-handle key).
    fn reserve_id(&self) -> u64 {
        let mut inner = lock_inner(&self.inner);
        let id = inner.next_conn_id;
        inner.next_conn_id += 1;
        id
    }

    fn clear_handle(&self, id: u64) {
        lock_inner(&self.inner).handles.remove(&id);
    }

    /// Abort every open connection from this client IP — including
    /// ones still sniffing (no record yet) — and report the registered
    /// ones closed. Unknown or malformed IPs are a no-op. The aborted
    /// tasks are dropped at their next await point, so their own
    /// unregister never runs: this removes the records, folds the
    /// counters into the totals and emits ConnectionClosed itself.
    /// Aborting first guarantees a task cannot emit its own close
    /// afterwards: a poll runs to its next await without yielding.
    pub fn close_client(&self, ip: &str) {
        let Ok(addr) = ip.parse::<IpAddr>() else { return };
        let mut ids = Vec::new();
        let mut to_abort = Vec::new();
        {
            let mut inner = lock_inner(&self.inner);
            let matching: Vec<u64> = inner
                .handles
                .iter()
                .filter(|(_, (_, peer_ip))| *peer_ip == addr)
                .map(|(id, _)| *id)
                .collect();
            for id in matching {
                if let Some((handle, _)) = inner.handles.remove(&id) {
                    ids.push(id);
                    // A None handle means the task spawned but its
                    // abort handle was not stored yet: nothing to abort.
                    if let Some(handle) = handle {
                        to_abort.push(handle);
                    }
                }
            }
        }
        for handle in to_abort {
            handle.abort();
        }
        let mut closed = Vec::new();
        let mut devices_changed = None;
        {
            let mut inner = lock_inner(&self.inner);
            for id in ids {
                if let Some(record) = inner.connections.remove(&id) {
                    inner.total_up += record.up.load(Ordering::Relaxed);
                    inner.total_down += record.down.load(Ordering::Relaxed);
                    let info = record.to_conn_info();
                    inner.session_log.push(info.clone());
                    if let Some(count) = inner.devices.get_mut(&record.client_ip) {
                        *count -= 1;
                        if *count == 0 {
                            inner.devices.remove(&record.client_ip);
                            devices_changed = Some(inner.devices.len());
                        }
                    }
                    closed.push(info);
                }
            }
        }
        for info in closed {
            self.emit(Event::ConnectionClosed(info));
        }
        if let Some(devices) = devices_changed {
            self.emit(Event::DevicesChanged(devices));
        }
    }

    /// Refuse this client until the proxy restarts (runtime-only, never
    /// persisted). Existing connections are NOT closed: call
    /// close_client for that, or deny first and close after.
    pub fn deny_client(&self, ip: &str) {
        if let Ok(addr) = ip.parse::<IpAddr>() {
            lock_inner(&self.inner).denied.insert(addr);
        }
    }

    /// Lift a block set by deny_client.
    pub fn allow_client(&self, ip: &str) {
        if let Ok(addr) = ip.parse::<IpAddr>() {
            lock_inner(&self.inner).denied.remove(&addr);
        }
    }

    /// True when this IP is blocked until restart.
    pub fn is_denied(&self, ip: &IpAddr) -> bool {
        lock_inner(&self.inner).denied.contains(ip)
    }

    fn register(&self, id: u64, client_ip: IpAddr, protocol: Protocol) -> std::sync::Arc<ConnRecord> {
        let mut inner = lock_inner(&self.inner);
        let record = std::sync::Arc::new(ConnRecord {
            id,
            client_ip,
            target: std::sync::Mutex::new((String::new(), 0)),
            protocol,
            started_ms: now_ms(),
            up: AtomicU64::new(0),
            down: AtomicU64::new(0),
            blocked: AtomicBool::new(false),
            live_up: std::sync::Arc::clone(&self.live_up),
            live_down: std::sync::Arc::clone(&self.live_down),
        });
        inner.connections.insert(id, std::sync::Arc::clone(&record));
        let joined = {
            let count = inner.devices.entry(client_ip).or_insert(0);
            *count += 1;
            *count == 1
        };
        let devices = inner.devices.len();
        drop(inner);
        if joined {
            self.emit(Event::DevicesChanged(devices));
        }
        record
    }

    fn unregister(&self, id: u64) {
        let mut devices_changed = None;
        {
            let mut inner = lock_inner(&self.inner);
            if let Some(record) = inner.connections.remove(&id) {
                inner.total_up += record.up.load(Ordering::Relaxed);
                inner.total_down += record.down.load(Ordering::Relaxed);
                let info = record.to_conn_info();
                inner.session_log.push(info);
                if let Some(count) = inner.devices.get_mut(&record.client_ip) {
                    *count -= 1;
                    if *count == 0 {
                        inner.devices.remove(&record.client_ip);
                        devices_changed = Some(inner.devices.len());
                    }
                }
            }
        }
        if let Some(devices) = devices_changed {
            self.emit(Event::DevicesChanged(devices));
        }
    }

    /// Record a failed authentication attempt from this connection's
    /// client. When it starts a block, emit AuthFailed with the IP
    /// only. A handler reports auth failure as a PermissionDenied
    /// error; other errors are not auth failures.
    fn record_auth_failure(&self, record: &ConnRecord, result: &std::io::Result<()>) {
        if matches!(result, Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied) {
            crate::diag::log_warn(format!("auth failed from {}", record.client_ip));
            if self
                .auth_guard
                .record_failure(record.client_ip, Instant::now())
            {
                self.emit(Event::AuthFailed(record.client_ip));
            }
        }
    }
}

/// One listener: the port it binds and the protocols it serves.
struct ListenerSpec {
    port: u16,
    allowed: HashSet<Protocol>,
}

/// The listeners for a config. Neither `http_port` nor `socks_port` set:
/// single mode, one listener on `port` serving every enabled protocol.
/// At least one set: split mode, `port` is ignored; the HTTP listener
/// exists only when http_port is set and http is enabled, the SOCKS
/// listener only when socks_port is set and a SOCKS protocol is enabled.
fn listener_specs(config: &Config) -> Result<Vec<ListenerSpec>, String> {
    let socks: HashSet<Protocol> = config
        .protocols
        .iter()
        .copied()
        .filter(|p| matches!(p, Protocol::Socks4 | Protocol::Socks5))
        .collect();
    match (config.http_port, config.socks_port) {
        (None, None) => Ok(vec![ListenerSpec {
            port: config.port,
            allowed: config.protocols.clone(),
        }]),
        (h, s) => {
            if h.is_some() && h == s {
                return Err("http_port and socks_port must differ".to_string());
            }
            let mut v = Vec::new();
            if let Some(port) = h {
                if config.protocols.contains(&Protocol::Http) {
                    v.push(ListenerSpec {
                        port,
                        allowed: HashSet::from([Protocol::Http]),
                    });
                }
            }
            if let Some(port) = s {
                if !socks.is_empty() {
                    v.push(ListenerSpec { port, allowed: socks });
                }
            }
            if v.is_empty() {
                return Err(
                    "no listener: enabled protocols do not match the configured ports"
                        .to_string(),
                );
            }
            Ok(v)
        }
    }
}

/// Wait for a cancelled task to finish unwinding (at most 2 s), so its
/// sockets are closed. Only blocks when called outside a runtime:
/// block_on panics inside one, and the engine is never stopped from a
/// worker thread anyway.
fn wait_for_task(task: JoinHandle<()>) {
    if tokio::runtime::Handle::try_current().is_ok() {
        return; // Inside a runtime: the task unwinds on its own.
    }
    if let Ok(rt) = runtime() {
        rt.block_on(async {
            let _ = tokio::time::timeout(Duration::from_secs(2), task).await;
        });
    }
}

/// Decrements the engine's active-connection count when the connection
/// task ends, whatever path it took.
struct ActiveGuard(std::sync::Arc<Engine>);

impl Drop for ActiveGuard {
    fn drop(&mut self) {
        if self.0.active.fetch_sub(1, Ordering::SeqCst) == 1 {
            self.0.on_idle();
        }
    }
}

/// Sample the live counters once a second while the engine is busy
/// and push each sample into the history ring. Starts on the
/// idle->busy edge; ends after 2 consecutive empty ticks (no active
/// connections), so nothing runs while the engine is idle. The first
/// tick is immediate (tokio intervals fire at once): it only sets the
/// baseline, no sample is pushed.
async fn sampler_loop(engine: std::sync::Arc<Engine>) {
    let mut interval = tokio::time::interval(Duration::from_secs(1));
    interval.tick().await;
    let mut prev_up = engine.live_up.load(Ordering::Relaxed);
    let mut prev_down = engine.live_down.load(Ordering::Relaxed);
    let mut prev_time = tokio::time::Instant::now();
    let mut prev_sample = crate::history::SpeedSample::default();
    let mut empty_ticks = 0u32;
    loop {
        interval.tick().await;
        let now_up = engine.live_up.load(Ordering::Relaxed);
        let now_down = engine.live_down.load(Ordering::Relaxed);
        let now = tokio::time::Instant::now();
        let elapsed = now.duration_since(prev_time);
        let sample = crate::history::smooth_sample(
            prev_up, prev_down, now_up, now_down, elapsed, prev_sample,
        );
        prev_up = now_up;
        prev_down = now_down;
        prev_time = now;
        prev_sample = sample;
        {
            let mut inner = lock_inner(&engine.inner);
            inner.history.push(now_unix_sec(), sample);
        }
        if engine.active.load(Ordering::SeqCst) == 0 {
            empty_ticks += 1;
            if empty_ticks >= 2 {
                break;
            }
        } else {
            empty_ticks = 0;
        }
    }
    engine.clear_sampler_task();
}

/// The accept loop: accept connections until stop() signals shutdown.
/// A single accept error (e.g. EMFILE) pauses briefly and retries; the
/// loop only exits on shutdown or when the listener is gone.
async fn accept_loop(
    engine: std::sync::Arc<Engine>,
    listener: std::sync::Arc<TcpListener>,
    config: Config,
    allowed: std::sync::Arc<HashSet<Protocol>>,
    mut shutdown: watch::Receiver<bool>,
) {
    loop {
        tokio::select! {
            _ = shutdown.changed() => break,
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, peer)) => {
                        let _ = stream.set_nodelay(true);
                        engine.try_spawn_connection(stream, peer, &config, allowed.clone(), shutdown.clone());
                    }
                    Err(e) => {
                        crate::diag::log_warn(format!("accept error: {e}"));
                        if *shutdown.borrow() {
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(10)).await;
                    }
                }
            }
        }
    }
}

/// Read one byte (the sniffed one) so the close is a clean FIN rather
/// than a reset: closing with unread received data makes the kernel send
/// RST. Only for paths where no handler reads the stream.
async fn consume_one(stream: &mut TcpStream) {
    use tokio::io::AsyncReadExt;
    let mut scratch = [0u8; 1];
    let _ = stream.read(&mut scratch).await;
}

/// One connection: sniff the protocol, then hand it to the handler for
/// that protocol. The sniffer only peeked, so a handler reads the stream
/// from its first byte.
async fn handle_connection(
    engine: std::sync::Arc<Engine>,
    mut stream: TcpStream,
    peer: SocketAddr,
    allowed: std::sync::Arc<HashSet<Protocol>>,
    mut shutdown: watch::Receiver<bool>,
    id: u64,
) {
    let _guard = ActiveGuard(std::sync::Arc::clone(&engine));
    if engine.auth_guard.is_blocked(peer.ip(), Instant::now()) {
        // Blocked: close without reading any protocol byte.
        crate::diag::log_warn(format!(
            "auth blocked {} (too many failures)",
            peer.ip()
        ));
        engine.emit(Event::AuthFailed(peer.ip()));
        return;
    }
    let protocol = tokio::select! {
        _ = shutdown.changed() => return,
        proto = sniffer::sniff_protocol(&mut stream) => match proto {
            Some(protocol) => protocol,
            None => return, // client closed, errored, or sent nothing in time
        },
    };
    let (config, up_limiter, down_limiter) = {
        let inner = lock_inner(&engine.inner);
        match inner.config.clone() {
            Some(config) => (
                config,
                inner.up_limiter.clone(),
                inner.down_limiter.clone(),
            ),
            None => return, // engine stopped while the sniff was running
        }
    };
    // Read once here, not in each handler: the flag is live state,
    // and every handler for this connection must agree on it.
    let vpn_down = engine.vpn_down_refused();
    if !allowed.contains(&protocol) {
        consume_one(&mut stream).await;
        return; // sniffed protocol is not served on this listener: close, no event
    }
    let record = engine.register(id, peer.ip(), protocol);
    match protocol {
        Protocol::Socks5 => {
            engine.emit(Event::ConnectionOpened(record.to_conn_info()));
            let result = crate::socks5::handle(
                stream,
                &engine.outbound,
                &config,
                &record,
                vpn_down,
                up_limiter.as_ref(),
                down_limiter.as_ref(),
            )
            .await;
            engine.record_auth_failure(&record, &result);
            engine.unregister(record.id);
            // Unregistered first, so the snapshot already carries the
            // final totals when the close is reported.
            engine.emit(Event::ConnectionClosed(record.to_conn_info()));
        }
        Protocol::Http => {
            engine.emit(Event::ConnectionOpened(record.to_conn_info()));
            let result = crate::http::handle(
                stream,
                &engine.outbound,
                &config,
                &record,
                vpn_down,
                up_limiter.as_ref(),
                down_limiter.as_ref(),
            )
            .await;
            engine.record_auth_failure(&record, &result);
            engine.unregister(record.id);
            engine.emit(Event::ConnectionClosed(record.to_conn_info()));
        }
        Protocol::Socks4 => {
            engine.emit(Event::ConnectionOpened(record.to_conn_info()));
            let result = crate::socks4::handle(
                stream,
                &engine.outbound,
                &config,
                &record,
                vpn_down,
                up_limiter.as_ref(),
                down_limiter.as_ref(),
            )
            .await;
            engine.record_auth_failure(&record, &result);
            engine.unregister(record.id);
            engine.emit(Event::ConnectionClosed(record.to_conn_info()));
        }
    }
}

// ---------------------------------------------------------------------------
// Handle table: Kotlin owns engines through i64 handles. Handles are never
// reused; 0 is never a valid handle.
// ---------------------------------------------------------------------------

static ENGINES: std::sync::LazyLock<Mutex<HashMap<i64, std::sync::Arc<Engine>>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn lock_table() -> MutexGuard<'static, HashMap<i64, std::sync::Arc<Engine>>> {
    ENGINES.lock().unwrap_or_else(|e| e.into_inner())
}

/// Create an engine and return its handle.
pub fn create_engine() -> i64 {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
    lock_table().insert(handle, std::sync::Arc::new(Engine::new()));
    handle
}

/// Run `f` on the engine behind `handle`, or return None when the handle
/// is unknown (never created, or already destroyed).
pub fn with_engine<T>(handle: i64, f: impl FnOnce(&std::sync::Arc<Engine>) -> T) -> Option<T> {
    lock_table().get(&handle).map(f)
}

/// Stop the engine behind `handle` and forget it. Unknown handles are a
/// no-op.
pub fn destroy_engine(handle: i64) {
    if let Some(engine) = lock_table().remove(&handle) {
        engine.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::UdpSocket;

    /// A sink that remembers every event, for tests.
    struct CollectingSink {
        events: Mutex<Vec<Event>>,
    }

    impl CollectingSink {
        fn new() -> std::sync::Arc<Self> {
            std::sync::Arc::new(Self {
                events: Mutex::new(Vec::new()),
            })
        }

        fn events(&self) -> Vec<Event> {
            self.events
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        }
    }

    impl EventSink for CollectingSink {
        fn on_event(&self, event: Event) {
            self.events
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(event);
        }
    }

    /// A port that is free right now (bind a probe, read its port, drop it).
    fn free_port() -> u16 {
        let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("probe bind");
        probe.local_addr().expect("probe addr").port()
    }

    #[test]
    fn bind_failure_is_a_clear_error() {
        // Hold a port so the engine cannot bind it.
        let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("probe bind");
        let port = probe.local_addr().expect("probe addr").port();
        let engine = std::sync::Arc::new(Engine::new());
        let err = engine
            .start(&format!("bind_addr = 127.0.0.1\nport = {port}"))
            .expect_err("bind must fail while the port is held");
        assert!(err.contains("bind"), "{err}");
        assert!(err.contains(&port.to_string()), "{err}");
        assert!(matches!(engine.state(), EngineState::Error(_)));
        // A fresh engine on a free port still starts.
        let engine2 = std::sync::Arc::new(Engine::new());
        engine2
            .start(&format!("port = {}", free_port()))
            .expect("start on a free port");
        engine2.stop();
    }

    #[test]
    fn start_stop_twice_is_safe() {
        let engine = std::sync::Arc::new(Engine::new());
        let cfg = format!("port = {}", free_port());
        engine.start(&cfg).expect("first start");
        assert!(matches!(engine.state(), EngineState::Running));
        engine.start(&cfg).expect("second start is a no-op");
        assert!(matches!(engine.state(), EngineState::Running));
        engine.stop();
        assert!(matches!(engine.state(), EngineState::Stopped));
        engine.stop(); // second stop is a no-op
        assert!(matches!(engine.state(), EngineState::Stopped));
        engine.start(&cfg).expect("start after stop");
        assert!(matches!(engine.state(), EngineState::Running));
        engine.stop();
    }

    #[test]
    fn snapshot_reflects_state() {
        let engine = std::sync::Arc::new(Engine::new());
        assert!(engine.snapshot().starts_with("stopped\t0\t0\t0"));
        engine
            .start(&format!("port = {}", free_port()))
            .expect("start");
        assert!(engine.snapshot().starts_with("running\t0\t0\t0"));
        engine.stop();
        assert!(engine.snapshot().starts_with("stopped\t0\t0\t0"));
    }

    #[tokio::test]
    async fn disabled_protocol_is_closed_quietly() {
        let engine = std::sync::Arc::new(Engine::new());
        let sink = CollectingSink::new();
        engine.set_sink(sink.clone());
        let port = free_port();
        engine
            .start(&format!("port = {port}\nprotocols = http"))
            .expect("start");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        client.write_all(&[0x05]).await.expect("write"); // SOCKS5, disabled
        tokio::time::sleep(Duration::from_millis(200)).await;
        // The connection closed, and no event was emitted for it.
        let mut buf = [0u8; 1];
        let n = client.read(&mut buf).await.expect("read");
        assert_eq!(n, 0, "engine closed the connection");
        assert!(
            sink.events()
                .iter()
                .all(|e| !matches!(e, Event::HandlerMissing(_))),
            "disabled protocol must not emit handler_missing"
        );
        engine.stop();
    }

    /// The abort-handle race (0.5.0): 200 connections that fail
    /// instantly (the client drops at once, so the task may finish
    /// before the handle is stored) must leave no stale handle
    /// behind. try_spawn_connection is called directly so all 200
    /// spawns happen before the check: no false pass from a slow
    /// accept loop.
    #[tokio::test]
    async fn abort_handles_do_not_leak_for_fast_connections() {
        let engine = std::sync::Arc::new(Engine::new());
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind test listener");
        let addr = listener.local_addr().expect("listener addr");
        let config = Config::default();
        let allowed = std::sync::Arc::new(HashSet::from([
            Protocol::Http,
            Protocol::Socks4,
            Protocol::Socks5,
        ]));
        let (_shutdown_tx, shutdown_rx) = watch::channel(false);
        for _ in 0..200 {
            let client = TcpStream::connect(addr).await.expect("connect");
            let (stream, peer) = listener.accept().await.expect("accept");
            drop(client); // instant EOF: the task finishes at once
            engine.try_spawn_connection(
                stream,
                peer,
                &config,
                allowed.clone(),
                shutdown_rx.clone(),
            );
        }
        // Poll until every task settled (10 ms sleeps, 5 s deadline).
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        loop {
            let (handles, active) = {
                let inner = lock_inner(&engine.inner);
                (
                    inner.handles.len(),
                    engine.active.load(Ordering::SeqCst),
                )
            };
            if handles == 0 && active == 0 {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "tasks did not settle: {handles} handles, {active} active"
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert_eq!(lock_inner(&engine.inner).handles.len(), 0);
    }

    /// A connection past the limit is counted, not dropped silently:
    /// with max_connections = 1, the second client is refused and the
    /// snapshot carries rejected_limit = 1.
    #[tokio::test]
    async fn limit_rejections_are_counted() {
        let (engine, _sink, port) = start_test_engine("max_connections = 1");
        // Hold the one slot: a SOCKS5 greeting, then silence.
        let mut first = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let reply = socks5_greet(&mut first, 0x00).await;
        assert_eq!(reply, [0x05, 0x00]);
        // The second client is refused at once.
        let mut second = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_closed(&mut second).await;
        let snap = engine.snapshot();
        let head = snap.lines().next().expect("snapshot head");
        let fields: Vec<&str> = head.split('\t').collect();
        assert_eq!(fields[3], "1", "one connection active: {head}");
        assert_eq!(fields[4], "1", "one limit rejection: {head}");
        assert_eq!(fields[5], "0", "no denied rejection: {head}");
        assert_eq!(fields[6], "0", "no disallowed rejection: {head}");
        engine.stop();
    }

    /// Denied and disallowed clients are counted separately in the
    /// snapshot.
    #[tokio::test]
    async fn denied_and_disallowed_rejections_are_counted() {
        // Disallowed: only 127.0.0.2 may connect; 127.0.0.1 may not.
        let (engine, _sink, port) = start_test_engine("allowed_clients = 127.0.0.2/32");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_closed(&mut client).await;
        let snap = engine.snapshot();
        let head = snap.lines().next().expect("snapshot head");
        let fields: Vec<&str> = head.split('\t').collect();
        assert_eq!(fields[6], "1", "one disallowed rejection: {head}");
        engine.stop();

        // Denied: the IP is blocked until restart.
        let (engine, _sink, port) = start_test_engine("");
        engine.deny_client("127.0.0.1");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_closed(&mut client).await;
        let snap = engine.snapshot();
        let head = snap.lines().next().expect("snapshot head");
        let fields: Vec<&str> = head.split('\t').collect();
        assert_eq!(fields[5], "1", "one denied rejection: {head}");
        engine.stop();
    }

    /// Garbage in every protocol's shape must close promptly and
    /// never wedge the engine: each case is a connection that the
    /// engine must drop (EOF within 2 s), and a good connection
    /// must still work afterwards.
    #[tokio::test]
    async fn garbage_connections_do_not_break_the_engine() {
        let (engine, _sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        // Truncated SOCKS5 greeting; first byte 0 (neither SOCKS4
        // nor SOCKS5, so the HTTP path, which closes on EOF);
        // truncated SOCKS4 head; a non-proxy first byte (HTTP path,
        // 400 then close). The SOCKS5 version-0 and invalid-domain
        // checks live in the socks5 module tests, which drive
        // `negotiate`/`read_request` directly.
        let cases: &[&[u8]] = &[
            b"\x05",
            b"\x00\x01\x00",
            b"\x04\x01\x00\x50",
            b"GARBAGE\r\n\r\n",
        ];
        for bytes in cases {
            let mut client = TcpStream::connect(("127.0.0.1", port))
                .await
                .expect("connect");
            client.write_all(bytes).await.expect("write garbage");
            client.shutdown().await.expect("shutdown write");
            // Drain until EOF: the engine closes the connection (a
            // 400 reply may come first on the HTTP path).
            let mut buf = [0u8; 4096];
            let closed = tokio::time::timeout(Duration::from_secs(2), async {
                loop {
                    match client.read(&mut buf).await {
                        Ok(0) => break,
                        Ok(_) => continue,
                        Err(_) => break,
                    }
                }
            })
            .await;
            assert!(
                closed.is_ok(),
                "engine must close garbage {bytes:?} within 2 s"
            );
        }
        // The engine still accepts a good connection afterwards.
        let _client = echo_connection(port, echo_port, b"hello").await;
        engine.stop();
    }

    /// Opening max_connections + 50 raw sockets: the extra ones are
    /// counted in rejected_limit, and new connections work again
    /// once the held ones close.
    #[tokio::test]
    async fn max_connections_rejects_the_overflow_and_recovers() {
        let echo_port = start_echo_server().await;
        let (engine, _sink, port) = start_test_engine("max_connections = 8");
        // Hold 8 connections open: each sends one SOCKS5 byte so it
        // passes the sniffer and registers (a silent connection never
        // registers, so the snapshot would not count it), then hangs
        // in the greeting read (10 s timeout) while the test runs.
        let mut held = Vec::new();
        for _ in 0..8 {
            let mut c = TcpStream::connect(("127.0.0.1", port))
                .await
                .expect("connect");
            c.write_all(&[0x05]).await.expect("write first byte");
            held.push(c);
        }
        wait_until("active reaches 8", || head_fields(&engine)[3] == "8").await;
        // 50 more: each must be refused and counted.
        let mut extra = Vec::new();
        for _ in 0..50 {
            extra.push(
                TcpStream::connect(("127.0.0.1", port))
                    .await
                    .expect("connect"),
            );
        }
        wait_until_timeout("rejected_limit reaches 50", 2, || {
            head_fields(&engine)[4].parse::<u64>().unwrap_or(0) == 50
        })
        .await;
        // The refused sockets are closed by the engine: reads end in EOF.
        for client in extra.iter_mut() {
            let mut buf = [0u8; 1];
            let n = tokio::time::timeout(Duration::from_secs(2), client.read(&mut buf))
                .await
                .expect("read finished")
                .expect("read ok");
            assert_eq!(n, 0, "a refused socket must be closed");
        }
        // Close the held connections; a new one works again.
        drop(held);
        wait_until("active reaches 0", || head_fields(&engine)[3] == "0").await;
        let _client = echo_connection(port, echo_port, b"again").await;
        engine.stop();
    }

    /// Start an engine with `extra_config` (one "key=value" line, or "")
    /// on a free port, with a collecting sink. Returns (engine, sink, port).
    fn start_test_engine(
        extra_config: &str,
    ) -> (std::sync::Arc<Engine>, std::sync::Arc<CollectingSink>, u16) {
        let engine = std::sync::Arc::new(Engine::new());
        let sink = CollectingSink::new();
        engine.set_sink(sink.clone());
        let port = free_port();
        engine
            .start(&format!("port = {port}\n{extra_config}"))
            .expect("start");
        (engine, sink, port)
    }

    /// A local TCP echo server: accepts connections and echoes them back.
    /// Returns its port.
    async fn start_echo_server() -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind echo");
        let port = listener.local_addr().expect("echo addr").port();
        tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let (mut r, mut w) = stream.into_split();
                    let _ = tokio::io::copy(&mut r, &mut w).await;
                });
            }
        });
        port
    }

    /// Read exactly `buf.len()` bytes within 5 s, or panic.
    async fn read_exact(client: &mut TcpStream, buf: &mut [u8]) {
        tokio::time::timeout(Duration::from_secs(5), client.read_exact(buf))
            .await
            .expect("read timed out")
            .expect("read failed");
    }

    /// A local UDP echo server: echoes datagrams back to their sender.
    /// Returns its port. It listens on 127.0.0.2, a different loopback
    /// IP from the 127.0.0.1 test client: the associate dispatch treats
    /// a datagram from the client's own IP as a client datagram, so a
    /// reply from 127.0.0.1 would be parsed as a header and dropped.
    async fn start_udp_echo_server() -> u16 {
        let socket = UdpSocket::bind("127.0.0.2:0")
            .await
            .expect("bind udp echo");
        let port = socket.local_addr().expect("udp echo addr").port();
        tokio::spawn(async move {
            let mut buf = vec![0u8; 64 * 1024];
            loop {
                match socket.recv_from(&mut buf).await {
                    Ok((n, from)) => {
                        if socket.send_to(&buf[..n], from).await.is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });
        port
    }

    /// Send a SOCKS5 greeting with one method and return the 2-byte reply.
    async fn socks5_greet(client: &mut TcpStream, method: u8) -> [u8; 2] {
        client
            .write_all(&[0x05, 0x01, method])
            .await
            .expect("write greeting");
        let mut reply = [0u8; 2];
        read_exact(client, &mut reply).await;
        reply
    }

    /// Send a SOCKS5 request and return the 10-byte reply (every reply
    /// in this step is a 10-byte IPv4 form).
    async fn socks5_request_reply(client: &mut TcpStream, request: &[u8]) -> [u8; 10] {
        client.write_all(request).await.expect("write request");
        let mut reply = [0u8; 10];
        read_exact(client, &mut reply).await;
        reply
    }

    /// The engine closed the connection: a read gives EOF. A reset is
    /// accepted too — after an error reply the handler closes with
    /// request bytes unread, so the kernel may send RST instead of FIN.
    async fn assert_closed(client: &mut TcpStream) {
        let mut buf = [0u8; 1];
        match tokio::time::timeout(Duration::from_secs(2), client.read(&mut buf)).await {
            Ok(Ok(0)) => {}
            Ok(Err(e)) if e.kind() == std::io::ErrorKind::ConnectionReset => {}
            other => panic!("expected the engine to close the connection, got {other:?}"),
        }
    }

    /// Send a SOCKS4 request and return the 8-byte reply.
    async fn socks4_request_reply(
        client: &mut TcpStream,
        cd: u8,
        ip: [u8; 4],
        port: u16,
        userid: &str,
        hostname: Option<&str>,
    ) -> [u8; 8] {
        let mut req = vec![0x04, cd];
        req.extend_from_slice(&port.to_be_bytes());
        req.extend_from_slice(&ip);
        req.extend_from_slice(userid.as_bytes());
        req.push(0);
        if let Some(host) = hostname {
            req.extend_from_slice(host.as_bytes());
            req.push(0);
        }
        client.write_all(&req).await.expect("write request");
        let mut reply = [0u8; 8];
        read_exact(client, &mut reply).await;
        reply
    }

    /// Basic auth header values used by the auth tests.
    const BASIC_USER_PASS: &str = "Basic dXNlcjpwYXNz";
    const BASIC_WRONG: &str = "Basic dXNlcjp3cm9uZw==";

    /// Start an engine that requires auth ("user" / "pass").
    fn start_auth_engine() -> (std::sync::Arc<Engine>, std::sync::Arc<CollectingSink>, u16) {
        start_test_engine("auth_user = user\nauth_pass = pass")
    }

    /// Start an engine in split mode: HTTP on one free port, SOCKS on
    /// another. Returns (engine, sink, http_port, socks_port).
    fn start_split_engine(
        extra_config: &str,
    ) -> (std::sync::Arc<Engine>, std::sync::Arc<CollectingSink>, u16, u16) {
        let engine = std::sync::Arc::new(Engine::new());
        let sink = CollectingSink::new();
        engine.set_sink(sink.clone());
        let http_port = free_port();
        let mut socks_port = free_port();
        while socks_port == http_port {
            socks_port = free_port();
        }
        engine
            .start(&format!(
                "http_port = {http_port}\nsocks_port = {socks_port}\n{extra_config}"
            ))
            .expect("start split");
        (engine, sink, http_port, socks_port)
    }

    /// Send a SOCKS5 greeting offering the given methods and return the 2-byte reply.
    async fn socks5_greet_many(client: &mut TcpStream, methods: &[u8]) -> [u8; 2] {
        let mut greeting = vec![0x05, methods.len() as u8];
        greeting.extend_from_slice(methods);
        client.write_all(&greeting).await.expect("write greeting");
        let mut reply = [0u8; 2];
        read_exact(client, &mut reply).await;
        reply
    }

    /// Send an RFC 1929 username/password subnegotiation and return the 2-byte reply.
    async fn socks5_auth(client: &mut TcpStream, user: &str, pass: &str) -> [u8; 2] {
        let mut req = vec![0x01, user.len() as u8];
        req.extend_from_slice(user.as_bytes());
        req.push(pass.len() as u8);
        req.extend_from_slice(pass.as_bytes());
        client.write_all(&req).await.expect("write auth");
        let mut reply = [0u8; 2];
        read_exact(client, &mut reply).await;
        reply
    }

    /// A CONNECT request with an optional Proxy-Authorization header value.
    fn connect_request(host: &str, port: u16, auth: Option<&str>) -> String {
        match auth {
            Some(value) => format!(
                "CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\nProxy-Authorization: {value}\r\n\r\n"
            ),
            None => format!("CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n\r\n"),
        }
    }

    /// Wait up to 2 s for a ConnectionClosed event and return its info.
    async fn wait_closed(sink: &std::sync::Arc<CollectingSink>) -> crate::model::ConnInfo {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let events = sink.events();
            if let Some(Event::ConnectionClosed(info)) = events
                .iter()
                .find(|e| matches!(e, Event::ConnectionClosed(_)))
            {
                return info.clone();
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "no ConnectionClosed, got: {events:?}"
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    /// Read a whole response (up to EOF) within 5 s, or panic.
    async fn read_response(client: &mut TcpStream) -> Vec<u8> {
        let mut buf = Vec::new();
        tokio::time::timeout(Duration::from_secs(5), client.read_to_end(&mut buf))
            .await
            .expect("response read timed out")
            .expect("response read failed");
        buf
    }

    /// Read a response head (up to the blank line) without waiting for
    /// the connection to close; used for CONNECT replies.
    async fn read_head_response(client: &mut TcpStream) -> Vec<u8> {
        let mut buf = Vec::new();
        let mut chunk = [0u8; 256];
        loop {
            let n = tokio::time::timeout(Duration::from_secs(5), client.read(&mut chunk))
                .await
                .expect("response read timed out")
                .expect("response read failed");
            assert!(n > 0, "connection closed before the response head");
            buf.extend_from_slice(&chunk[..n]);
            if buf.windows(4).position(|w| w == b"\r\n\r\n").is_some() {
                return buf;
            }
        }
    }

    /// A local HTTP server that records the raw request it received and
    /// answers `ok`. Returns (port, recorded bytes).
    async fn start_recording_server() -> (u16, std::sync::Arc<Mutex<Vec<u8>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let recorded = std::sync::Arc::new(Mutex::new(Vec::new()));
        let recorded_clone = std::sync::Arc::clone(&recorded);
        tokio::spawn(async move {
            if let Ok((mut stream, _)) = listener.accept().await {
                // Read the head (up to the blank line).
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                let mut head_end = None;
                while head_end.is_none() {
                    match stream.read(&mut chunk).await {
                        Ok(0) => break,
                        Ok(n) => {
                            buf.extend_from_slice(&chunk[..n]);
                            head_end = buf
                                .windows(4)
                                .position(|w| w == b"\r\n\r\n")
                                .map(|i| i + 4);
                        }
                        Err(_) => break,
                    }
                }
                let Some(end) = head_end else {
                    return;
                };
                // Read the body, if the head declares one.
                let head = String::from_utf8_lossy(&buf[..end]).into_owned();
                let content_length = head
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        if name.trim().eq_ignore_ascii_case("content-length") {
                            value.trim().parse::<usize>().ok()
                        } else {
                            None
                        }
                    })
                    .unwrap_or(0);
                while buf.len() < end + content_length {
                    match stream.read(&mut chunk).await {
                        Ok(0) => break,
                        Ok(n) => buf.extend_from_slice(&chunk[..n]),
                        Err(_) => break,
                    }
                }
                *recorded_clone
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = buf;
                let _ = stream
                    .write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",
                    )
                    .await;
            }
        });
        (port, recorded)
    }

    #[tokio::test]
    async fn http_get_is_rewritten_and_relayed() {
        let (engine, sink, port) = start_test_engine("");
        let (server_port, recorded) = start_recording_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = format!(
            "GET http://127.0.0.1:{server_port}/hello HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\nProxy-Connection: keep-alive\r\nX-Test: yes\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 200 OK"),
            "expected a 200 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        assert!(response.ends_with(b"ok"), "response body is 'ok'");
        drop(client); // let the relay finish, so the close is reported
        let received = recorded.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let expected = format!(
            "GET /hello HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\nX-Test: yes\r\nConnection: close\r\n\r\n"
        );
        assert_eq!(
            received,
            expected.as_bytes(),
            "server got the rewritten request"
        );
        // Opened at registration, closed with the final counts.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let events = sink.events();
            if events
                .iter()
                .any(|e| matches!(e, Event::ConnectionClosed(_)))
            {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "no ConnectionClosed, got: {events:?}"
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(sink
            .events()
            .iter()
            .any(|e| matches!(e, Event::ConnectionOpened(_))));
        engine.stop();
    }

    #[tokio::test]
    async fn http_post_body_arrives_intact() {
        let (engine, _sink, port) = start_test_engine("");
        let (server_port, recorded) = start_recording_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        // Head and body in a single write: the body may arrive with the
        // head (body prefix) or after it (pump); either way it must
        // reach the server intact.
        let request = format!(
            "POST http://127.0.0.1:{server_port}/submit HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\nContent-Length: 5\r\n\r\nhello"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 200 OK"),
            "expected a 200 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        let received = recorded.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let expected = format!(
            "POST /submit HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello"
        );
        assert_eq!(
            received,
            expected.as_bytes(),
            "server got the rewritten request with the body intact"
        );
        drop(client);
        engine.stop();
    }

    #[tokio::test]
    async fn http_relative_uri_is_400() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        client
            .write_all(b"GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n")
            .await
            .expect("write");
        let response = read_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 400"),
            "expected a 400 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        engine.stop();
    }

    #[tokio::test]
    async fn http_unreachable_target_is_502() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        // A port that is free right now, so nothing listens on it.
        let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("probe bind");
        let target_port = probe.local_addr().expect("probe addr").port();
        drop(probe);
        let request = format!(
            "GET http://127.0.0.1:{target_port}/ HTTP/1.1\r\nHost: 127.0.0.1:{target_port}\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 502"),
            "expected a 502 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        engine.stop();
    }

    #[tokio::test]
    async fn http_connect_relays_to_echo_server() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = format!(
            "CONNECT 127.0.0.1:{echo_port} HTTP/1.1\r\nHost: 127.0.0.1:{echo_port}\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert_eq!(
            response,
            b"HTTP/1.1 200 Connection Established\r\n\r\n",
            "expected a 200 reply, got: {}",
            String::from_utf8_lossy(&response)
        );
        // Payload through the tunnel: the echo server sends it back.
        client.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(&echo, b"hello");
        // Close our side and wait for the engine to close the connection.
        drop(client);
        let closed = {
            let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
            loop {
                let events = sink.events();
                if let Some(Event::ConnectionClosed(info)) = events
                    .iter()
                    .find(|e| matches!(e, Event::ConnectionClosed(_)))
                {
                    break info.clone();
                }
                assert!(
                    tokio::time::Instant::now() < deadline,
                    "no ConnectionClosed, got: {events:?}"
                );
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        };
        assert_eq!(closed.port, echo_port);
        assert_eq!(closed.protocol, Protocol::Http);
        assert_eq!(closed.up, 5, "5 bytes relayed client -> target");
        assert_eq!(closed.down, 5, "5 bytes relayed target -> client");
        assert!(sink
            .events()
            .iter()
            .any(|e| matches!(e, Event::ConnectionOpened(_))));
        // The snapshot totals kept the bytes after the connection closed.
        assert!(
            engine.snapshot().starts_with("running\t5\t5\t0"),
            "{}",
            engine.snapshot()
        );
        engine.stop();
    }

    #[tokio::test]
    async fn http_connect_to_closed_port_is_502() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        // A port that is free right now, so nothing listens on it.
        let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("probe bind");
        let target_port = probe.local_addr().expect("probe addr").port();
        drop(probe);
        let request = format!(
            "CONNECT 127.0.0.1:{target_port} HTTP/1.1\r\nHost: 127.0.0.1:{target_port}\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 502"),
            "expected a 502 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        engine.stop();
    }

    #[tokio::test]
    async fn http_connect_to_blocked_domain_is_403() {
        let (engine, sink, port) = start_test_engine("blocked_domains = blocked.example");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = connect_request("blocked.example", 443, None);
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 403"),
            "expected a 403 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        let closed = wait_closed(&sink).await;
        assert!(closed.blocked, "closed connection is marked blocked");
        assert_eq!(closed.host, "blocked.example");
        assert_eq!(closed.port, 443);
        engine.stop();
    }

    #[tokio::test]
    async fn http_connect_to_private_target_is_403_when_blocked() {
        let (engine, sink, port) = start_test_engine("block_private = true");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = connect_request("127.0.0.1", 80, None);
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 403"),
            "expected a 403 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        let closed = wait_closed(&sink).await;
        assert!(closed.blocked, "closed connection is marked blocked");
        assert_eq!(closed.host, "127.0.0.1");
        assert_eq!(closed.port, 80);
        engine.stop();
    }

    #[tokio::test]
    async fn require_vpn_refuses_http_connect_with_403() {
        let (engine, _sink, port) = start_test_engine("require_vpn = true");
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = connect_request("127.0.0.1", echo_port, None);
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 403"),
            "expected a 403 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        drop(client);
        // VPN comes up: the same CONNECT now succeeds.
        engine.set_vpn_active(true);
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 200"),
            "expected a 200 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        drop(client);
        engine.stop();
    }

    #[tokio::test]
    async fn http_plain_to_blocked_domain_is_403() {
        let (engine, sink, port) = start_test_engine("blocked_domains = blocked.example");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        client
            .write_all(b"GET http://blocked.example/ HTTP/1.1\r\nHost: blocked.example\r\n\r\n")
            .await
            .expect("write");
        let response = read_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 403"),
            "expected a 403 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        let closed = wait_closed(&sink).await;
        assert!(closed.blocked, "closed connection is marked blocked");
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_connect_to_blocked_domain_is_0x02() {
        let (engine, sink, port) = start_test_engine("blocked_domains = *.example");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        let domain = b"a.example";
        let mut req = vec![0x05, 0x01, 0x00, 0x03, domain.len() as u8];
        req.extend_from_slice(domain);
        req.extend_from_slice(&443u16.to_be_bytes());
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[1], 0x02, "blocked domain gets 0x02, got {reply:?}");
        assert_closed(&mut client).await;
        let closed = wait_closed(&sink).await;
        assert!(closed.blocked, "closed connection is marked blocked");
        assert_eq!(closed.host, "a.example");
        engine.stop();
    }

    #[tokio::test]
    async fn require_vpn_refuses_socks5_connect_with_0x02() {
        let (engine, sink, port) = start_test_engine("require_vpn = true");
        let echo_port = start_echo_server().await;
        // No VPN active yet (the default): the CONNECT is refused.
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        let req = [
            0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
            (echo_port >> 8) as u8,
            (echo_port & 0xff) as u8,
        ];
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[1], 0x02, "VPN down gets 0x02, got {reply:?}");
        assert_closed(&mut client).await;
        let closed = wait_closed(&sink).await;
        assert!(!closed.blocked, "a VPN refusal is not a blocked domain");
        // VPN comes up: the same CONNECT now succeeds.
        engine.set_vpn_active(true);
        let tunnel = open_socks5_tunnel(port, echo_port).await;
        drop(tunnel);
        // VPN drops again: refused once more.
        engine.set_vpn_active(false);
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[1], 0x02, "VPN down again gets 0x02, got {reply:?}");
        drop(client);
        engine.stop();
    }

    #[tokio::test]
    async fn socks4_connect_to_blocked_domain_is_0x5b() {
        let (engine, sink, port) = start_test_engine("blocked_domains = blocked.example");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let reply = socks4_request_reply(
            &mut client,
            0x01,
            [0, 0, 0, 1],
            443,
            "user",
            Some("blocked.example"),
        )
        .await;
        assert_eq!(reply[1], 0x5B, "blocked domain gets 0x5B, got {reply:?}");
        assert_closed(&mut client).await;
        let closed = wait_closed(&sink).await;
        assert!(closed.blocked, "closed connection is marked blocked");
        engine.stop();
    }

    #[tokio::test]
    async fn unblocked_ip_literal_still_connects() {
        let (engine, _sink, port) = start_test_engine("blocked_domains = blocked.example");
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = format!(
            "CONNECT 127.0.0.1:{echo_port} HTTP/1.1\r\nHost: 127.0.0.1:{echo_port}\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert_eq!(
            response,
            b"HTTP/1.1 200 Connection Established\r\n\r\n",
            "expected a 200 reply, got: {}",
            String::from_utf8_lossy(&response)
        );
        drop(client);
        engine.stop();
    }

    /// Open a SOCKS5 tunnel to the echo server and return the client.
    async fn open_socks5_tunnel(port: u16, echo_port: u16) -> TcpStream {
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        let req = [
            0x05,
            0x01,
            0x00,
            0x01,
            127,
            0,
            0,
            1,
            (echo_port >> 8) as u8,
            (echo_port & 0xff) as u8,
        ];
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[1], 0x00, "CONNECT succeeds, got {reply:?}");
        client
    }

    #[tokio::test]
    async fn close_client_aborts_open_connections() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let mut client = open_socks5_tunnel(port, echo_port).await;
        assert!(
            engine.snapshot().starts_with("running\t0\t0\t1"),
            "{}",
            engine.snapshot()
        );
        engine.close_client("127.0.0.1");
        // The client side sees EOF, and the close event carries the client IP.
        assert_closed(&mut client).await;
        let closed = wait_closed(&sink).await;
        assert_eq!(
            closed.client_ip,
            IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1))
        );
        assert!(
            engine.snapshot().starts_with("running\t0\t0\t0"),
            "{}",
            engine.snapshot()
        );
        // A malformed IP and an unknown IP are no-ops, not panics.
        engine.close_client("not an ip");
        engine.close_client("10.9.9.9");
        engine.stop();
    }

    #[tokio::test]
    async fn deny_client_refuses_new_connections_until_allowed() {
        let (engine, _sink, port) = start_test_engine("");
        let blocked = IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1));
        engine.deny_client("127.0.0.1");
        assert!(engine.is_denied(&blocked));
        // A new connection is closed without any reply.
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_closed(&mut client).await;
        drop(client);
        // Allow again: the same client connects and negotiates normally.
        engine.allow_client("127.0.0.1");
        assert!(!engine.is_denied(&blocked));
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        drop(client);
        engine.stop();
    }

    #[tokio::test]
    async fn deny_client_leaves_existing_connections_open() {
        let (engine, _sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let mut client = open_socks5_tunnel(port, echo_port).await;
        engine.deny_client("127.0.0.1");
        // The open tunnel still relays: the echo comes back.
        client.write_all(b"ping").await.expect("write");
        let mut echo = [0u8; 4];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(&echo, b"ping");
        // Only an explicit close ends it.
        engine.close_client("127.0.0.1");
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn stop_clears_denied_clients() {
        let (engine, _sink, port) = start_test_engine("");
        let blocked = IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1));
        engine.deny_client("127.0.0.1");
        assert!(engine.is_denied(&blocked));
        engine.stop();
        assert!(!engine.is_denied(&blocked));
        // After a restart the client connects again. stop() cannot
        // wait for the accept task inside a test runtime
        // (wait_for_task returns early there), so the restart bind
        // can lose the race with the aborted task releasing the port:
        // retry briefly instead of assuming the port is free.
        let mut restarted = false;
        for _ in 0..10 {
            match engine.start(&format!("port = {port}")) {
                Ok(()) => {
                    restarted = true;
                    break;
                }
                Err(_) => tokio::time::sleep(Duration::from_millis(10)).await,
            }
        }
        assert!(restarted, "restart");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        drop(client);
        engine.stop();
    }

    #[tokio::test]
    async fn http_garbage_is_400() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        // Not valid UTF-8: the head reader rejects it with a 400.
        client
            .write_all(b"\xff\xfe not http\r\n\r\n")
            .await
            .expect("write");
        let response = read_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 400"),
            "expected a 400 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        engine.stop();
    }

    #[tokio::test]
    async fn socks4_connect_relays_to_echo_server() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let reply =
            socks4_request_reply(&mut client, 0x01, [127, 0, 0, 1], echo_port, "user", None)
                .await;
        assert_eq!(reply[0], 0x00);
        assert_eq!(reply[1], 0x5A, "CONNECT succeeded, got {reply:?}");
        // Payload through the relay: the echo server sends it back.
        client.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(&echo, b"hello");
        // Close our side and wait for the engine to close the connection.
        drop(client);
        let closed = wait_closed(&sink).await;
        assert_eq!(closed.port, echo_port);
        assert_eq!(closed.protocol, Protocol::Socks4);
        assert_eq!(closed.up, 5, "5 bytes relayed client -> target");
        assert_eq!(closed.down, 5, "5 bytes relayed target -> client");
        assert!(sink
            .events()
            .iter()
            .any(|e| matches!(e, Event::ConnectionOpened(_))));
        // The snapshot totals kept the bytes after the connection closed.
        assert!(
            engine.snapshot().starts_with("running\t5\t5\t0"),
            "{}",
            engine.snapshot()
        );
        engine.stop();
    }

    #[tokio::test]
    async fn socks4a_connect_relays_to_echo_server() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        // 4a: DSTIP 0.0.0.1 + hostname after the USERID.
        let reply = socks4_request_reply(
            &mut client,
            0x01,
            [0, 0, 0, 1],
            echo_port,
            "user",
            Some("localhost"),
        )
        .await;
        assert_eq!(reply[0], 0x00);
        assert_eq!(reply[1], 0x5A, "CONNECT succeeded, got {reply:?}");
        client.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(&echo, b"hello");
        drop(client);
        let closed = wait_closed(&sink).await;
        assert_eq!(closed.port, echo_port);
        assert_eq!(closed.protocol, Protocol::Socks4);
        assert_eq!(closed.up, 5, "5 bytes relayed client -> target");
        assert_eq!(closed.down, 5, "5 bytes relayed target -> client");
        engine.stop();
    }

    #[tokio::test]
    async fn socks4_rejects_bind() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let reply =
            socks4_request_reply(&mut client, 0x02, [127, 0, 0, 1], 80, "user", None).await;
        assert_eq!(reply[0], 0x00);
        assert_eq!(reply[1], 0x5B, "BIND rejected, got {reply:?}");
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn mixed_http_socks4_socks5_on_one_port() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let (server_port, recorded) = start_recording_server().await;
        // 1. HTTP GET.
        let mut http = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = format!(
            "GET http://127.0.0.1:{server_port}/hello HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\n\r\n"
        );
        http.write_all(request.as_bytes()).await.expect("write");
        let response = read_response(&mut http).await;
        assert!(
            response.starts_with(b"HTTP/1.1 200 OK"),
            "expected a 200 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        let received = recorded.lock().unwrap_or_else(|e| e.into_inner()).clone();
        assert!(
            received.starts_with(b"GET /hello HTTP/1.1\r\n"),
            "server got the rewritten request"
        );
        drop(http);
        // 2. SOCKS4 CONNECT.
        let mut s4 = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let reply =
            socks4_request_reply(&mut s4, 0x01, [127, 0, 0, 1], echo_port, "user", None).await;
        assert_eq!(reply[1], 0x5A, "SOCKS4 CONNECT succeeded, got {reply:?}");
        s4.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut s4, &mut echo).await;
        assert_eq!(&echo, b"hello");
        drop(s4);
        // 3. SOCKS5 CONNECT.
        let mut s5 = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut s5, 0x00).await, [0x05, 0x00]);
        let mut req = vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1];
        req.extend_from_slice(&echo_port.to_be_bytes());
        let reply = socks5_request_reply(&mut s5, &req).await;
        assert_eq!(reply[1], 0x00, "SOCKS5 CONNECT succeeded, got {reply:?}");
        s5.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut s5, &mut echo).await;
        assert_eq!(&echo, b"hello");
        drop(s5);
        // All three connections closed: HTTP 0 up / 59 down (the whole
        // response, head + "ok" body, is relayed), SOCKS4 5/5, SOCKS5 5/5.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let closed = sink
                .events()
                .iter()
                .filter(|e| matches!(e, Event::ConnectionClosed(_)))
                .count();
            if closed == 3 {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "only {closed} of 3 connections closed"
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            engine.snapshot().starts_with("running\t10\t69\t0"),
            "{}",
            engine.snapshot()
        );
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_auth_right_credentials_connect() {
        let (engine, sink, port) = start_auth_engine();
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(
            socks5_greet_many(&mut client, &[0x02]).await,
            [0x05, 0x02]
        );
        assert_eq!(
            socks5_auth(&mut client, "user", "pass").await,
            [0x01, 0x00]
        );
        let mut req = vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1];
        req.extend_from_slice(&echo_port.to_be_bytes());
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[1], 0x00, "CONNECT succeeded, got {reply:?}");
        client.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(&echo, b"hello");
        drop(client);
        let closed = wait_closed(&sink).await;
        assert_eq!(closed.protocol, Protocol::Socks5);
        assert_eq!(closed.up, 5, "5 bytes relayed client -> target");
        assert_eq!(closed.down, 5, "5 bytes relayed target -> client");
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_auth_wrong_credentials_rejected() {
        let (engine, _sink, port) = start_auth_engine();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(
            socks5_greet_many(&mut client, &[0x02]).await,
            [0x05, 0x02]
        );
        assert_eq!(
            socks5_auth(&mut client, "user", "wrong").await,
            [0x01, 0x01]
        );
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_auth_no_credentials_rejected() {
        let (engine, _sink, port) = start_auth_engine();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(
            socks5_greet_many(&mut client, &[0x00]).await,
            [0x05, 0xFF]
        );
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn http_connect_auth_right_credentials() {
        let (engine, sink, port) = start_auth_engine();
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = connect_request("127.0.0.1", echo_port, Some(BASIC_USER_PASS));
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert_eq!(
            response,
            b"HTTP/1.1 200 Connection Established\r\n\r\n",
            "expected a 200 reply, got: {}",
            String::from_utf8_lossy(&response)
        );
        client.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(&echo, b"hello");
        drop(client);
        let closed = wait_closed(&sink).await;
        assert_eq!(closed.protocol, Protocol::Http);
        assert_eq!(closed.up, 5, "5 bytes relayed client -> target");
        assert_eq!(closed.down, 5, "5 bytes relayed target -> client");
        engine.stop();
    }

    #[tokio::test]
    async fn http_connect_auth_wrong_credentials_is_407() {
        let (engine, _sink, port) = start_auth_engine();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = connect_request("127.0.0.1", 443, Some(BASIC_WRONG));
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        let text = String::from_utf8_lossy(&response).into_owned();
        assert!(
            text.starts_with("HTTP/1.1 407"),
            "expected a 407 response, got: {text}"
        );
        assert!(
            text.contains("Proxy-Authenticate: Basic realm=\"ProxyShare\""),
            "407 must carry Proxy-Authenticate, got: {text}"
        );
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn http_connect_auth_no_credentials_is_407() {
        let (engine, _sink, port) = start_auth_engine();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = connect_request("127.0.0.1", 443, None);
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 407"),
            "expected a 407 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn http_get_auth_right_credentials() {
        let (engine, _sink, port) = start_auth_engine();
        let (server_port, recorded) = start_recording_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = format!(
            "GET http://127.0.0.1:{server_port}/hello HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\nProxy-Authorization: {BASIC_USER_PASS}\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 200 OK"),
            "expected a 200 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        let received = recorded.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let received = String::from_utf8_lossy(&received).into_owned();
        assert!(
            !received.contains("Proxy-Authorization"),
            "the auth header must not be forwarded: {received}"
        );
        assert!(
            received.contains("Connection: close"),
            "Connection: close is present: {received}"
        );
        drop(client);
        engine.stop();
    }

    #[tokio::test]
    async fn http_get_auth_wrong_credentials_is_407() {
        let (engine, _sink, port) = start_auth_engine();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = format!(
            "GET http://127.0.0.1:80/hello HTTP/1.1\r\nHost: example.com\r\nProxy-Authorization: {BASIC_WRONG}\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 407"),
            "expected a 407 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn http_get_auth_no_credentials_is_407() {
        let (engine, _sink, port) = start_auth_engine();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let request = format!(
            "GET http://127.0.0.1:80/hello HTTP/1.1\r\nHost: example.com\r\n\r\n"
        );
        client.write_all(request.as_bytes()).await.expect("write");
        let response = read_head_response(&mut client).await;
        assert!(
            response.starts_with(b"HTTP/1.1 407"),
            "expected a 407 response, got: {}",
            String::from_utf8_lossy(&response)
        );
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn socks4_auth_required_is_refused() {
        let (engine, _sink, port) = start_auth_engine();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let reply =
            socks4_request_reply(&mut client, 0x01, [127, 0, 0, 1], 80, "user", None).await;
        assert_eq!(reply[0], 0x00);
        assert_eq!(
            reply[1], 0x5B,
            "SOCKS4 refused when auth is required, got {reply:?}"
        );
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn auth_guard_blocks_ip_after_five_failures() {
        let (engine, sink, port) = start_auth_engine();
        // 5 failed SOCKS5 logins from 127.0.0.1.
        for _ in 0..5 {
            let mut client = TcpStream::connect(("127.0.0.1", port))
                .await
                .expect("connect");
            assert_eq!(
                socks5_greet_many(&mut client, &[0x02]).await,
                [0x05, 0x02]
            );
            assert_eq!(
                socks5_auth(&mut client, "user", "wrong").await,
                [0x01, 0x01]
            );
            assert_closed(&mut client).await;
        }
        // The IP is now blocked: the next connection closes before any
        // protocol byte is read.
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_closed(&mut client).await;
        assert!(
            sink.events()
                .iter()
                .any(|e| matches!(e, Event::AuthFailed(_))),
            "an AuthFailed event was emitted"
        );
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_connect_relays_to_echo_server() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        let mut req = vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1];
        req.extend_from_slice(&echo_port.to_be_bytes());
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[0], 0x05);
        assert_eq!(reply[1], 0x00, "CONNECT succeeded, got {reply:?}");
        // Payload through the relay: the echo server sends it back.
        client.write_all(b"hello").await.expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(&echo, b"hello");
        // Close our side and wait for the engine to close the connection.
        drop(client);
        let closed = {
            let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
            loop {
                let events = sink.events();
                if let Some(Event::ConnectionClosed(info)) = events
                    .iter()
                    .find(|e| matches!(e, Event::ConnectionClosed(_)))
                {
                    break info.clone();
                }
                assert!(
                    tokio::time::Instant::now() < deadline,
                    "no ConnectionClosed, got: {events:?}"
                );
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        };
        assert_eq!(closed.host, "127.0.0.1");
        assert_eq!(closed.port, echo_port);
        assert_eq!(closed.protocol, Protocol::Socks5);
        assert_eq!(closed.up, 5, "5 bytes relayed client -> target");
        assert_eq!(closed.down, 5, "5 bytes relayed target -> client");
        assert!(sink
            .events()
            .iter()
            .any(|e| matches!(e, Event::ConnectionOpened(_))));
        // The snapshot totals kept the bytes after the connection closed.
        assert!(
            engine.snapshot().starts_with("running\t5\t5\t0"),
            "{}",
            engine.snapshot()
        );
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_rejects_unknown_method() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        // Only GSSAPI (0x01), no no-auth: the engine has nothing to accept.
        assert_eq!(socks5_greet(&mut client, 0x01).await, [0x05, 0xFF]);
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_rejects_unsupported_command() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        // BIND (0x02) to 127.0.0.1:80.
        let reply =
            socks5_request_reply(&mut client, &[0x05, 0x02, 0x00, 0x01, 127, 0, 0, 1, 0, 80])
                .await;
        assert_eq!(reply[1], 0x07, "command not supported, got {reply:?}");
        assert_closed(&mut client).await;
        engine.stop();
    }

    /// Open a UDP ASSOCIATE and return the bound UDP port.
    async fn socks5_associate(client: &mut TcpStream) -> u16 {
        assert_eq!(socks5_greet(client, 0x00).await, [0x05, 0x00]);
        // UDP ASSOCIATE (0x03) to 0.0.0.0:0; the DST fields are ignored.
        let reply =
            socks5_request_reply(client, &[0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
        assert_eq!(reply[0], 0x05);
        assert_eq!(reply[1], 0x00, "UDP ASSOCIATE succeeded, got {reply:?}");
        u16::from_be_bytes([reply[8], reply[9]])
    }

    #[tokio::test]
    async fn socks5_udp_associate_round_trip() {
        let (engine, _sink, port) = start_test_engine("");
        let echo_port = start_udp_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let udp_port = socks5_associate(&mut client).await;
        assert_ne!(udp_port, 0);
        let udp = UdpSocket::bind("127.0.0.1:0")
            .await
            .expect("bind client udp");
        // Datagram to the echo server (127.0.0.2) with "hello" as DATA.
        let mut d = vec![0, 0, 0, 0x01, 127, 0, 0, 2];
        d.extend_from_slice(&echo_port.to_be_bytes());
        d.extend_from_slice(b"hello");
        udp.send_to(&d, ("127.0.0.1", udp_port))
            .await
            .expect("send datagram");
        let mut buf = vec![0u8; 64 * 1024];
        let (n, _) = tokio::time::timeout(Duration::from_secs(2), udp.recv_from(&mut buf))
            .await
            .expect("reply timed out")
            .expect("recv failed");
        let (target, _port, offset) = crate::udp::parse_udp_header(&buf[..n])
            .expect("reply parses as a UDP header");
        assert_eq!(
            target,
            crate::socks5::Target::Ip(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)))
        );
        assert_eq!(&buf[offset..n], b"hello");
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_udp_associate_ignores_other_ip() {
        let (engine, _sink, port) = start_test_engine("");
        let echo_port = start_udp_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let udp_port = socks5_associate(&mut client).await;
        // A datagram from 127.0.0.3 — not the IP that opened the
        // association — with a valid header pointing at the echo server.
        let foreign = UdpSocket::bind("127.0.0.3:0")
            .await
            .expect("bind foreign udp");
        let mut d = vec![0, 0, 0, 0x01, 127, 0, 0, 2];
        d.extend_from_slice(&echo_port.to_be_bytes());
        d.extend_from_slice(b"hello");
        foreign
            .send_to(&d, ("127.0.0.1", udp_port))
            .await
            .expect("send foreign datagram");
        // It is dropped before any client address is known: nothing
        // was forwarded to the target, so the counters stay at zero.
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(
            engine.snapshot().starts_with("running\t0\t0\t1"),
            "{}",
            engine.snapshot()
        );
        // And the client hears nothing back.
        let udp = UdpSocket::bind("127.0.0.1:0")
            .await
            .expect("bind client udp");
        let mut buf = vec![0u8; 64 * 1024];
        assert!(
            tokio::time::timeout(Duration::from_millis(100), udp.recv_from(&mut buf))
                .await
                .is_err(),
            "foreign datagram must not reach the client"
        );
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_udp_associate_ends_when_tcp_closes() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_udp_echo_server().await;
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let udp_port = socks5_associate(&mut client).await;
        drop(client); // close the TCP control connection
        let closed = wait_closed(&sink).await;
        assert_eq!(closed.protocol, Protocol::Socks5);
        // The UDP socket is closed too: a datagram gets no reply.
        let udp = UdpSocket::bind("127.0.0.1:0")
            .await
            .expect("bind client udp");
        let mut d = vec![0, 0, 0, 0x01, 127, 0, 0, 2];
        d.extend_from_slice(&echo_port.to_be_bytes());
        d.extend_from_slice(b"hello");
        udp.send_to(&d, ("127.0.0.1", udp_port))
            .await
            .expect("send datagram");
        let mut buf = vec![0u8; 64 * 1024];
        assert!(
            tokio::time::timeout(Duration::from_millis(100), udp.recv_from(&mut buf))
                .await
                .is_err(),
            "closed association must not answer"
        );
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_bad_address_type_is_refused() {
        let (engine, _sink, port) = start_test_engine("");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        let reply =
            socks5_request_reply(&mut client, &[0x05, 0x01, 0x00, 0x06, 127, 0, 0, 1, 0, 80])
                .await;
        assert_eq!(reply[1], 0x08, "address type not supported, got {reply:?}");
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn socks5_unresolvable_domain_is_host_unreachable() {
        let (engine, _sink, port) = start_test_engine("connect_timeout_s = 2");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        // CONNECT nonexistent.invalid:80 — the domain does not resolve.
        let mut req = vec![0x05, 0x01, 0x00, 0x03, 18];
        req.extend_from_slice(b"nonexistent.invalid");
        req.extend_from_slice(&80u16.to_be_bytes());
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[1], 0x04, "host unreachable, got {reply:?}");
        assert_closed(&mut client).await;
        engine.stop();
    }

    #[tokio::test]
    async fn split_mode_http_and_socks_work_and_snapshot_counts_both() {
        let (engine, sink, http_port, socks_port) = start_split_engine("");
        let (server_port, _recorded) = start_recording_server().await;
        // HTTP on the HTTP port.
        let mut http_client = TcpStream::connect(("127.0.0.1", http_port))
            .await
            .expect("connect http");
        let request = format!(
            "GET http://127.0.0.1:{server_port}/hello HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\n\r\n"
        );
        http_client
            .write_all(request.as_bytes())
            .await
            .expect("write");
        let response = read_response(&mut http_client).await;
        assert!(response.starts_with(b"HTTP/1.1 200 OK"));
        assert!(response.ends_with(b"ok"));
        drop(http_client);
        // SOCKS5 on the SOCKS port.
        let echo_port = start_echo_server().await;
        let mut socks_client = TcpStream::connect(("127.0.0.1", socks_port))
            .await
            .expect("connect socks");
        assert_eq!(socks5_greet(&mut socks_client, 0x00).await, [0x05, 0x00]);
        let mut req = vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1];
        req.extend_from_slice(&echo_port.to_be_bytes());
        let reply = socks5_request_reply(&mut socks_client, &req).await;
        assert_eq!(reply[1], 0x00, "CONNECT succeeded, got {reply:?}");
        socks_client
            .write_all(b"hello")
            .await
            .expect("write payload");
        let mut echo = [0u8; 5];
        read_exact(&mut socks_client, &mut echo).await;
        assert_eq!(&echo, b"hello");
        drop(socks_client);
        // Both connections closed: wait for both ConnectionClosed events.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let closed = sink
                .events()
                .iter()
                .filter(|e| matches!(e, Event::ConnectionClosed(_)))
                .count();
            if closed >= 2 {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "only {closed} connections closed, got: {:?}",
                sink.events()
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        // HTTP: up 0 (no body), down 59 (whole response). SOCKS5: 5 up, 5 down.
        assert!(
            engine.snapshot().starts_with("running\t5\t64\t0"),
            "snapshot: {}",
            engine.snapshot()
        );
        engine.stop();
    }

    #[tokio::test]
    async fn split_mode_wrong_protocol_is_closed() {
        let (engine, sink, http_port, socks_port) = start_split_engine("");
        // SOCKS5 greeting on the HTTP port: closed, no event.
        let mut client = TcpStream::connect(("127.0.0.1", http_port))
            .await
            .expect("connect");
        client
            .write_all(&[0x05, 0x01, 0x00])
            .await
            .expect("write greeting");
        assert_closed(&mut client).await;
        // HTTP on the SOCKS port: closed, no event.
        let mut client = TcpStream::connect(("127.0.0.1", socks_port))
            .await
            .expect("connect");
        client
            .write_all(b"GET http://127.0.0.1:1/ HTTP/1.1\r\nHost: x\r\n\r\n")
            .await
            .expect("write");
        assert_closed(&mut client).await;
        assert!(
            !sink
                .events()
                .iter()
                .any(|e| matches!(e, Event::ConnectionOpened(_))),
            "wrong-protocol connections must not emit an event"
        );
        engine.stop();
    }

    #[test]
    fn split_mode_equal_ports_is_error() {
        let engine = std::sync::Arc::new(Engine::new());
        let err = engine
            .start("http_port = 9000\nsocks_port = 9000")
            .expect_err("equal ports is an error");
        assert!(err.contains("must differ"), "{err}");
        assert!(matches!(engine.state(), EngineState::Error(_)));
    }

    #[test]
    fn split_mode_no_matching_listener_is_error() {
        let engine = std::sync::Arc::new(Engine::new());
        let err = engine
            .start("protocols = socks5\nhttp_port = 9000")
            .expect_err("no matching listener is an error");
        assert!(err.contains("no listener"), "{err}");
        assert!(matches!(engine.state(), EngineState::Error(_)));
    }

    #[test]
    fn split_mode_second_bind_failing_releases_first() {
        // Hold the SOCKS port so the second bind fails.
        let held = std::net::TcpListener::bind("127.0.0.1:0").expect("hold");
        let socks_port = held.local_addr().expect("held addr").port();
        let http_port = free_port();
        let engine = std::sync::Arc::new(Engine::new());
        let err = engine
            .start(&format!(
                "http_port = {http_port}\nsocks_port = {socks_port}"
            ))
            .expect_err("second bind fails");
        assert!(err.contains(&socks_port.to_string()), "{err}");
        assert!(matches!(engine.state(), EngineState::Error(_)));
        // The first port was released: it can be bound again right away.
        assert!(
            std::net::TcpListener::bind(("127.0.0.1", http_port)).is_ok(),
            "http port was not released after the failed start"
        );
    }

    #[test]
    fn split_mode_start_stop_start() {
        let engine = std::sync::Arc::new(Engine::new());
        let sink = CollectingSink::new();
        engine.set_sink(sink.clone());
        let http_port = free_port();
        let mut socks_port = free_port();
        while socks_port == http_port {
            socks_port = free_port();
        }
        let config = format!("http_port = {http_port}\nsocks_port = {socks_port}");
        engine.start(&config).expect("first start");
        assert!(matches!(engine.state(), EngineState::Running));
        engine.stop();
        assert!(matches!(engine.state(), EngineState::Stopped));
        engine.start(&config).expect("second start");
        assert!(matches!(engine.state(), EngineState::Running));
        // HTTP still works on http_port after the restart.
        runtime().expect("runtime").block_on(async {
            let (server_port, _recorded) = start_recording_server().await;
            let mut client = TcpStream::connect(("127.0.0.1", http_port))
                .await
                .expect("connect");
            let request = format!(
                "GET http://127.0.0.1:{server_port}/hello HTTP/1.1\r\nHost: 127.0.0.1:{server_port}\r\n\r\n"
            );
            client.write_all(request.as_bytes()).await.expect("write");
            let response = read_response(&mut client).await;
            assert!(response.starts_with(b"HTTP/1.1 200 OK"));
        });
        engine.stop();
    }

    #[test]
    fn split_mode_ignores_port() {
        let ignored = free_port();
        let mut http_port = free_port();
        while http_port == ignored {
            http_port = free_port();
        }
        let mut socks_port = free_port();
        while socks_port == http_port || socks_port == ignored {
            socks_port = free_port();
        }
        let engine = std::sync::Arc::new(Engine::new());
        engine
            .start(&format!(
                "port = {ignored}\nhttp_port = {http_port}\nsocks_port = {socks_port}"
            ))
            .expect("start");
        // Nothing listens on `port` in split mode.
        assert!(
            std::net::TcpStream::connect(("127.0.0.1", ignored)).is_err(),
            "port must be ignored in split mode"
        );
        engine.stop();
    }

    /// The snapshot head fields, in order: state, total_up,
    /// total_down, active, rejected×3, live_up, live_down, devices,
    /// speed_up, speed_down.
    fn head_fields(engine: &Engine) -> Vec<String> {
        engine
            .snapshot()
            .lines()
            .next()
            .expect("snapshot head")
            .split('\t')
            .map(str::to_string)
            .collect()
    }

    /// Open a SOCKS5 connection through the engine to the echo server
    /// and move `payload` through it (echoed back). Returns the client
    /// socket, still open.
    async fn echo_connection(
        port: u16,
        echo_port: u16,
        payload: &[u8],
    ) -> TcpStream {
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        assert_eq!(socks5_greet(&mut client, 0x00).await, [0x05, 0x00]);
        let mut req = vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1];
        req.extend_from_slice(&echo_port.to_be_bytes());
        let reply = socks5_request_reply(&mut client, &req).await;
        assert_eq!(reply[1], 0x00, "CONNECT succeeded, got {reply:?}");
        client.write_all(payload).await.expect("write payload");
        let mut echo = vec![0u8; payload.len()];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(echo, payload);
        client
    }

    /// Poll until `check` is true or the deadline passes (real time).
    async fn wait_until<F: Fn() -> bool>(what: &str, check: F) {
        wait_until_timeout(what, 2, check).await;
    }

    /// Poll until `check` is true or `secs` pass (real time). The
    /// sampler and the idle-stop countdown run on the engine's own
    /// runtime, so a paused test clock cannot drive them: these
    /// tests run in real time and wait for the real timers instead.
    async fn wait_until_timeout<F: Fn() -> bool>(what: &str, secs: u64, check: F) {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(secs);
        loop {
            if check() {
                return;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "timed out waiting for {what}"
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    #[tokio::test]
    async fn live_counters_include_active_and_closed() {
        let (engine, _sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let client = echo_connection(port, echo_port, b"hello").await;
        // While the connection is open, the live totals already
        // include its bytes (the closed-only totals do not yet).
        let head = head_fields(&engine);
        assert_eq!(head[3], "1", "one active connection");
        assert_eq!(head[7], "5", "live_up counts the active bytes");
        assert_eq!(head[8], "5", "live_down counts the active bytes");
        assert_eq!(head[1], "0", "closed total is still 0");
        drop(client);
        wait_until("the connection to close", || {
            head_fields(&engine)[3] == "0"
        })
        .await;
        // Closing moves the bytes into the closed totals; the live
        // totals are monotonic and keep them.
        let head = head_fields(&engine);
        assert_eq!(head[1], "5");
        assert_eq!(head[2], "5");
        assert_eq!(head[7], "5");
        assert_eq!(head[8], "5");
        engine.stop();
    }

    #[tokio::test]
    async fn devices_counts_distinct_ips_not_connections() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let c1 = echo_connection(port, echo_port, b"a").await;
        let c2 = echo_connection(port, echo_port, b"b").await;
        // Two connections from the same IP are one device, and the
        // DevicesChanged edge fired exactly once for it.
        wait_until("devices to reach 1", || head_fields(&engine)[9] == "1").await;
        assert_eq!(
            sink.events()
                .iter()
                .filter(|e| matches!(e, Event::DevicesChanged(_)))
                .count(),
            1,
            "one device joined, one edge"
        );
        assert_eq!(head_fields(&engine)[3], "2", "two active connections");
        drop(c1);
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert_eq!(head_fields(&engine)[9], "1", "one connection left, still one device");
        assert_eq!(
            sink.events()
                .iter()
                .filter(|e| matches!(e, Event::DevicesChanged(_)))
                .count(),
            1,
            "no device left while a connection remains"
        );
        drop(c2);
        wait_until("devices to reach 0", || head_fields(&engine)[9] == "0").await;
        assert!(sink
            .events()
            .iter()
            .any(|e| matches!(e, Event::DevicesChanged(0))));
        engine.stop();
    }

    #[tokio::test]
    async fn busy_and_idle_fire_only_on_edges() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let c1 = echo_connection(port, echo_port, b"a").await;
        let c2 = echo_connection(port, echo_port, b"b").await;
        wait_until("both connections to register", || {
            head_fields(&engine)[3] == "2"
        })
        .await;
        // Two connections, one Busy edge.
        assert_eq!(
            sink.events().iter().filter(|e| matches!(e, Event::Busy)).count(),
            1,
            "Busy fires once, on the 0 -> 1 edge"
        );
        drop(c1);
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert_eq!(
            sink.events().iter().filter(|e| matches!(e, Event::Idle)).count(),
            0,
            "no Idle while a connection is still open"
        );
        drop(c2);
        wait_until("the Idle edge", || {
            sink.events().iter().any(|e| matches!(e, Event::Idle))
        })
        .await;
        assert_eq!(
            sink.events().iter().filter(|e| matches!(e, Event::Idle)).count(),
            1,
            "Idle fires once, on the 1 -> 0 edge"
        );
        engine.stop();
    }

    #[tokio::test]
    async fn sampler_samples_while_busy_and_stops_when_idle() {
        let (engine, _sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        // Connect first with a 1-byte payload: the sampler starts on
        // the Busy edge, but its baseline is taken at its first poll
        // — a burst sent before that poll would be counted before
        // the baseline and no tick would ever see it (that flaked
        // on CI). So wait for the sampler's first tick (one history
        // entry), then send the burst: the next tick must see the
        // full delta.
        let mut client = echo_connection(port, echo_port, b"a").await;
        wait_until_timeout("the sampler's first tick", 5, || {
            lock_inner(&engine.inner).history.len() >= 1
        })
        .await;
        // A 10 KB burst: the next 1 s tick sees ~10 KB/s, well over
        // the 64 B/s snap, so the EMA sample is nonzero. The sampler
        // ticks in real time on the engine runtime, so this test
        // waits for the ticks instead of advancing a paused clock
        // (see wait_until_timeout).
        let payload = vec![7u8; 10 * 1024];
        client.write_all(&payload).await.expect("write burst");
        let mut echo = vec![0u8; payload.len()];
        read_exact(&mut client, &mut echo).await;
        assert_eq!(echo, payload);
        // The burst entry stays in the 120 s ring even after later
        // ticks decay to 0.
        wait_until_timeout("the first sample", 3, || {
            head_fields(&engine)[10].parse::<u64>().unwrap_or(0) > 0
        })
        .await;
        let history = engine.history();
        assert_eq!(history.split(';').count(), 120, "a full 120 s");
        assert!(
            history.split(';').any(|p| p != "0,0"),
            "the burst is in the history: {history}"
        );
        // Ticks with no new bytes read exactly 0 (delta 0 snaps to
        // 0 instead of decaying), so speed is 0 within 2 ticks.
        wait_until_timeout("speed to decay to 0", 5, || {
            head_fields(&engine)[10] == "0"
        })
        .await;
        // Close the connection: after 2 empty ticks the sampler ends
        // and nothing runs while idle.
        drop(client);
        wait_until_timeout("the sampler to end", 5, || {
            lock_inner(&engine.inner).sampler_task.is_none()
        })
        .await;
        // The history still renders a full 120 s (gaps are zeros).
        assert_eq!(engine.history().split(';').count(), 120);
        engine.stop();
    }

    #[tokio::test]
    async fn idle_stop_fires_after_idle() {
        let (engine, sink, port) = start_test_engine("idle_stop_secs = 1");
        let echo_port = start_echo_server().await;
        let client = echo_connection(port, echo_port, b"a").await;
        drop(client);
        wait_until("the Idle edge", || {
            sink.events().iter().any(|e| matches!(e, Event::Idle))
        })
        .await;
        wait_until_timeout("IdleStop after 1 s idle", 5, || {
            sink.events().iter().any(|e| matches!(e, Event::IdleStop))
        })
        .await;
        assert!(
            sink.events().iter().any(|e| matches!(e, Event::IdleStop)),
            "IdleStop fires 1 s after idle: {:?}",
            sink.events()
        );
        engine.stop();
    }

    #[tokio::test]
    async fn idle_stop_is_cancelled_by_busy() {
        let (engine, sink, port) = start_test_engine("idle_stop_secs = 1");
        let echo_port = start_echo_server().await;
        let client = echo_connection(port, echo_port, b"a").await;
        drop(client);
        wait_until("the Idle edge", || {
            sink.events().iter().any(|e| matches!(e, Event::Idle))
        })
        .await;
        // Traffic returns while the 1 s countdown is armed: the
        // countdown is cancelled and no IdleStop ever fires. The
        // 2 s wait past the deadline is the assertion itself: only
        // waiting it out proves nothing fired.
        let _client2 = echo_connection(port, echo_port, b"b").await;
        tokio::time::sleep(Duration::from_secs(2)).await;
        assert!(
            !sink.events().iter().any(|e| matches!(e, Event::IdleStop)),
            "Busy cancelled the countdown: {:?}",
            sink.events()
        );
        engine.stop();
    }

    #[tokio::test]
    async fn idle_stop_off_by_default() {
        let (engine, sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let client = echo_connection(port, echo_port, b"a").await;
        drop(client);
        wait_until("the Idle edge", || {
            sink.events().iter().any(|e| matches!(e, Event::Idle))
        })
        .await;
        // No countdown is ever armed with idle_stop_secs = 0: wait
        // past the 1 s a countdown would have used, then assert.
        tokio::time::sleep(Duration::from_secs(2)).await;
        assert!(
            !sink.events().iter().any(|e| matches!(e, Event::IdleStop)),
            "idle_stop_secs = 0 never fires IdleStop"
        );
        engine.stop();
    }

    #[tokio::test]
    async fn session_log_records_closed_connections() {
        let (engine, _sink, port) = start_test_engine("");
        let echo_port = start_echo_server().await;
        let client = echo_connection(port, echo_port, b"hello").await;
        drop(client);
        wait_until("the connection to close", || {
            head_fields(&engine)[3] == "0"
        })
        .await;
        let log = engine.session_log_since(0);
        let lines: Vec<&str> = log.lines().collect();
        assert_eq!(lines.len(), 1, "one closed connection: {log}");
        let fields: Vec<&str> = lines[0].split('\t').collect();
        assert_eq!(fields.len(), 10, "log id + 9 connection fields");
        assert_eq!(fields[0], "1", "first log id is 1");
        assert_eq!(fields[2], "127.0.0.1");
        assert_eq!(fields[3], "127.0.0.1");
        assert_eq!(fields[4], echo_port.to_string());
        assert_eq!(fields[7], "5");
        assert_eq!(fields[8], "5");
        assert_eq!(engine.session_log_since(1), "", "nothing newer than 1");
        engine.clear_session_log();
        assert_eq!(engine.session_log_since(0), "", "cleared");
        engine.stop();
    }
}
