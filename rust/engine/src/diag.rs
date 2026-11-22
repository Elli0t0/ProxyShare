//! In-process diagnostics ring buffer: the last 1000 engine log lines.
//!
//! The engine never logs traffic payloads (no MITM) or credentials:
//! only metadata (client IPs, target host:port, protocol, byte counts)
//! and lifecycle events. Lines are `ts_ms<TAB>LEVEL<TAB>message<TAB>count`,
//! read from Kotlin through the `nativeDiagLog` JNI getter (see
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

/// One diagnostics line: unix time in ms, severity, message (no tabs or newlines),
/// and how many times this exact line repeated in a row (within 60 s).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DiagLine {
    pub ts_ms: u64,
    pub level: Level,
    pub msg: String,
    pub count: u32,
}

/// How many lines the ring buffer keeps.
pub const MAX_LINES: usize = 1000;

/// A bounded log: oldest lines drop off once full. Pure: no globals.
pub struct DiagLog {
    lines: VecDeque<DiagLine>,
}

impl DiagLog {
    pub fn new() -> Self {
        Self { lines: VecDeque::new() }
    }

    /// Append a line. Tabs and newlines in the message are replaced with
    /// spaces so one line stays one line in the wire format. A line that
    /// repeats the last one (same level and message, within 60 s) bumps
    /// the last line's count and timestamp instead of pushing a new line.
    pub fn log(&mut self, level: Level, msg: String) {
        let msg = msg.replace(['\t', '\n', '\r'], " ");
        let ts_ms = now_ms();
        if let Some(last) = self.lines.back_mut() {
            if last.level == level && last.msg == msg && ts_ms.saturating_sub(last.ts_ms) < 60_000 {
                last.count += 1;
                last.ts_ms = ts_ms;
                return;
            }
        }
        if self.lines.len() >= MAX_LINES {
            self.lines.pop_front();
        }
        self.lines.push_back(DiagLine { ts_ms, level, msg, count: 1 });
    }

    pub fn len(&self) -> usize {
        self.lines.len()
    }

    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }

    /// All lines, oldest first, as the wire text: one
    /// `ts_ms<TAB>LEVEL<TAB>message<TAB>count` line each.
    pub fn snapshot_text(&self) -> String {
        let mut out = String::new();
        for line in &self.lines {
            out.push_str(&format!("{}\t{}\t{}\t{}\n", line.ts_ms, line.level.as_str(), line.msg, line.count));
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

/// Clear the global buffer (Logs screen "Clear").
pub fn clear() {
    LOG.lock().unwrap_or_else(|e| e.into_inner()).lines.clear();
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
        assert!(lines[0].ends_with("\tINFO\tfirst\t1"));
        assert!(lines[1].ends_with("\tWARN\tsecond\t1"));
        assert!(lines[2].ends_with("\tERROR\tthird\t1"));
    }

    #[test]
    fn buffer_caps_at_1000_lines() {
        let mut log = DiagLog::new();
        for i in 0..(MAX_LINES + 1) {
            log.log(Level::Info, format!("line {i}"));
        }
        assert_eq!(MAX_LINES, log.len());
        let text = log.snapshot_text();
        // The wire format now ends each line with "\t<count>\n", so the
        // message is followed by a tab, not a newline.
        assert!(!text.contains("line 0\t"));
        assert!(text.contains("line 1\t"));
        assert!(text.contains(&format!("line {MAX_LINES}\t")));
    }

    #[test]
    fn tabs_and_newlines_are_sanitized() {
        let mut log = DiagLog::new();
        log.log(Level::Info, "a\tb\nc".to_string());
        let text = log.snapshot_text();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(1, lines.len());
        assert!(lines[0].ends_with("\tINFO\ta b c\t1"));
    }

    #[test]
    fn repeats_collapse_into_one_line_with_a_count() {
        let mut log = DiagLog::new();
        log.log(Level::Warn, "same".to_string());
        log.log(Level::Warn, "same".to_string());
        log.log(Level::Warn, "same".to_string());
        assert_eq!(1, log.len());
        let text = log.snapshot_text();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(1, lines.len());
        assert!(lines[0].ends_with("\tWARN\tsame\t3"));
    }

    #[test]
    fn different_lines_do_not_collapse() {
        let mut log = DiagLog::new();
        log.log(Level::Warn, "same".to_string());
        log.log(Level::Warn, "other".to_string());
        log.log(Level::Error, "same".to_string());
        assert_eq!(3, log.len());
        let text = log.snapshot_text();
        let lines: Vec<&str> = text.lines().collect();
        assert!(lines[0].ends_with("\tWARN\tsame\t1"));
        assert!(lines[1].ends_with("\tWARN\tother\t1"));
        assert!(lines[2].ends_with("\tERROR\tsame\t1"));
    }
}
