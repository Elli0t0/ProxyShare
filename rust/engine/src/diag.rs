//! In-process diagnostics ring buffer: the last 300 engine log lines.
//!
//! The engine never logs traffic payloads (no MITM) or credentials:
//! only metadata (client IPs, target host:port, protocol, byte counts)
//! and lifecycle events. Lines are `ts_ms<TAB>LEVEL<TAB>message`, read
//! from Kotlin through the `nativeDiagLog` JNI getter (see
//! docs/ENGINE_API.md). The global buffer is a thin wrapper over
//! [`DiagLog`], which holds the logic and is what the tests exercise
//! (a global would make parallel tests interfere with each other).

use std::collections::VecDeque;
use std::sync::{LazyLock, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

/// Severity of a diagnostics line.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Level {
    Info,
    Warn,
    Error,
}

impl Level {
    pub fn as_str(self) -> &'static str {
        match self {
            Level::Info => "INFO",
            Level::Warn => "WARN",
            Level::Error => "ERROR",
        }
    }
}

/// One diagnostics line: unix time in ms, severity, message (no tabs or newlines).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DiagLine {
    pub ts_ms: u64,
    pub level: Level,
    pub msg: String,
}

/// How many lines the ring buffer keeps.
pub const MAX_LINES: usize = 300;

/// A bounded log: oldest lines drop off once full. Pure: no globals.
pub struct DiagLog {
    lines: VecDeque<DiagLine>,
}

impl DiagLog {
    pub fn new() -> Self {
        Self { lines: VecDeque::new() }
    }

    /// Append a line. Tabs and newlines in the message are replaced with
    /// spaces so one line stays one line in the wire format.
    pub fn log(&mut self, level: Level, msg: String) {
        let msg = msg.replace(['\t', '\n', '\r'], " ");
        if self.lines.len() >= MAX_LINES {
            self.lines.pop_front();
        }
        self.lines.push_back(DiagLine { ts_ms: now_ms(), level, msg });
    }

    pub fn len(&self) -> usize {
        self.lines.len()
    }

    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }

    /// All lines, oldest first, as the wire text: one
    /// `ts_ms<TAB>LEVEL<TAB>message` line each.
    pub fn snapshot_text(&self) -> String {
        let mut out = String::new();
        for line in &self.lines {
            out.push_str(&format!("{}\t{}\t{}\n", line.ts_ms, line.level.as_str(), line.msg));
        }
        out
    }
}

impl Default for DiagLog {
    fn default() -> Self {
        Self::new()
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

static LOG: LazyLock<Mutex<DiagLog>> = LazyLock::new(|| Mutex::new(DiagLog::new()));

/// Append a line to the global buffer.
pub fn log(level: Level, msg: String) {
    LOG.lock().unwrap_or_else(|e| e.into_inner()).log(level, msg);
}

/// Append an INFO line to the global buffer.
pub fn log_info(msg: String) {
    log(Level::Info, msg);
}

/// Append a WARN line to the global buffer.
pub fn log_warn(msg: String) {
    log(Level::Warn, msg);
}

/// Append an ERROR line to the global buffer.
pub fn log_error(msg: String) {
    log(Level::Error, msg);
}

/// All lines in the global buffer, oldest first, as the wire text.
pub fn snapshot_text() -> String {
    LOG.lock().unwrap_or_else(|e| e.into_inner()).snapshot_text()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lines_come_back_in_order() {
        let mut log = DiagLog::new();
        log.log(Level::Info, "first".to_string());
        log.log(Level::Warn, "second".to_string());
        log.log(Level::Error, "third".to_string());
        let text = log.snapshot_text();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(3, lines.len());
        assert!(lines[0].ends_with("\tINFO\tfirst"));
        assert!(lines[1].ends_with("\tWARN\tsecond"));
        assert!(lines[2].ends_with("\tERROR\tthird"));
    }

    #[test]
    fn buffer_caps_at_300_lines() {
        let mut log = DiagLog::new();
        for i in 0..(MAX_LINES + 1) {
            log.log(Level::Info, format!("line {i}"));
        }
        assert_eq!(MAX_LINES, log.len());
        let text = log.snapshot_text();
        assert!(!text.contains("line 0\n"));
        assert!(text.contains("line 1\n"));
        assert!(text.contains(&format!("line {MAX_LINES}\n")));
    }

    #[test]
    fn tabs_and_newlines_are_sanitized() {
        let mut log = DiagLog::new();
        log.log(Level::Info, "a\tb\nc".to_string());
        let text = log.snapshot_text();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(1, lines.len());
        assert!(lines[0].ends_with("\tINFO\ta b c"));
    }
}
