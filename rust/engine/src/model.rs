//! Engine data model: protocols, connection records, engine state, events.
//!
//! No networking lives here — these are the plain types the engine and the
//! Kotlin side agree on. Their text forms (config values, snapshot lines,
//! event callback arguments) are fixed in docs/ENGINE_API.md.

use std::fmt;
use std::net::IpAddr;
use std::str::FromStr;

/// A proxy protocol the engine speaks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Protocol {
    Http,
    Socks4,
    Socks5,
}

impl Protocol {
    /// The config/snapshot token: "http", "socks4", "socks5".
    pub fn as_str(&self) -> &'static str {
        match self {
            Protocol::Http => "http",
            Protocol::Socks4 => "socks4",
            Protocol::Socks5 => "socks5",
        }
    }
}

impl FromStr for Protocol {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let lower = s.trim().to_ascii_lowercase();
        match lower.as_str() {
            "http" => Ok(Protocol::Http),
            "socks4" => Ok(Protocol::Socks4),
            "socks5" => Ok(Protocol::Socks5),
            other => Err(format!(
                "unknown protocol '{other}' (expected http, socks4 or socks5)"
            )),
        }
    }
}

impl fmt::Display for Protocol {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// One connection the engine is handling (or handled).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConnInfo {
    /// Engine-assigned id, unique while the engine runs.
    pub id: u64,
    /// The client's address.
    pub client_ip: IpAddr,
    /// The target host the client asked for.
    pub host: String,
    /// The target port the client asked for.
    pub port: u16,
    /// Which protocol this connection speaks.
    pub protocol: Protocol,
    /// When the connection opened, unix time in milliseconds.
    pub started_ms: u64,
    /// Bytes read from the client so far.
    pub up: u64,
    /// Bytes written to the client so far.
    pub down: u64,
}

impl ConnInfo {
    /// One snapshot line: "id\tclient\thost\tport\tproto\tstarted_ms\tup\tdown".
    pub fn snapshot_line(&self) -> String {
        format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            self.id, self.client_ip, self.host, self.port, self.protocol, self.started_ms,
            self.up, self.down
        )
    }
}

/// What the engine is doing right now.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineState {
    Stopped,
    Starting,
    Running,
    Error(String),
}

impl EngineState {
    /// The snapshot/event token: "stopped", "starting", "running" or "error".
    /// The error message itself is not in the token; it travels in the
    /// event callback's second argument.
    pub fn as_str(&self) -> &'static str {
        match self {
            EngineState::Stopped => "stopped",
            EngineState::Starting => "starting",
            EngineState::Running => "running",
            EngineState::Error(_) => "error",
        }
    }
}

/// Something the Kotlin side is told about through the onEvent callback.
/// The callback takes (type: Int, a: String, b: String); `to_callback`
/// is the fixed mapping, see docs/ENGINE_API.md.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// The engine state changed. a = new state name, b = error message or "".
    StateChanged(EngineState),
    /// A connection opened. a = connection id, b = its snapshot line.
    ConnectionOpened(ConnInfo),
    /// A connection closed. a = connection id, b = its final snapshot line.
    ConnectionClosed(ConnInfo),
}

impl Event {
    /// Callback type numbers: 1 = state changed, 2 = connection opened,
    /// 3 = connection closed. They are part of the contract: do not renumber.
    pub fn to_callback(&self) -> (i32, String, String) {
        match self {
            Event::StateChanged(state) => {
                let message = match state {
                    EngineState::Error(msg) => msg.clone(),
                    _ => String::new(),
                };
                (1, state.as_str().to_string(), message)
            }
            Event::ConnectionOpened(info) => (2, info.id.to_string(), info.snapshot_line()),
            Event::ConnectionClosed(info) => (3, info.id.to_string(), info.snapshot_line()),
        }
    }
}

/// The whole-engine snapshot text: first line
/// "state\ttotal_up\ttotal_down\tactive", then one line per connection.
/// See docs/ENGINE_API.md.
pub fn snapshot_text(
    state: &EngineState,
    total_up: u64,
    total_down: u64,
    conns: &[ConnInfo],
) -> String {
    let mut text = format!(
        "{}\t{}\t{}\t{}",
        state.as_str(),
        total_up,
        total_down,
        conns.len()
    );
    for conn in conns {
        text.push('\n');
        text.push_str(&conn.snapshot_line());
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::IpAddr;
    use std::str::FromStr;

    fn sample_conn() -> ConnInfo {
        ConnInfo {
            id: 1,
            client_ip: IpAddr::from_str("192.168.1.5").expect("test IP is valid"),
            host: "example.com".to_string(),
            port: 443,
            protocol: Protocol::Http,
            started_ms: 1_794_500_000_000,
            up: 1024,
            down: 2048,
        }
    }

    #[test]
    fn protocol_round_trip() {
        for (token, proto) in [
            ("http", Protocol::Http),
            ("socks4", Protocol::Socks4),
            ("socks5", Protocol::Socks5),
        ] {
            assert_eq!(Protocol::from_str(token).expect("known token"), proto);
            assert_eq!(proto.as_str(), token);
            assert_eq!(proto.to_string(), token);
        }
        assert!(Protocol::from_str("ftp").is_err());
        assert_eq!(
            Protocol::from_str("SOCKS5").expect("case-insensitive"),
            Protocol::Socks5
        );
    }

    #[test]
    fn conn_snapshot_line() {
        assert_eq!(
            sample_conn().snapshot_line(),
            "1\t192.168.1.5\texample.com\t443\thttp\t1794500000000\t1024\t2048"
        );
    }

    #[test]
    fn snapshot_text_shape() {
        assert_eq!(
            snapshot_text(&EngineState::Stopped, 0, 0, &[]),
            "stopped\t0\t0\t0"
        );
        let conn = sample_conn();
        assert_eq!(
            snapshot_text(&EngineState::Running, 1024, 2048, &[conn]),
            "running\t1024\t2048\t1\n1\t192.168.1.5\texample.com\t443\thttp\t1794500000000\t1024\t2048"
        );
    }

    #[test]
    fn event_callbacks() {
        let (t, a, b) = Event::StateChanged(EngineState::Running).to_callback();
        assert_eq!((t, a.as_str(), b.as_str()), (1, "running", ""));
        let (t, a, b) =
            Event::StateChanged(EngineState::Error("bind failed".to_string())).to_callback();
        assert_eq!((t, a.as_str(), b.as_str()), (1, "error", "bind failed"));
        let conn = sample_conn();
        let (t, a, b) = Event::ConnectionOpened(conn.clone()).to_callback();
        assert_eq!(t, 2);
        assert_eq!(a, "1");
        assert_eq!(b, conn.snapshot_line());
        let (t, a, b) = Event::ConnectionClosed(conn.clone()).to_callback();
        assert_eq!(t, 3);
        assert_eq!(a, "1");
        assert_eq!(b, conn.snapshot_line());
    }
}
