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
    /// When true, only private/link-local clients may connect.
    pub private_only: bool,
    /// Most connections at once.
    pub max_connections: u32,
    /// Seconds a connection may sit idle before it is closed.
    pub idle_timeout_s: u64,
    /// Seconds a connect to the target may take before it is aborted.
    pub connect_timeout_s: u64,
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
            private_only: true,
            max_connections: 64,
            idle_timeout_s: 300,
            connect_timeout_s: 10,
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
                "private_only" => config.private_only = parse_bool(value, line_no, key)?,
                "max_connections" => {
                    config.max_connections = parse_u32_min(value, line_no, key, 1)?
                }
                "idle_timeout_s" => config.idle_timeout_s = parse_u64(value, line_no, key)?,
                "connect_timeout_s" => {
                    config.connect_timeout_s = parse_u64(value, line_no, key)?
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
    fn auth_needs_both_halves() {
        assert!(Config::parse("auth_user = alice").is_err());
        assert!(Config::parse("auth_pass = secret").is_err());
        assert!(Config::parse("auth_user = \nauth_pass = secret").is_err());
        let config = Config::parse("auth_user = alice\nauth_pass = secret")
            .expect("both halves parse");
        assert_eq!(config.auth_user.as_deref(), Some("alice"));
    }
}
