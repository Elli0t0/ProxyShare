//! Engine configuration, parsed from a plain "key=value" text.
//!
//! Format: one `key=value` per line. Blank lines and lines starting with
//! `#` are ignored. Keys are matched case-sensitively; unknown keys are
//! ignored. A key given twice wins with its last value. Every value is
//! validated, and a bad one is a clear error naming the line, the key and
//! the value. The full key table is in docs/ENGINE_API.md.

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr};
use std::str::FromStr;

use crate::cidr::Cidr;
use crate::model::Protocol;

/// Engine configuration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    /// Address the proxy listens on.
    pub bind_addr: IpAddr,
    /// Port the proxy listens on.
    pub port: u16,
    /// Optional HTTP-only port. When set (with or without socks_port),
    /// split mode replaces the single `port`.
    pub http_port: Option<u16>,
    /// Optional SOCKS4/SOCKS5 port. When set (with or without http_port),
    /// split mode replaces the single `port`.
    pub socks_port: Option<u16>,
    /// Protocols to accept.
    pub protocols: HashSet<Protocol>,
    /// Optional proxy auth user name (needs auth_pass too).
    pub auth_user: Option<String>,
    /// Optional proxy auth password (needs auth_user too).
    pub auth_pass: Option<String>,
    /// Client networks allowed in. Empty means: any client, subject to
    /// private_only.
    pub allowed_clients: Vec<Cidr>,
    /// Domains refused before any outbound connect. Empty means none
    /// blocked. Entries are exact hosts ("ads.example.com") or suffix
    /// wildcards ("*.example.com", which also matches example.com).
    pub blocked_domains: Vec<String>,
    /// When true, only private/link-local clients may connect.
    pub private_only: bool,
    /// Most connections at once.
    pub max_connections: u32,
    /// Seconds a connection may sit idle before it is closed.
    pub idle_timeout_s: u64,
    /// Seconds a connect to the target may take before it is aborted.
    pub connect_timeout_s: u64,
    /// Download speed limit in KB/s, total across all connections.
    /// 0 = unlimited.
    pub limit_down_kbps: u64,
    /// Upload speed limit in KB/s, total across all connections.
    /// 0 = unlimited.
    pub limit_up_kbps: u64,
    /// Refuse new connections while no VPN is active (the live VPN
    /// state arrives through set_vpn_active; false here means the
    /// flag is ignored). Existing connections are never closed.
    pub require_vpn: bool,
    /// Refuse targets that resolve only to private addresses (the
    /// phone's own network: router admin page, other LAN devices,
    /// localhost services). Default off: VPN-internal 10.x targets
    /// would break otherwise. Enforced in the Connector after DNS,
    /// never on the hostname.
    pub block_private: bool,
    /// Seconds of idleness (no connections at all) before the engine
    /// emits IdleStop. 0 = off. The Kotlin side stops the proxy on
    /// that event; the engine never stops itself.
    pub idle_stop_secs: u64,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_addr: IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            port: 1080,
            http_port: None,
            socks_port: None,
            protocols: HashSet::from([Protocol::Http, Protocol::Socks4, Protocol::Socks5]),
            auth_user: None,
            auth_pass: None,
            allowed_clients: Vec::new(),
            blocked_domains: Vec::new(),
            private_only: true,
            max_connections: 256,
            idle_timeout_s: 300,
            connect_timeout_s: 10,
            limit_down_kbps: 0,
            limit_up_kbps: 0,
            require_vpn: false,
            block_private: false,
            idle_stop_secs: 0,
        }
    }
}

