//! Session log: the last 500 closed connections, owned by the engine.
//! The UI pulls new records with nativeSessionLog(sinceId) while the
//! Logs tab is visible. One line per record:
//! "log_id\tconn_id\tclient\thost\tport\tproto\tstarted_ms\tup\tdown\tblocked"
//! — the connection part is the snapshot line ConnInfo already
//! produces, prefixed with the log's own monotonically increasing id.

use std::collections::VecDeque;

use crate::model::ConnInfo;

/// How many closed connections the log keeps.
pub const MAX_ENTRIES: usize = 500;

/// A bounded log of closed connections. Pure: no globals, no clock.
pub struct SessionLog {
    entries: VecDeque<(u64, ConnInfo)>,
    next_id: u64,
}

impl SessionLog {
    pub fn new() -> Self {
        Self {
            entries: VecDeque::new(),
            next_id: 1,
        }
    }

    /// Append a closed connection. Returns its log id.
    pub fn push(&mut self, info: ConnInfo) -> u64 {
        let id = self.next_id;
        self.next_id += 1;
        self.entries.push_back((id, info));
        while self.entries.len() > MAX_ENTRIES {
            self.entries.pop_front();
        }
        id
    }

    /// Records newer than `since_id`, oldest first, one line each.
    /// Empty when nothing is newer.
    pub fn since(&self, since_id: u64) -> String {
        let mut out = String::new();
        for (id, info) in self.entries.iter().filter(|(id, _)| *id > since_id) {
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str(&format!("{id}\t{}", info.snapshot_line()));
        }
        out
    }

    /// Drop every record. Ids keep increasing: a cleared log never
    /// reuses an id the UI may already have seen.
    pub fn clear(&mut self) {
        self.entries.clear();
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Protocol;
    use std::net::IpAddr;
    use std::str::FromStr;

    fn conn(id: u64, host: &str) -> ConnInfo {
        ConnInfo {
            id,
            client_ip: IpAddr::from_str("192.168.1.5").expect("test IP is valid"),
            host: host.to_string(),
            port: 443,
            protocol: Protocol::Http,
            started_ms: 1_794_500_000_000,
            up: 100,
            down: 200,
            blocked: false,
        }
    }

    #[test]
    fn since_returns_only_newer_records_oldest_first() {
        let mut log = SessionLog::new();
        assert_eq!(log.push(conn(1, "a.example")), 1);
        assert_eq!(log.push(conn(2, "b.example")), 2);
        let all = log.since(0);
        assert_eq!(all.lines().count(), 2);
        assert!(all.starts_with("1\t1\t192.168.1.5\ta.example\t443\thttp\t"));
        let newer = log.since(1);
        assert_eq!(newer.lines().count(), 1);
        assert!(newer.starts_with("2\t2\t192.168.1.5\tb.example\t443\thttp\t"));
        assert_eq!(log.since(2), "");
    }

    #[test]
    fn caps_at_500_and_keeps_newest() {
        let mut log = SessionLog::new();
        for i in 1..=501u64 {
            log.push(conn(i, "example.com"));
        }
        assert_eq!(log.len(), 500);
        // The oldest remaining record is id 2, not 1.
        assert!(log.since(0).starts_with("2\t"));
    }

    #[test]
    fn clear_empties_but_ids_keep_increasing() {
        let mut log = SessionLog::new();
        log.push(conn(1, "a.example"));
        log.clear();
        assert_eq!(log.len(), 0);
        assert_eq!(log.since(0), "");
        assert_eq!(log.push(conn(2, "b.example")), 2);
    }
}
