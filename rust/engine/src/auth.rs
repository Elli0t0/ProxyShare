//! Proxy authentication: constant-time credential comparison and a
//! small base64 decoder (no crates).
//!
//! When the config has both `auth_user` and `auth_pass`, every protocol
//! must authenticate. SOCKS5 uses username/password (method 0x02, RFC
//! 1929), HTTP uses `Proxy-Authorization: Basic`, and SOCKS4 has no
//! auth so it is refused. Attempted passwords are never logged or
//! emitted: failures carry only a generic message.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::config::Config;

/// True when the config requires authentication (both halves set).
pub fn required(config: &Config) -> bool {
    config.auth_user.is_some() && config.auth_pass.is_some()
}

/// Check a username/password pair against the config, in constant time.
/// False when auth is not required or either half differs.
pub fn check(config: &Config, user: &[u8], pass: &[u8]) -> bool {
    match (&config.auth_user, &config.auth_pass) {
        (Some(expected_user), Some(expected_pass)) => {
            ct_eq(user, expected_user.as_bytes()) && ct_eq(pass, expected_pass.as_bytes())
        }
        _ => false,
    }
}

/// Check a `Proxy-Authorization` header value ("Basic <base64>") against
/// the config. False for anything else, including a missing header.
pub fn check_basic(config: &Config, header_value: &str) -> bool {
    let mut parts = header_value.split_whitespace();
    let scheme = parts.next().unwrap_or("");
    let token = parts.next().unwrap_or("");
    if !scheme.eq_ignore_ascii_case("basic") || token.is_empty() {
        return false;
    }
    let Some(decoded) = base64_decode(token) else {
        return false;
    };
    let Some(colon) = decoded.iter().position(|&b| b == b':') else {
        return false;
    };
    check(config, &decoded[..colon], &decoded[colon + 1..])
}

/// Constant-time comparison (still leaks the length, which is fine here).
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    let mut diff: usize = a.len() ^ b.len();
    for i in 0..a.len().max(b.len()) {
        let x = a.get(i).copied().unwrap_or(0);
        let y = b.get(i).copied().unwrap_or(0);
        diff |= usize::from(x ^ y);
    }
    diff == 0
}

pub fn base64_decode(input: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(input.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0u32);
    for c in input.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            _ => return None,
        };
        acc = (acc << 6) | u32::from(v);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

/// Failures count for 60 s; 5 of them block the IP for 5 minutes.
const WINDOW: Duration = Duration::from_secs(60);
const BLOCK_FOR: Duration = Duration::from_secs(300);
const MAX_FAILS: usize = 5;

#[derive(Default)]
struct Entry {
    fails: Vec<Instant>,
    blocked_until: Option<Instant>,
}

/// Failed-auth throttle: one per engine. The time is passed in so
/// tests can use fake Instants and never sleep.
#[derive(Default)]
pub struct AuthGuard {
    state: Mutex<HashMap<IpAddr, Entry>>,
}

impl AuthGuard {
    pub fn is_blocked(&self, ip: IpAddr, now: Instant) -> bool {
        let s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        s.get(&ip).and_then(|e| e.blocked_until).is_some_and(|t| now < t)
    }

    /// Returns true when this failure starts a block.
    pub fn record_failure(&self, ip: IpAddr, now: Instant) -> bool {
        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let e = s.entry(ip).or_default();
        e.fails.retain(|t| now.duration_since(*t) < WINDOW);
        e.fails.push(now);
        if e.fails.len() >= MAX_FAILS {
            e.blocked_until = Some(now + BLOCK_FOR);
            e.fails.clear();
            return true;
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_decode_decodes_user_pass() {
        assert_eq!(
            base64_decode("dXNlcjpwYXNz"),
            Some(b"user:pass".to_vec())
        );
        assert_eq!(base64_decode("dXNlcjp3cm9uZw=="), Some(b"user:wrong".to_vec()));
        assert_eq!(base64_decode("!!!"), None);
        assert_eq!(base64_decode(""), Some(Vec::new()));
    }

    #[test]
    fn ct_eq_compares() {
        assert!(ct_eq(b"user", b"user"));
        assert!(!ct_eq(b"user", b"usex"));
        assert!(!ct_eq(b"user", b"use"));
        assert!(!ct_eq(b"user", b"users"));
        assert!(ct_eq(b"", b""));
    }

    #[test]
    fn guard_blocks_after_five_failures() {
        let guard = AuthGuard::default();
        let ip = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1));
        let t0 = Instant::now();
        for i in 0..4 {
            let now = t0 + Duration::from_secs(i);
            assert!(!guard.record_failure(ip, now));
            assert!(!guard.is_blocked(ip, now));
        }
        // The 5th failure starts the block.
        let now = t0 + Duration::from_secs(4);
        assert!(guard.record_failure(ip, now));
        assert!(guard.is_blocked(ip, now));
    }

    #[test]
    fn guard_block_ends_after_five_minutes() {
        let guard = AuthGuard::default();
        let ip = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1));
        let t0 = Instant::now();
        for i in 0..5 {
            guard.record_failure(ip, t0 + Duration::from_secs(i));
        }
        // The block started at the 5th failure (t0 + 4 s) and lasts 300 s.
        assert!(guard.is_blocked(ip, t0 + Duration::from_secs(303)));
        assert!(!guard.is_blocked(ip, t0 + Duration::from_secs(304)));
    }

    #[test]
    fn guard_old_failures_do_not_count() {
        let guard = AuthGuard::default();
        let ip = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1));
        let t0 = Instant::now();
        for i in 0..4 {
            guard.record_failure(ip, t0 + Duration::from_secs(i));
        }
        // 61 s later the failures from t0 and t0 + 1 s are outside the
        // 60 s window, so only 3 count and no block starts.
        let now = t0 + Duration::from_secs(61);
        assert!(!guard.record_failure(ip, now));
        assert!(!guard.is_blocked(ip, now));
    }

    #[test]
    fn check_basic_accepts_only_the_configured_pair() {
        let config = Config {
            auth_user: Some("user".to_string()),
            auth_pass: Some("pass".to_string()),
            ..Config::default()
        };
        assert!(check_basic(&config, "Basic dXNlcjpwYXNz"));
        assert!(!check_basic(&config, "Basic dXNlcjp3cm9uZw=="));
        assert!(!check_basic(&config, "Basic !!!"));
        assert!(!check_basic(&config, "Bearer dXNlcjpwYXNz"));
        assert!(!check_basic(&config, ""));
        assert!(!check_basic(&Config::default(), "Basic dXNlcjpwYXNz"));
    }
}