impl Config {
    /// Parse a "key=value" text. Unknown keys are ignored; a bad value is
    /// an error naming the line, the key and the value.
    pub fn parse(text: &str) -> Result<Self, String> {
        let mut config = Config::default();
        for (index, raw_line) in text.lines().enumerate() {
            let line_no = index + 1;
            let line = raw_line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let (key, value) = line.split_once('=').ok_or_else(|| {
                format!("config line {line_no}: expected key=value, got '{line}'")
            })?;
            let key = key.trim();
            let value = value.trim();
            match key {
                "bind_addr" => config.bind_addr = parse_ip(value, line_no, key)?,
                "port" => config.port = parse_port(value, line_no, key)?,
                "http_port" => config.http_port = Some(parse_port(value, line_no, key)?),
                "socks_port" => config.socks_port = Some(parse_port(value, line_no, key)?),
                "protocols" => config.protocols = parse_protocols(value, line_no, key)?,
                "auth_user" => config.auth_user = opt_string(value),
                "auth_pass" => config.auth_pass = opt_string(value),
                "allowed_clients" => config.allowed_clients = parse_cidrs(value, line_no, key)?,
                "blocked_domains" => config.blocked_domains = parse_strings(value),
                "private_only" => config.private_only = parse_bool(value, line_no, key)?,
                "max_connections" => {
                    config.max_connections = parse_u32_min(value, line_no, key, 1)?
                }
                "idle_timeout_s" => config.idle_timeout_s = parse_u64(value, line_no, key)?,
                "connect_timeout_s" => {
                    config.connect_timeout_s = parse_u64(value, line_no, key)?
                }
                "limit_down_kbps" => {
                    config.limit_down_kbps = parse_u64(value, line_no, key)?
                }
                "limit_up_kbps" => {
                    config.limit_up_kbps = parse_u64(value, line_no, key)?
                }
                "require_vpn" => config.require_vpn = parse_bool(value, line_no, key)?,
                "block_private" => config.block_private = parse_bool(value, line_no, key)?,
                "idle_stop_secs" => {
                    config.idle_stop_secs = parse_u64(value, line_no, key)?
                }
                _ => { /* unknown keys are ignored */ }
            }
        }
        // Auth needs both halves or neither.
        if config.auth_user.is_some() != config.auth_pass.is_some() {
            return Err("config: auth_user and auth_pass must be set together".to_string());
        }
        Ok(config)
    }

    /// One-line config summary for the diagnostics log. Never includes
    /// the password (or the username): only whether auth is on.
    pub fn summary(&self) -> String {
        let port_str = |p: Option<u16>| match p {
            Some(p) => p.to_string(),
            None => "-".to_string(),
        };
        let ports = match (self.http_port, self.socks_port) {
            (None, None) => format!("port {}", self.port),
            (h, s) => format!("http_port {} socks_port {}", port_str(h), port_str(s)),
        };
        let mut protocols: Vec<&str> =
            self.protocols.iter().map(|p| p.as_str()).collect();
        protocols.sort_unstable();
        let limits = match (self.limit_down_kbps, self.limit_up_kbps) {
            (0, 0) => String::new(),
            (d, u) => format!(", limits down {d}/up {u} KB/s"),
        };
        let vpn = if self.require_vpn {
            ", require_vpn on"
        } else {
            ""
        };
        let block_private = if self.block_private {
            ", block_private on"
        } else {
            ""
        };
        let idle_stop = if self.idle_stop_secs > 0 {
            format!(", idle stop {}s", self.idle_stop_secs)
        } else {
            String::new()
        };
        format!(
            "bind {} {ports}, protocols {}, private_only {}, max {}, auth {}, {} blocked domains{limits}{vpn}{block_private}{idle_stop}",
            self.bind_addr,
            protocols.join("+"),
            self.private_only,
            self.max_connections,
            if self.auth_user.is_some() { "on" } else { "off" },
            self.blocked_domains.len(),
        )
    }
}

/// Cap the configured connection limit by the process fd budget: each
/// proxied connection holds 2 fds (client + upstream), and 128 fds are
/// kept for listeners, the runtime and everything else. Never 0, so a
/// tiny fd budget still allows a single connection.
pub fn effective_max_connections(configured: u32, nofile_soft: u64) -> u32 {
    let budget = nofile_soft.saturating_sub(128) / 2;
    let capped = (configured as u64).min(budget).max(1);
    u32::try_from(capped).unwrap_or(u32::MAX)
}

