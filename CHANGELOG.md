# Changelog

## 0.1.8 — fix crash on open (native lib failed to load)

- Root cause, found in the shipped 0.1.7 APK: libproxyshare_engine.so
  references android_setsocknetwork but has NO libandroid DT_NEEDED,
  so System.loadLibrary fails and the app dies in Application.onCreate.
  cargo-ndk defaulted to platform 21, whose libandroid stub lacks the
  symbol (API 23+), so the linker dropped the dependency.
- Fix: cargo ndk --platform 26 (== minSdk) in both cargoBuild tasks,
  so the stub exports the symbol and the .so records the DT_NEEDED.
- No code changed; engine version bumped to 0.1.8 to match the app.

## 0.1.7 — outbound mode + kill switch + exit-IP check

- AppSettings gains outboundMode ("default"/"vpn_only"/"wifi"/
  "mobile") and exitIpUrl (default https://api.ipify.org), both
  Kotlin-only: toEngineConfig() is unchanged (test proves it).
- New service/NetworkSelector.kt: OutboundMode + setting-string
  mapping, one NetworkCallback per mode, checkExitIp (manual only).
- ProxyController: start waits up to 2 s for the mode's network
  (else Error "No <mode> network available"); while running it
  pushes the network handle to the engine; VPN_ONLY kill switch
  stops at once on network loss, never falls back.
- Engine gains setError() for pre-start errors. Home: segmented
  Default / VPN only / Wi-Fi / Mobile (stopped only) + "Check exit
  IP" with a result card. Strings en/fa.

## 0.1.6 — outbound network binding (Rust only, TCP only)

- New rust/engine/src/netbind.rs: bind_socket_to_network calls
  android_setsocknetwork (libandroid) on Android, no-op elsewhere.
- Connector holds an AtomicU64 network handle (0 = default); connect
  opens each outbound socket with TcpSocket and binds it before
  connecting. Timeout, per-address loop, TCP_NODELAY and error
  mapping unchanged.
- Engine::set_outbound_network + JNI nativeSetOutboundNetwork
  (nativeStop pattern) + Kotlin NativeEngine/Engine wrappers.
- Known limitation: UDP ASSOCIATE sockets and DNS lookups are NOT
  bound yet — TCP only.

## 0.1.5 — navigation shell + Home screen

- New ui/AppRoot.kt (a composable, not the Application class):
  Scaffold with a bottom NavigationBar — Home, Connections,
  Settings — plain rememberSaveable state, no navigation library.
  Connections and Settings are placeholders until 0.1.8 and 0.1.9
  (private placeholder composables in AppRoot.kt). Only core
  Material icons (Home, List, Settings).
- New data/NetworkInfo.kt: IfKind (WIFI, HOTSPOT, ETHERNET, MOBILE,
  VPN, OTHER), NetIf, classifyInterface, listIPv4 (up, non-loopback
  IPv4 only), networkChanges callbackFlow (available/lost/link
  properties, VPN networks included) and networkInterfaces, which
  re-lists on every network change and every 5 s (hotspot toggles do
  not always fire callbacks), debounced 300 ms, on Dispatchers.IO.
- HomeScreen rewritten, driven by ProxyController state: large
  animated Start/Stop button (64 dp, 28 dp corners, animated color
  and label), status card (Stopped / Starting / Running / Error with
  the message), running totals (up/down bytes, active connections)
  from the snapshot, address card (one row per non-VPN interface,
  "label (name): IP:port" with a copy button; the raw interface name
  is always shown, so wlan1-as-hotspot stays visible; VPN
  interfaces listed separately, greyed out, "outbound only"),
  credentials card when auth is on (user + copy, password hidden
  with tap to reveal + copy), protocol chips (HTTP / SOCKS4 /
  SOCKS5) reflecting the settings, warning banner when auth is off
  and private_only is off. API 33+: an explanation dialog, then the
  POST_NOTIFICATIONS request; the proxy still starts when it is
  denied. Temporary 0.1.4 buttons removed. settings.keepScreenOn is
  honored while running. MainActivity now shows AppRoot.
- Strings in values/ and values-fa/. Unit test for
  classifyInterface (6 tests: every prefix group + unknown).
- 3 new files this step (AppRoot, NetworkInfo, NetworkInfoTest) —
  the placeholders live in AppRoot.kt to stay within the rule.

## 0.1.4 — service + ProxyController

- CI: `./gradlew testDebugUnitTest` now runs before assembleDebug
  (workflow edit, separate commit).
- New app/ProxyShareApp.kt: Application subclass calling
  ProxyController.init(this) in onCreate; registered with
  android:name in the manifest.
- New service/ProxyController.kt: app-scoped singleton (object, no
  Hilt) owning the one Engine, its own CoroutineScope (SupervisorJob
  + Dispatchers.Default) and a SettingsRepository. Exposes state /
  snapshot / events, plus a settings flow (needed for the
  notification's port text; the settings screen will use it too).
  start() reads settings, runs ensurePassword(), starts the engine;
  stop() also resets an Error state to Stopped. Both are safe to call
  twice and never throw; concurrent starts are guarded by a Mutex.
- New service/ProxyService.kt: foreground service (specialUse,
  PROPERTY_SPECIAL_USE_FGS_SUBTYPE local_proxy_server). Ongoing
  notification (channel proxy_running, IMPORTANCE_LOW) with a Stop
  action and a tap action opening MainActivity, using a new small
  vector status icon (ic_proxy_status, swap arrows) instead of the
  launcher icon; text "Running on port <port>", refreshed if the
  settings land or change after start. Holds a non-reference-counted
  PARTIAL_WAKE_LOCK only while running. Observes the engine state:
  Stopped (from the app) stops the service; Error shows the error
  text, keeps the notification visible (detach), and stops the
  service. ACTION_STOP does the same from the notification.
- Manifest: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
  POST_NOTIFICATIONS, WAKE_LOCK permissions; the service declared
  exported=false with foregroundServiceType specialUse.
- HomeScreen (temporary, replaced in 0.1.5): Start and Stop buttons
  plus the live state text (Start launches the service, Stop calls
  ProxyController.stop()).
- New dependency: androidx.core 1.16.0, for ServiceCompat,
  ContextCompat and NotificationCompat. It was already in the APK
  through activity-compose, so the size cost is ~0.
- Rust side untouched except ENGINE_VERSION, now "engine 0.1.4".

## 0.1.3 — engine/Engine.kt (step 0.0.10b)

- New app/src/main/java/com/example/proxyshare/engine/Engine.kt:
  Kotlin wrapper that owns the native handle (`nativeCreate` in the
  constructor). Exposes `state: StateFlow<EngineState>` (sealed
  interface Stopped/Starting/Running/Error, mirroring the Rust
  EngineState), `snapshot: StateFlow<Snapshot>` and `events:
  SharedFlow<EngineEvent>` (extra buffer 256, DROP_OLDEST).
- `start(settings)` and `stop()` are suspend functions on
  Dispatchers.IO; `start` maps settings with `toEngineConfig()`,
  returns "" or the error text, and polls `nativeSnapshot` every 1 s
  while running; `setFastPolling(true)` switches the poll to 250 ms
  for the connections screen; `close()` destroys the handle and is
  safe to call twice. The listener is registered once in init;
  events arrive on a native thread and are only tryEmit-ted, and a
  type-1 event also updates the state (with its error message).
- `parseSnapshot` is verbatim from the spec: null for a missing or
  short head line, bad head numbers fall back to 0, a malformed
  connection line is skipped, never a crash.
- Tests: 7 parser tests in app/src/test/.../EngineTest.kt (good
  snapshot, empty text, short head, bad head numbers, malformed
  connection lines skipped, blank lines ignored, state-name mapping).
- Correction: 0.1.2 was Kotlin-only and left ENGINE_VERSION at
  "engine 0.1.1", so its home screen showed the old engine version.
  It is "engine 0.1.3" now, in step with the app version.
- Step 0.0.10b shipped as 0.1.3: 0.0.10, 0.1.0, 0.1.1 and 0.1.2
  were taken. The CI workflow still does not run
  `./gradlew testDebugUnitTest` — run it locally until the workflow
  gains the step.

## 0.1.2 — SettingsRepository (step 0.0.10a)

- New app/src/main/java/com/example/proxyshare/data/SettingsRepository.kt:
  Preferences DataStore with every Config field plus themeMode,
  language and keepScreenOn. Exposes `Flow<AppSettings>` and a
  suspend update function per field; `ensurePassword` generates a
  random 12-character password at first run when auth is on and none
  is set. The password is never logged.
- `AppSettings.toEngineConfig()` maps settings to the engine's
  key=value text (verbatim from the spec): auth keys only when auth
  is enabled with a non-blank user and password, `allowed_clients`
  only when the list is non-empty — no empty keys are written.
  Defaults: port 8080, all three protocols on, private_only true.
- Unit test (new app/src/test/.../SettingsRepositoryTest.kt, JUnit
  4.13.2 added to the version catalog): the full default mapping,
  auth keys omitted when the password is empty or auth is off,
  `allowed_clients` omitted when empty / joined when set, password
  length and character set. 6 tests.
- Step 0.0.10a shipped as 0.1.2: 0.0.10, 0.1.0 and 0.1.1 were taken.
  Note: the CI workflow does not run `./gradlew testDebugUnitTest`
  yet — run it locally until the workflow gains the step.

## 0.1.1 — SOCKS5 UDP ASSOCIATE (step 0.0.9b)

- CMD 0x03 in socks5.rs: `read_request` now returns the command too
  and `handle` dispatches CONNECT (existing body, now in `connect`)
  vs UDP ASSOCIATE (new `associate`) vs anything else (0x07).
- `associate` binds one UdpSocket on the TCP connection's local IP
  (port 0), replies success with its address, then loops with
  `tokio::select!` over the TCP control connection (read 0 or error
  ends the association) and `udp.recv_from` with the idle timeout.
  Client-IP datagrams are parsed, resolved with lookup_host, their
  DATA sent to the target and the client's UDP source remembered;
  other datagrams get the sender's address prepended as a header and
  go to the remembered client address, and are dropped while no
  client address is known yet. Only the opening IP may send. Whole
  datagrams count into up/down; the destination host:port is metadata
  only. The `#[allow(dead_code)]` on `parse_udp_header` is gone —
  it is used now.
- Tests: 3 new engine tests with a local UDP echo server (it listens
  on 127.0.0.2, a different loopback IP from the client, because the
  dispatch treats a datagram from the client's own IP as a client
  datagram): round trip (answer comes back wrapped in a header),
  foreign-IP datagram ignored (snapshot counters stay 0), closing
  the TCP connection ends the association (ConnectionClosed + no UDP
  answer). Total tests now 74 (71 + 3).
- docs/ENGINE_API.md records UDP ASSOCIATE.
- Step 0.0.9b shipped as 0.1.1: 0.0.9, 0.0.10 and 0.1.0 were taken.

## 0.1.0 — SOCKS5 UDP header, pure functions (step 0.0.9a)

- New rust/engine/src/udp.rs: `parse_udp_header` and
  `build_udp_header` (verbatim from the spec). Datagram layout is
  RSV(2) = 0, FRAG(1) = 0, ATYP, DST.ADDR, DST.PORT, DATA; FRAG != 0
  means drop. No sockets in this step.
- `Target` in socks5.rs is now `pub(crate)` (plus `Debug` /
  `PartialEq` derives so the tests can compare it) so udp.rs can use
  it; lib.rs has `pub mod udp`.
- Tests: v4, domain and v6 round trips, truncated input returns None,
  FRAG != 0 returns None. Total tests now 71 (66 + 5).
- docs/ENGINE_API.md records the UDP header functions.
- Step 0.0.9a shipped as 0.1.0: 0.0.9 and 0.0.10 were both taken.

## 0.0.10 — failed-auth throttle (step 0.0.8b)

- New `AuthGuard` in rust/engine/src/auth.rs (verbatim from the spec):
  after 5 failed attempts within 60 s from one IP, the IP is blocked
  for 5 minutes; the time is passed in, so tests use fake Instants
  and never sleep. One guard lives in Engine.
- A blocked IP is closed in `handle_connection` before any protocol
  byte is read. A handler reports an auth failure as a
  `PermissionDenied` error, which the engine records against the
  connection's client IP; when a block starts, an `AuthFailed` event
  (type 5) is emitted with the IP only. A blocked IP that tries again
  also emits `AuthFailed`.
- Tests: 3 guard unit tests (4 failures do not block, the 5th does,
  the block ends after 5 minutes, failures older than 60 s do not
  count) + 1 engine test (5 wrong SOCKS5 logins, then the next
  connection closes at the door and `AuthFailed` is emitted). The
  model test now covers the type-5 callback.
- docs/ENGINE_API.md records the throttle and the type-5 event (and
  fixes the stale Server line that still said HTTP/SOCKS4 had no
  handler).

## 0.0.9 — authentication (step 0.0.8a)

- New rust/engine/src/auth.rs: `ct_eq` and `base64_decode`
  (hand-written, no crates), `required` / `check` / `check_basic`
  against the config's `auth_user` / `auth_pass`.
- SOCKS5: with auth, only method 0x02 is accepted, then RFC 1929
  ([0x01, 0x00] on success, [0x01, 0x01] on failure and close);
  without auth, no-auth only as before.
- HTTP: with auth, a missing or wrong `Proxy-Authorization: Basic`
  header gets a 407 with `Proxy-Authenticate: Basic
  realm="ProxyShare"`; applies to CONNECT and plain requests.
- SOCKS4: refused with 0x5B when auth is required (it has no auth).
- Tests: auth.rs unit tests (base64, ct_eq, check_basic); every
  protocol with right, wrong and no credentials — SOCKS5 3, HTTP
  CONNECT 3 + plain GET 3, SOCKS4 1 with auth → 0x5B. Attempted
  passwords are never logged or emitted.
- docs/ENGINE_API.md records the auth contract.

## 0.0.8 — SOCKS4/4a

- New rust/engine/src/socks4.rs: `handle` (same signature as
  socks5::handle), CONNECT only, 10 s timeout on every read,
  `read_cstring` for USERID and the 4a hostname, 0x5A on success and
  0x5B on failure (wrong command, DNS or connect failure).
- Engine: explicit `Protocol::Socks4` arm copied from the Socks5 arm;
  every protocol now has a handler, so the placeholder arm and its
  `handler_missing` emission are gone. `Event::HandlerMissing` stays
  in the contract (type 4) with `#[allow(dead_code)]`. The old
  handler_missing test is deleted; the disabled-protocol test still
  asserts no event is emitted for a disabled protocol.
- Tests: plain SOCKS4 and SOCKS4a through the engine to a local echo
  server (0x5A, echo works, 5 up / 5 down), BIND gets 0x5B, and a
  mixed test (HTTP, SOCKS4, SOCKS5 one after another on the same port,
  snapshot totals 10 up / 12 down).
- docs/ENGINE_API.md records the SOCKS4 contract.

## 0.0.7 — HTTP CONNECT tunnels (step 0.0.6a)

- CONNECT tunnels in rust/engine/src/http.rs: `split_host_port` (host
  may be an IPv6 literal in brackets), target set as soon as parsed,
  502 for DNS or connect failure, `200 Connection Established` on
  success, bytes already sent after the head forwarded, then relayed
  untouched. The head cap is 16 KiB for both CONNECT and plain HTTP
  (was 64 KiB in 0.0.6). Plain HTTP still forwards GET/POST: the 501
  in the 0.0.6a spec assumed 0.0.6a landed before 0.0.6b, which is
  already green.
- Tests: `split_host_port` unit test; CONNECT to a local echo server
  (200, echo works, 5 up / 5 down), CONNECT to a closed port gives
  502, invalid-UTF-8 garbage gives 400.
- docs/ENGINE_API.md records the CONNECT contract.

## 0.0.6 — plain HTTP forwarding (step 0.0.6b)

- New rust/engine/src/http.rs: `rewrite_head` (pure function, the
  reference rewrite verbatim: absolute URI to origin-form, hop-by-hop
  headers dropped, `Connection: close` added) and `handle` (reads the
  head with a 10 s timeout, 400 for anything but an absolute-URI
  request, 502 for an unreachable target, sends the rewritten head
  plus body bytes already read, then relays both directions untouched;
  bodies are never parsed or logged).
- Engine: HTTP connections now emit `ConnectionOpened` (type 2) at
  registration and `ConnectionClosed` (type 3) at the end, like SOCKS5;
  SOCKS4 still reports `handler_missing` (type 4). `nativeVersion()`
  now returns "engine 0.0.6".
- Tests: `rewrite_head` unit tests (absolute URI with and without a
  port, all 7 hop-by-hop headers dropped, relative URI / CONNECT /
  https / bad port rejected); GET and POST against a local recording
  server (rewritten head received, body intact), relative URI gives
  400, unreachable target gives 502.
- docs/ENGINE_API.md records the HTTP contract.

## 0.0.5 — relay + SOCKS5 CONNECT

- New rust/engine/src/connector.rs: an `Outbound` trait (the seam 0.1.1
  uses to bind sockets to an Android Network) with a plain `TcpOutbound`
  implementation: resolves via `tokio::net::lookup_host`, tries the
  addresses in order, applies `connect_timeout_s` to the lookup and to
  each connect attempt, sets `TCP_NODELAY`. A failure is `Dns` (name did
  not resolve) or `Connect` (no address accepted it), so the handler can
  answer with the right SOCKS5 reply code.
- New rust/engine/src/relay.rs: `pump` (16 KiB buffer, live counting into
  the connection record's counters, idle timeout, clean shutdown on EOF)
  and `relay` (two pumps joined with `tokio::join!`, one per direction;
  when either side ends, the other ends with it).
- New rust/engine/src/socks5.rs: method negotiation (no-auth only — any
  other list gets `[0x05, 0xFF]`), CONNECT requests with ATYP IPv4 /
  domain / IPv6, reply codes 0x00, 0x04, 0x05, 0x07, 0x08, a 10 s
  negotiation timeout, the record's target filled in as soon as the
  request is parsed, and the relay counting live into the record.
- Engine: SOCKS5 connections now emit `ConnectionOpened` (type 2) at
  registration and `ConnectionClosed` (type 3) with the final counts at
  the end (unregistered first, so the snapshot already carries the final
  totals); HTTP and SOCKS4 still report `handler_missing` (type 4). The
  sniffer only peeks, so the SOCKS5 handler reads the stream from its
  first byte; the paths where no handler reads the stream still consume
  the sniffed byte first, so the close is a clean FIN. Counters count
  relayed payload only, not the greeting, request or reply bytes.
- Tests: a SOCKS5 client talking to a local echo server through the
  engine (greeting, CONNECT, payload echoed back, opened/closed events,
  5 up / 5 down, snapshot totals kept after close); unknown method ->
  0xFF; BIND -> 0x07; bad ATYP -> 0x08; unresolvable domain -> 0x04;
  connector and relay unit tests over real sockets and in-memory
  duplex streams. `nativeVersion()` now returns "engine 0.0.5".
- FIX (same version, run #19 red): cargo test failed to compile with 3
  errors in relay.rs — E0505 "cannot move out of `ctr` / `up` /
  `down` because it is borrowed". This red was my own doing: in the
  previous push I changed two `tokio::join!` arms to `async move`
  "to be safe" without the compiler asking for it, and a `move` arm
  moves the counter while the sibling arm borrows it. In edition 2021
  a plain `async` arm captures by reference, so a value borrowed by
  one arm can also be read by the other — the plain version was
  correct. Both arms are back to plain `async`. Lesson: never apply a
  speculative fix the compiler never asked for.
- FIX (same version, run #20 red): cargo test passed (33 passed), but
  clippy failed with one lint — `async_fn_in_trait` on
  `Outbound::connect` (an `async fn` in a trait cannot state the
  `Send` bound its future needs). The trait and the `TcpOutbound`
  impl are now a plain `fn` returning `impl Future<Output =
  Result<TcpStream, ConnectError>> + Send`, with the body wrapped in
  `async move`; the call site (socks5) and the tests await it the
  same way, so nothing else changed.
- FIX (same version, runs #20–#21 red): the `Outbound` trait's
  async-in-trait signature failed clippy, and the trait had exactly
  one implementation with nothing needing it before 0.1.1, so it is
  deleted: `Connector` is now a plain struct with an inherent async
  `connect`, and the SOCKS5 handler takes `&Connector` directly.
- FIX (same version, run #22 red): cargo test passed (33 passed), but
  clippy failed with one lint — `explicit_auto_deref` on `&*engine.
  outbound`: the parameter is now a concrete `&Connector`, so the
  explicit deref is redundant (auto-deref does it). One line.
- docs/ENGINE_API.md records the SOCKS5, outbound and relay contracts.

## 0.0.4 — server shell + JNI lifecycle

- New rust/engine/src/engine.rs: a global tokio runtime (multi-thread, 2
  workers named "ps-worker"), an Engine stored in a global handle table
  (Mutex<HashMap<i64, Arc<Engine>>>), start() binding a TcpListener via
  socket2 (SO_REUSEADDR, backlog 128) with an accept loop enforcing
  max_connections and the client filter (allowed_clients + private_only),
  a connection registry (id, counters as AtomicU64), stop() cancelling
  the accept task and closing sockets (connection tasks are woken by the
  shutdown signal, so everything closes well within 2 s).
- New rust/engine/src/sniffer.rs: ProtocolSniffer via peek() — 0x04
  Socks4, 0x05 Socks5, else Http — with a 5 s wait for the first byte.
  Handlers are not implemented yet: a sniffed connection is registered,
  its sniffed byte consumed, reported as a handler_missing event (type 4)
  and closed cleanly.
- New rust/engine/src/listener.rs: JniListener (cached JavaVM, GlobalRef
  to the Kotlin listener, cached onEvent method ID, events sent from
  tokio threads via attach_current_thread) behind a small EventSink trait,
  so the engine core is testable without Java.
- JNI API: nativeCreate(): Long, nativeStart(h, cfg): String ("" = ok,
  else error text), nativeStop(h), nativeDestroy(h), nativeSnapshot(h):
  String, nativeSetListener(h, listener: EngineListener). Kotlin
  EngineListener interface + the five externs on NativeEngine; keep rule
  for EngineListener. nativeVersion() now returns "engine 0.0.4".
- Event type 4 (handler missing) added to the contract and to
  docs/ENGINE_API.md, with the lifecycle and server-shell sections.
- Tests: bind failure (port in use) gives a clear error naming the
  address and port; start/stop twice is safe (start is idempotent,
  stop is idempotent, start after stop rebinds); snapshot reflects the
  state; a SOCKS5 connection reports handler_missing and closes; a
  disabled protocol closes quietly with no event.
- FIX (same version, run #12 red): cargo test failed to compile with 5
  errors in engine.rs — `IpAddr` has no `is_private` (it is on
  `Ipv4Addr`/`Ipv6Addr`, so the check is now per family);
  `HashMap::new` is not const, so the `ENGINES` static is now a
  `LazyLock`; an `async move` block captures the whole `listener` Arc,
  so the task's clone is made before the spawn; and draining the
  connection map while adding its counters to the same struct is two
  mutable borrows, so the drain is collected first.
- FIX (same version, run #13 red): cargo test compiled, but 4 tests
  failed. Three panicked with "there is no reactor running":
  `TcpListener::from_std` needs a tokio runtime context, but `start()`
  runs on the caller's (plain) thread. The bind now returns a plain
  std listener, and the accept task converts it with `from_std` inside
  the runtime (on failure it reports an Error state); the engine no
  longer stores the listener — the task owns it, so `stop()` closes it
  by aborting the task. The fourth test failed because the
  disabled-protocol path closed the connection with the sniffed byte
  still unread, so the kernel sent RST and the client's read got
  "Connection reset by peer": the byte is now consumed right after
  sniffing, before the enabled check, so every close is a clean FIN.
- FIX (same version, run #14 red): cargo test passed (22 passed), but
  clippy `-D warnings` failed with 4 style lints: Engine needs a
  `Default` impl next to `new()`; `.map(|j| String::from(j))` is a
  redundant closure (use `.map(String::from)`); the nativeCreate match
  simplifies to `catch_unwind(AssertUnwindSafe(create_engine))
  .unwrap_or_default()` (the closure was redundant too). No behavior
  changed — the tests had already passed.
- FIX (same version, run #15 red): cargo test failed on one test,
  start_stop_twice_is_safe: "start after stop" failed to bind the same
  port — "Address already in use". Cause: `stop()` only aborted the
  accept task, and the socket stays bound until the task is actually
  dropped, so an immediate restart raced the teardown (it passed in
  run #14 by luck). `stop()` now waits for the accept task to finish
  unwinding (bounded by 2 s, skipped when called inside a runtime,
  where block_on would panic), so the socket is closed before `stop()`
  returns — which is also what the real app needs when the user toggles
  the proxy off and on quickly.
- FIX (same version, run #16 red): cargo test passed (22 passed) again,
  but clippy failed with one lint: `let _ = rt.block_on(...)` — block_on
  returns `()` (unit), so binding it is a `let_unit_value` error. Dropped
  the `let _ =`. One line.
- Run #17 GREEN (2026-11-12); v0.0.4 release APK is signed
  (ProxyShare-0.0.4-release.apk). Step 0.0.4 complete.

## 0.0.3 — Rust models, config, CIDR

- New rust/engine/src/model.rs: Protocol (Http, Socks4, Socks5), ConnInfo,
  EngineState (Stopped, Starting, Running, Error(String)), Event
  (StateChanged, ConnectionOpened, ConnectionClosed) with to_callback()
  mapping to (type, a, b) — 1 state, 2 opened, 3 closed. snapshot_text():
  first line "state total_up total_down active", then one
  "id client host port proto started_ms up down" line per connection.
- New rust/engine/src/config.rs: Config parsed from plain "key=value" text
  (hand-written parser; blank lines and # comments ignored; unknown keys
  ignored; last value wins; clear errors naming line, key and value).
  Keys: bind_addr, port, protocols, auth_user/auth_pass (both or neither),
  allowed_clients, private_only, max_connections, idle_timeout_s,
  connect_timeout_s. Defaults: 0.0.0.0:1080, all three protocols, no auth,
  no client list, private_only true, 64 connections, 300 s idle, 10 s
  connect.
- New rust/engine/src/cidr.rs: IPv4/IPv6 CIDR parse + contains (bare IP =
  single host; host bits masked at parse; other family never contains).
- docs/ENGINE_API.md now records the decided formats: the config key table,
  the snapshot text format, and the onEvent(type:Int, a:String, b:String)
  callback. JNI wiring for config/snapshot/events lands with the engine
  start; no networking yet.
- Unit tests for the config parser and Cidr (v4 + v6) and the model formats.

## 0.0.2 — Rust toolchain + JNI hello world

- Rust workspace at rust/; engine crate proxyshare_engine (cdylib) with jni
  0.21, tokio 1 (minimal features), socket2 0.5, libc 0.2. Release profile:
  opt-level z, LTO, codegen-units 1, strip, panic unwind.
  (Verified: latest stable is jni 0.22.4 and socket2 0.6.5; stayed on the
  pinned 0.21 / 0.5 because the step's JNI snippet targets the 0.21 API.)
- rust/.cargo/config.toml: 16 KB page-size link arg for aarch64 and x86_64
  (Android 15+).
- Gradle cargoBuild (debug, arm64-v8a + x86_64) and cargoBuildRelease (all
  ABIs, --release) wired before preDebugBuild / preReleaseBuild; readable
  error if cargo or cargo-ndk is missing. rust/target and jniLibs ignored.
- One JNI function: nativeVersion() returns "engine 0.0.2"; wrapped in
  catch_unwind so no panic crosses JNI (returns null on failure).
- Kotlin NativeEngine object (@JvmStatic) + version shown on the home screen
  ("Engine unavailable" if the library fails to load).
- proguard keeps native methods; docs/ENGINE_API.md records the contract.
- CI: Rust toolchain + cargo-ndk, cargo test, cargo clippy -D warnings,
  and APK .so checks.
- Run #10 GREEN (2026-11-12); v0.0.2 release APK is signed
  (ProxyShare-0.0.2-release.apk).
- FIX (same version): the 0.0.1 release APK shipped unsigned — the
  persistent signing config was only applied to the debug build type.
  The release build type now uses the same persistent key.
- FIX (same version, run #7 red): tasks.named("preDebugBuild") throws at
  configuration time — AGP registers preDebugBuild/preReleaseBuild only
  after the build script is evaluated. Replaced with tasks.configureEach,
  which fires when the task is registered.
- FIX (same version, run #8 red): clippy -D warnings failed on
  `unused_mut` — in jni 0.21, JNIEnv::new_string takes &self, so the
  `mut` on the env parameter from the spec snippet is unused. Dropped it.
  Also moved [profile.release] to the workspace root rust/Cargo.toml:
  cargo ignores profiles in a non-root member, so the engine's release
  profile (opt-level z, LTO, strip) was silently ignored.
- FIX (same version, run #9 red): the debug build compiled and packaged,
  then failed storing the configuration cache — `doFirst
  { checkCargoToolchain() }` makes the cargoBuild task action reference the
  build script object, which cannot be serialized. The cargo/cargo-ndk
  presence check now runs inside the shell command itself (same readable
  error messages); the task command line is plain data, so it is cache-safe.

## 0.0.1 — Kotlin skeleton

- Folders engine/ service/ data/ ui/theme ui/components ui/screens; no core/
  folder.
- Dependencies only: Compose BOM, material3, activity-compose,
  lifecycle-runtime-compose, kotlinx-coroutines-android, datastore-preferences.
- ProxyShareTheme: System/Light/Dark, dynamic color on Android 12+, fallback
  palette, large rounded shapes, edge-to-edge.
- MainActivity with a centered placeholder from strings; manifest
  supportsRtl=true, enableOnBackInvokedCallback=true; strings in English +
  Persian.
- Release: isMinifyEnabled + isShrinkResources, proguard-android-optimize.txt,
  locale filter en/fa, packaging excludes, Kotlin -Xno-param-assertions /
  -Xno-call-assertions / -Xno-receiver-assertions, -assumenosideeffects for
  Log.v/d in proguard-rules.pro.
- Gradle wrapper properties (9.5.0); CI generates the wrapper scripts + jar
  with `gradle wrapper` and verifies ./gradlew assembleDebug +
  ./gradlew assembleRelease.
- No proxy engine yet — that is a later step. No Rust crate in this step, so
  the cargo test / clippy checks apply from the step that adds rust/engine.
