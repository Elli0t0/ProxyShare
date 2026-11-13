//! The proxy engine: a tokio runtime, a listening socket, an accept loop,
//! a connection registry and an event sink.
//!
//! Each engine lives in the global handle table (see `create_engine` /
//! `destroy_engine`) and is owned by Kotlin through a handle (i64).
//! Kotlin talks to it only through the JNI functions in lib.rs; this file
//! has no JNI in it.
//!
//! SOCKS5 is handled (see `socks5`); HTTP and SOCKS4 still end in a
//! `handler_missing` event. Events go out through the `EventSink` the
//! JNI listener registers; the sink is never called while the engine
//! lock is held.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicI64, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use socket2::{Domain, Protocol as SockProtocol, Socket, Type};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tokio::sync::watch;
use tokio::task::JoinHandle;

use crate::config::Config;
use crate::connector::Connector;
use crate::model::{snapshot_text, ConnInfo, EngineState, Event, EventSink, Protocol};
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
}

impl ConnRecord {
    /// Fill in the target once a handler has parsed the request.
    pub fn set_target(&self, host: String, port: u16) {
        *self.target.lock().unwrap_or_else(|e| e.into_inner()) = (host, port);
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
        }
    }
}

struct EngineInner {
    config: Option<Config>,
    sink: Option<std::sync::Arc<dyn EventSink>>,
    state: EngineState,
    accept_task: Option<JoinHandle<()>>,
    shutdown_tx: Option<watch::Sender<bool>>,
    connections: HashMap<u64, std::sync::Arc<ConnRecord>>,
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
    /// The outbound used to dial targets. A plain struct; 0.1.1 adds a
    /// network-handle field to it.
    outbound: std::sync::Arc<Connector>,
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
fn client_allowed(config: &Config, ip: &IpAddr) -> bool {
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
                accept_task: None,
                shutdown_tx: None,
                connections: HashMap::new(),
                next_conn_id: 1,
                total_up: 0,
                total_down: 0,
            }),
            active: AtomicUsize::new(0),
            outbound: std::sync::Arc::new(Connector),
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
    fn bind_listener(config: &Config) -> Result<std::net::TcpListener, String> {
        let addr = SocketAddr::new(config.bind_addr, config.port);
        let domain = if config.bind_addr.is_ipv4() {
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
                self.transition_to_error(e.clone());
                return Err(e);
            }
        };
        {
            let mut inner = lock_inner(&self.inner);
            match inner.state {
                EngineState::Starting | EngineState::Running => return Ok(()),
                _ => inner.state = EngineState::Starting,
            }
        }
        self.emit(Event::StateChanged(EngineState::Starting));

        let rt = match runtime() {
            Ok(rt) => rt,
            Err(e) => {
                self.transition_to_error(e.clone());
                return Err(e);
            }
        };
        let std_listener = match Self::bind_listener(&config) {
            Ok(listener) => listener,
            Err(e) => {
                self.transition_to_error(e.clone());
                return Err(e);
            }
        };

        let mut inner = lock_inner(&self.inner);
        if inner.state != EngineState::Starting {
            // A stop() ran while we were binding: drop the listener and
            // stay stopped instead of starting behind the caller's back.
            return Ok(());
        }
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let engine = std::sync::Arc::clone(self);
        let task_config = config.clone();
        // The std listener becomes a tokio listener inside this task, where
        // a runtime context exists. If that fails, the engine reports an
        // error instead of listening.
        let accept = rt.spawn(async move {
            match TcpListener::from_std(std_listener) {
                Ok(listener) => {
                    accept_loop(engine, std::sync::Arc::new(listener), task_config, shutdown_rx)
                        .await;
                }
                Err(e) => {
                    engine.transition_to_error(format!("failed to wrap listener: {e}"));
                }
            }
        });
        inner.accept_task = Some(accept);
        inner.shutdown_tx = Some(shutdown_tx);
        inner.config = Some(config);
        inner.state = EngineState::Running;
        drop(inner);
        self.emit(Event::StateChanged(EngineState::Running));
        Ok(())
    }

    /// Stop listening, cancel the accept task and close the sockets.
    /// Idempotent: stopping an already stopped engine is a no-op. A
    /// connection task stuck waiting for a first byte is woken by the
    /// shutdown signal. The accept task is waited for (at most 2 s) so
    /// its listener socket is closed before this returns: aborting alone
    /// leaves the socket bound until the task is dropped, and a restart
    /// on the same port would fail with "Address already in use".
    pub fn stop(&self) {
        let (accept_task, was_running);
        {
            let mut inner = lock_inner(&self.inner);
            was_running = inner.state != EngineState::Stopped;
            if let Some(tx) = inner.shutdown_tx.take() {
                let _ = tx.send(true);
            }
            accept_task = inner.accept_task.take();
            if let Some(task) = &accept_task {
                task.abort();
            }
            // Drain first, then add the counters: iterating the drain
            // borrows the map, and the totals live in the same struct.
            let drained: Vec<_> = inner.connections.drain().map(|(_, r)| r).collect();
            for record in drained {
                inner.total_up += record.up.load(Ordering::Relaxed);
                inner.total_down += record.down.load(Ordering::Relaxed);
            }
            inner.state = EngineState::Stopped;
        }
        if let Some(task) = accept_task {
            wait_for_task(task);
        }
        if was_running {
            self.emit(Event::StateChanged(EngineState::Stopped));
        }
    }

    /// The snapshot text (see docs/ENGINE_API.md).
    pub fn snapshot(&self) -> String {
        let (state, total_up, total_down, mut conns);
        {
            let inner = lock_inner(&self.inner);
            state = inner.state.clone();
            total_up = inner.total_up;
            total_down = inner.total_down;
            conns = inner
                .connections
                .values()
                .map(|r| r.to_conn_info())
                .collect::<Vec<_>>();
        }
        conns.sort_by_key(|c| c.id);
        snapshot_text(&state, total_up, total_down, &conns)
    }

    /// Accept a connection if the client is allowed and the connection
    /// limit is not reached; otherwise drop it. The limit counts every
    /// connection task currently running.
    fn try_spawn_connection(
        self: &std::sync::Arc<Self>,
        stream: TcpStream,
        peer: SocketAddr,
        config: &Config,
        shutdown: watch::Receiver<bool>,
    ) {
        if !client_allowed(config, &peer.ip()) {
            return; // stream dropped: client not allowed, no event
        }
        if self.active.fetch_add(1, Ordering::SeqCst) >= config.max_connections as usize {
            self.active.fetch_sub(1, Ordering::SeqCst);
            return; // stream dropped: at the limit, no event
        }
        let engine = std::sync::Arc::clone(self);
        tokio::task::spawn(async move {
            handle_connection(engine, stream, peer, shutdown).await;
        });
    }

    fn register(&self, client_ip: IpAddr, protocol: Protocol) -> std::sync::Arc<ConnRecord> {
        let mut inner = lock_inner(&self.inner);
        let id = inner.next_conn_id;
        inner.next_conn_id += 1;
        let record = std::sync::Arc::new(ConnRecord {
            id,
            client_ip,
            target: std::sync::Mutex::new((String::new(), 0)),
            protocol,
            started_ms: now_ms(),
            up: AtomicU64::new(0),
            down: AtomicU64::new(0),
        });
        inner.connections.insert(id, std::sync::Arc::clone(&record));
        record
    }

    fn unregister(&self, id: u64) {
        let mut inner = lock_inner(&self.inner);
        if let Some(record) = inner.connections.remove(&id) {
            inner.total_up += record.up.load(Ordering::Relaxed);
            inner.total_down += record.down.load(Ordering::Relaxed);
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
        self.0.active.fetch_sub(1, Ordering::SeqCst);
    }
}