fn opt_string(value: &str) -> Option<String> {
    if value.is_empty() {
        None
    } else {
        Some(value.to_string())
    }
}

fn bad_value(value: &str, line_no: usize, key: &str, expected: &str) -> String {
    format!("config line {line_no}: invalid value for '{key}': '{value}' ({expected})")
}

fn parse_ip(value: &str, line_no: usize, key: &str) -> Result<IpAddr, String> {
    IpAddr::from_str(value).map_err(|_| bad_value(value, line_no, key, "expected an IP address"))
}

fn parse_port(value: &str, line_no: usize, key: &str) -> Result<u16, String> {
    let port = value
        .parse::<u16>()
        .map_err(|_| bad_value(value, line_no, key, "expected a port 1-65535"))?;
    if port == 0 {
        return Err(bad_value(value, line_no, key, "expected a port 1-65535"));
    }
    Ok(port)
}

fn parse_protocols(value: &str, line_no: usize, key: &str) -> Result<HashSet<Protocol>, String> {
    let mut protocols = HashSet::new();
    for token in value.split(',') {
        let token = token.trim();
        if token.is_empty() {
            continue;
        }
        let proto = Protocol::from_str(token)
            .map_err(|err| bad_value(value, line_no, key, &err))?;
        protocols.insert(proto);
    }
    if protocols.is_empty() {
        return Err(bad_value(
            value,
            line_no,
            key,
            "expected at least one of http, socks4, socks5",
        ));
    }
    Ok(protocols)
}

fn parse_cidrs(value: &str, line_no: usize, key: &str) -> Result<Vec<Cidr>, String> {
    let mut cidrs = Vec::new();
    for token in value.split(',') {
        let token = token.trim();
        if token.is_empty() {
            continue;
        }
        let cidr = Cidr::parse(token).map_err(|err| bad_value(value, line_no, key, &err))?;
        cidrs.push(cidr);
    }
    Ok(cidrs)
}

/// A comma list of plain strings: split, trim, drop empties.
/// Never an error: any non-empty token is kept as-is.
fn parse_strings(value: &str) -> Vec<String> {
    value
        .split(',')
        .map(|token| token.trim().to_string())
        .filter(|token| !token.is_empty())
        .collect()
}

/// True when `host` is blocked by the domain list: an exact entry, or
/// a "*.suffix" entry matching the suffix itself and anything under
/// it. Case-insensitive; a trailing dot is ignored. IP literals never
/// match, and neither does an empty host or an empty list.
pub fn domain_blocked(list: &[String], host: &str) -> bool {
    if IpAddr::from_str(host).is_ok() {
        return false;
    }
    let host = normalize_host(host);
    if host.is_empty() {
        return false;
    }
    list.iter().any(|entry| {
        let entry = normalize_host(entry);
        match entry.strip_prefix("*.") {
            Some(suffix) => host == suffix || host.ends_with(&format!(".{suffix}")),
            None => host == entry,
        }
    })
}

fn normalize_host(host: &str) -> String {
    host.trim().trim_end_matches('.').to_ascii_lowercase()
}

/// True when `ip` is a private address: the phone's own network.
/// IPv4: private, loopback, link-local, unspecified, broadcast.
/// IPv6: loopback, unspecified, unique-local (fc00::/7), link-local
/// (fe80::/10), and IPv4-mapped addresses judged by their IPv4 half.
pub fn is_private_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            v4.is_private() || v4.is_loopback() || v4.is_link_local()
                || v4.is_unspecified() || v4.is_broadcast()
        }
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_private_ip(IpAddr::V4(v4));
            }
            v6.is_loopback()
                || v6.is_unspecified()
                || (v6.segments()[0] & 0xfe00) == 0xfc00 // unique local fc00::/7
                || (v6.segments()[0] & 0xffc0) == 0xfe80 // link-local fe80::/10
        }
    }
}