/// The accept loop: accept connections until stop() signals shutdown.
/// A single accept error (e.g. EMFILE) pauses briefly and retries; the
/// loop only exits on shutdown or when the listener is gone.
async fn accept_loop(
    engine: std::sync::Arc<Engine>,
    listener: std::sync::Arc<TcpListener>,
    config: Config,
    mut shutdown: watch::Receiver<bool>,
) {
    loop {
        tokio::select! {
            _ = shutdown.changed() => break,
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, peer)) => {
                        engine.try_spawn_connection(stream, peer, &config, shutdown.clone());
                    }
                    Err(_) => {
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
    mut shutdown: watch::Receiver<bool>,
) {
    let _guard = ActiveGuard(std::sync::Arc::clone(&engine));
    let protocol = tokio::select! {
        _ = shutdown.changed() => return,
        proto = sniffer::sniff_protocol(&mut stream) => match proto {
            Some(protocol) => protocol,
            None => return, // client closed, errored, or sent nothing in time
        },
    };
    let config = match lock_inner(&engine.inner).config.clone() {
        Some(config) => config,
        None => return, // engine stopped while the sniff was running
    };
    if !config.protocols.contains(&protocol) {
        consume_one(&mut stream).await;
        return; // sniffed protocol is disabled: close, no event
    }
    let record = engine.register(peer.ip(), protocol);
    match protocol {
        Protocol::Socks5 => {
            engine.emit(Event::ConnectionOpened(record.to_conn_info()));
            let _ = crate::socks5::handle(stream, &*engine.outbound, &config, &record).await;
            engine.unregister(record.id);
            // Unregistered first, so the snapshot already carries the
            // final totals when the close is reported.
            engine.emit(Event::ConnectionClosed(record.to_conn_info()));
        }
        _ => {
            // HTTP and SOCKS4 have no handler yet.
            consume_one(&mut stream).await;
            engine.emit(Event::HandlerMissing(record.to_conn_info()));
            engine.unregister(record.id);
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
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

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
    async fn sniffed_connection_reports_handler_missing() {
        // SOCKS4 has no handler yet: it reports handler_missing and closes.
        // (SOCKS5 is covered by socks5_connect_relays_to_echo_server.)
        let engine = std::sync::Arc::new(Engine::new());
        let sink = CollectingSink::new();
        engine.set_sink(sink.clone());
        let port = free_port();
        engine
            .start(&format!("port = {port}"))
            .expect("start");
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        client.write_all(&[0x04]).await.expect("write");
        // Wait up to 2 s for the handler_missing event.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let events = sink.events();
            if events
                .iter()
                .any(|e| matches!(e, Event::HandlerMissing(_)))
            {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "no handler_missing event, got: {events:?}"
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        // The engine closed the connection: a read gives EOF.
        let mut buf = [0u8; 1];
        let n = client.read(&mut buf).await.expect("read");
        assert_eq!(n, 0, "engine closed the connection");
        // No connection is left registered, and the engine still runs.
        assert!(engine.snapshot().starts_with("running\t0\t0\t0"));
        engine.stop();
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
}