fn parse_bool(value: &str, line_no: usize, key: &str) -> Result<bool, String> {
    match value.to_ascii_lowercase().as_str() {
        "true" => Ok(true),
        "false" => Ok(false),
        _ => Err(bad_value(value, line_no, key, "expected true or false")),
    }
}

fn parse_u32_min(value: &str, line_no: usize, key: &str, min: u32) -> Result<u32, String> {
    let parsed = value
        .parse::<u32>()
        .map_err(|_| bad_value(value, line_no, key, &format!("expected a whole number >= {min}")))?;
    if parsed < min {
        return Err(bad_value(
            value,
            line_no,
            key,
            &format!("expected a whole number >= {min}"),
        ));
    }
    Ok(parsed)
}

fn parse_u64(value: &str, line_no: usize, key: &str) -> Result<u64, String> {
    value
        .parse::<u64>()
        .map_err(|_| bad_value(value, line_no, key, "expected a whole number of seconds >= 0"))
}

#[cfg(test)]
mod tests {
    use super::*;

    const FULL: &str = "\
# a comment
bind_addr = 127.0.0.1
port = 8080
protocols = http, socks5
auth_user = alice
auth_pass = secret
allowed_clients = 192.168.1.0/24, ::1/128
blocked_domains = ads.example.com, *.tracker.example
private_only = false
max_connections = 10
idle_timeout_s = 60
connect_timeout_s = 5
";

    #[test]
    fn full_config_parses() {
        let config = Config::parse(FULL).expect("valid config");
        assert_eq!(config.bind_addr, IpAddr::from_str("127.0.0.1").expect("test IP"));
        assert_eq!(config.port, 8080);
        assert_eq!(
            config.protocols,
            HashSet::from([Protocol::Http, Protocol::Socks5])
        );
        assert_eq!(config.auth_user.as_deref(), Some("alice"));
        assert_eq!(config.auth_pass.as_deref(), Some("secret"));
        assert_eq!(config.allowed_clients.len(), 2);
        assert_eq!(config.allowed_clients[0].to_string(), "192.168.1.0/24");
        assert_eq!(
            config.blocked_domains,
            vec!["ads.example.com".to_string(), "*.tracker.example".to_string()]
        );
        assert!(!config.private_only);
        assert_eq!(config.max_connections, 10);
        assert_eq!(config.idle_timeout_s, 60);
        assert_eq!(config.connect_timeout_s, 5);
    }

    #[test]
    fn empty_text_gives_defaults() {
        let config = Config::parse("").expect("empty config parses");
        assert_eq!(config, Config::default());
    }

    #[test]
    fn split_ports_parse() {
        let config = Config::parse("http_port = 8080\nsocks_port = 1080")
            .expect("split ports parse");
        assert_eq!(config.http_port, Some(8080));
        assert_eq!(config.socks_port, Some(1080));
        let config = Config::parse("").expect("empty config parses");
        assert_eq!(config.http_port, None);
        assert_eq!(config.socks_port, None);
        let err = Config::parse("http_port = abc").expect_err("bad http_port is an error");
        assert!(err.contains("'http_port'"), "{err}");
        let err = Config::parse("socks_port = 0").expect_err("socks_port 0 is an error");
        assert!(err.contains("1-65535"), "{err}");
        let config = Config::parse("http_port = 8080\nhttp_port = 9090")
            .expect("last http_port wins");
        assert_eq!(config.http_port, Some(9090));
    }

    #[test]
    fn unknown_keys_ignored_and_last_value_wins() {
        let config = Config::parse("future_key = 1\nport = 1080\nport = 9090\n")
            .expect("unknown key ignored");
        assert_eq!(config.port, 9090);
    }

    #[test]
    fn bad_values_are_clear_errors() {
        let err = Config::parse("port = abc").expect_err("bad port is an error");
        assert!(err.contains("'port'"), "{err}");
        let err = Config::parse("port = 0").expect_err("port 0 is an error");
        assert!(err.contains("1-65535"), "{err}");
        let err = Config::parse("no equals sign").expect_err("missing = is an error");
        assert!(err.contains("key=value"), "{err}");
        let err = Config::parse("protocols = ftp").expect_err("bad protocol is an error");
        assert!(err.contains("'protocols'"), "{err}");
        let err = Config::parse("protocols = ").expect_err("empty protocols is an error");
        assert!(err.contains("at least one"), "{err}");
        let err = Config::parse("allowed_clients = 192.168.1.0/33")
            .expect_err("bad CIDR is an error");
        assert!(err.contains("'allowed_clients'"), "{err}");
        let err = Config::parse("private_only = yes").expect_err("bad bool is an error");
        assert!(err.contains("true or false"), "{err}");
        let err = Config::parse("max_connections = 0").expect_err("max 0 is an error");
        assert!(err.contains(">= 1"), "{err}");
    }

    #[test]
    fn blocked_domains_parse() {
        let config =
            Config::parse("blocked_domains = ads.example.com , , *.tracker.example ")
                .expect("blocked domains parse");
        assert_eq!(
            config.blocked_domains,
            vec!["ads.example.com".to_string(), "*.tracker.example".to_string()]
        );
        let config = Config::parse("blocked_domains = ").expect("empty value parses");
        assert!(config.blocked_domains.is_empty());
        let config = Config::parse("").expect("empty config parses");
        assert!(config.blocked_domains.is_empty());
    }

    #[test]
    fn domain_blocked_exact_and_wildcard() {
        let list = vec![
            "ads.example.com".to_string(),
            "*.tracker.example".to_string(),
        ];
        assert!(domain_blocked(&list, "ads.example.com"));
        assert!(domain_blocked(&list, "ADS.EXAMPLE.COM"));
        assert!(domain_blocked(&list, "ads.example.com."));
        assert!(domain_blocked(&list, "a.tracker.example"));
        assert!(domain_blocked(&list, "tracker.example"));
        assert!(domain_blocked(&list, "a.b.tracker.example"));
        assert!(!domain_blocked(&list, "sub.ads.example.com"));
        assert!(!domain_blocked(&list, "adtracker.example"));
        assert!(!domain_blocked(&list, "notexample.com"));
        assert!(!domain_blocked(&list, "example.com"));
    }

    #[test]
    fn domain_blocked_wildcard_matches_base_domain() {
        let list = vec!["*.example.com".to_string()];
        assert!(domain_blocked(&list, "example.com"));
        assert!(domain_blocked(&list, "a.example.com"));
        assert!(!domain_blocked(&list, "notexample.com"));
        assert!(!domain_blocked(&list, "badexample.com"));
    }

    #[test]
    fn domain_blocked_never_matches_ips_or_empty() {
        let list = vec!["*.example.com".to_string(), "1.2.3.4".to_string()];
        assert!(!domain_blocked(&list, "1.2.3.4"));
        assert!(!domain_blocked(&list, "2001:db8::1"));
        assert!(!domain_blocked(&list, "::1"));
        assert!(!domain_blocked(&list, ""));
        assert!(!domain_blocked(&[], "example.com"));
        assert!(!domain_blocked(&[], "1.2.3.4"));
    }

    #[test]
    fn summary_never_includes_credentials() {
        let config = Config::parse(
            "auth_user = alice\nauth_pass = s3cret\nblocked_domains = ads.example.com",
        )
        .expect("config parses");
        let summary = config.summary();
        assert!(summary.contains("auth on"));
        assert!(summary.contains("1 blocked domains"));
        assert!(!summary.contains("alice"));
        assert!(!summary.contains("s3cret"));
    }

    #[test]
    fn summary_shows_ports_and_protocols() {
        let config = Config::parse("http_port = 8080\nsocks_port = 1080").expect("config parses");
        let summary = config.summary();
        assert!(summary.contains("bind 0.0.0.0"));
        assert!(summary.contains("http_port 8080"));
        assert!(summary.contains("socks_port 1080"));
        assert!(summary.contains("protocols http+socks4+socks5"));
        assert!(summary.contains("auth off"));
        let single = Config::default().summary();
        assert!(single.contains("port 1080"));
        assert!(!single.contains("http_port"));
    }

    #[test]
    fn limits_parse_and_show_in_summary() {
        let config = Config::parse("limit_down_kbps = 512\nlimit_up_kbps = 128")
            .expect("limits parse");
        assert_eq!(config.limit_down_kbps, 512);
        assert_eq!(config.limit_up_kbps, 128);
        assert!(config
            .summary()
            .contains("limits down 512/up 128 KB/s"));
        assert!(!Config::default().summary().contains("limits"));
        assert!(Config::parse("limit_down_kbps = abc").is_err());
    }

    #[test]
    fn require_vpn_parses_and_shows_in_summary() {
        assert!(!Config::default().require_vpn);
        assert!(!Config::default().summary().contains("require_vpn"));
        let config = Config::parse("require_vpn = true").expect("require_vpn parse");
        assert!(config.require_vpn);
        assert!(config.summary().contains("require_vpn on"));
        assert!(Config::parse("require_vpn = maybe").is_err());
    }

    #[test]
    fn block_private_parses_and_shows_in_summary() {
        assert!(!Config::default().block_private);
        assert!(!Config::default().summary().contains("block_private"));
        let config = Config::parse("block_private = true").expect("block_private parse");
        assert!(config.block_private);
        assert!(config.summary().contains("block_private on"));
        assert!(Config::parse("block_private = maybe").is_err());
    }

    #[test]
    fn is_private_ip_table() {
        for ip in [
            "10.0.0.1",
            "172.16.5.5",
            "192.168.1.1",
            "127.0.0.1",
            "169.254.1.1",
            "::1",
            "fd00::1",
            "fe80::1",
            "::ffff:192.168.1.1",
        ] {
            let addr = IpAddr::from_str(ip).expect("test IP");
            assert!(is_private_ip(addr), "{ip} is private");
        }
        for ip in ["8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "172.32.0.1"] {
            let addr = IpAddr::from_str(ip).expect("test IP");
            assert!(!is_private_ip(addr), "{ip} is not private");
        }
    }

    #[test]
    fn default_max_connections_is_256() {
        assert_eq!(Config::default().max_connections, 256);
    }

    #[test]
    fn effective_max_uses_configured_when_fds_allow() {
        assert_eq!(effective_max_connections(256, 65_536), 256);
        assert_eq!(effective_max_connections(256, 1024), 256);
        assert_eq!(effective_max_connections(64, 256), 64);
    }

    #[test]
    fn effective_max_caps_by_fd_budget() {
        // (1024 - 128) / 2 = 448 fds left for connections.
        assert_eq!(effective_max_connections(2000, 1024), 448);
        assert_eq!(effective_max_connections(256, 256), 64);
    }

    #[test]
    fn effective_max_never_zero() {
        assert_eq!(effective_max_connections(64, 128), 1);
        assert_eq!(effective_max_connections(64, 0), 1);
    }

    #[test]
    fn auth_needs_both_halves() {
        assert!(Config::parse("auth_user = alice").is_err());
        assert!(Config::parse("auth_pass = secret").is_err());
        assert!(Config::parse("auth_user = \nauth_pass = secret").is_err());
        let config = Config::parse("auth_user = alice\nauth_pass = secret")
            .expect("both halves parse");
        assert_eq!(config.auth_user.as_deref(), Some("alice"));
    }
}
