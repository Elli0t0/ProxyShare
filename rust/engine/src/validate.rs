//! Single validation source for settings text (0.5.2).
//!
//! Kotlin used to validate CIDR/domain/port itself while Rust validated
//! the same values again in `cidr`/`config`, and the two could drift.
//! Now Kotlin calls `nativeValidate` (lib.rs) and this module is the
//! only implementation. It returns "" when valid, or a stable error
//! code: "empty", "has_scheme", "has_path", "bad_cidr", "bad_port",
//! "bad_domain", "bad_kind" (unknown kind, a caller bug). Kotlin maps
//! each code to a string resource and holds no validation logic.

use crate::cidr::Cidr;

/// What kind of value to validate (the `kind` of `nativeValidate`).
pub const KIND_CIDR: i32 = 0;
pub const KIND_DOMAIN: i32 = 1;
pub const KIND_PORT: i32 = 2;

/// Validate `value` as `kind`; "" when valid, else a stable code.
pub fn validate(kind: i32, value: &str) -> &'static str {
    match kind {
        KIND_CIDR => validate_cidr(value),
        KIND_DOMAIN => validate_domain(value),
        KIND_PORT => validate_port(value),
        _ => "bad_kind",
    }
}

/// "192.168.1.0/24", "2001:db8::/32", or a bare IP (single host).
/// The CIDR parser is the authority, so IPv6 works too (the old
/// Kotlin validator only knew IPv4).
fn validate_cidr(value: &str) -> &'static str {
    let text = value.trim();
    if text.is_empty() {
        return "empty";
    }
    if Cidr::parse(text).is_ok() {
        ""
    } else {
        "bad_cidr"
    }
}

/// A domain to block: "ads.example.com" or "*.example.com". Uppercase
/// and a trailing dot are fine (matching is case-insensitive and
/// dot-tolerant, see `domain_blocked`). A single label ("example") is
/// accepted, as the old Kotlin validator accepted it.
fn validate_domain(value: &str) -> &'static str {
    let text = value.trim();
    if text.is_empty() {
        return "empty";
    }
    if text.contains("://") {
        return "has_scheme";
    }
    if text.contains('/') {
        return "has_path";
    }
    if text.contains(':') || text.chars().any(char::is_whitespace) {
        return "bad_domain";
    }
    let host = text.strip_prefix("*.").unwrap_or(text);
    if host.is_empty() {
        return "bad_domain";
    }
    let host = host.strip_suffix('.').unwrap_or(host);
    if host.is_empty() {
        return "bad_domain";
    }
    for label in host.split('.') {
        if label.is_empty()
            || label.len() > 63
            || !label
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-')
            || label.starts_with('-')
            || label.ends_with('-')
        {
            return "bad_domain";
        }
    }
    ""
}

/// A port the UI may offer: 1024-65535 (no privileged ports). The
/// engine config itself accepts 1-65535 (`config::parse_port`); this
/// is the stricter UI rule the old Kotlin validator enforced.
fn validate_port(value: &str) -> &'static str {
    let text = value.trim();
    if text.is_empty() {
        return "empty";
    }
    match text.parse::<u32>() {
        Ok(port) if (1024..=65535).contains(&port) => "",
        _ => "bad_port",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cidr_valid() {
        assert_eq!(validate(KIND_CIDR, "192.168.1.0/24"), "");
        assert_eq!(validate(KIND_CIDR, "10.0.0.5"), "");
        assert_eq!(validate(KIND_CIDR, "2001:db8::/32"), "");
        assert_eq!(validate(KIND_CIDR, "::1"), "");
        assert_eq!(validate(KIND_CIDR, "  192.168.1.0/24  "), "");
    }

    #[test]
    fn cidr_invalid() {
        assert_eq!(validate(KIND_CIDR, "300.1.1.1"), "bad_cidr");
        assert_eq!(validate(KIND_CIDR, "1.2.3"), "bad_cidr");
        assert_eq!(validate(KIND_CIDR, "1.2.3.4/33"), "bad_cidr");
        assert_eq!(validate(KIND_CIDR, "::1/129"), "bad_cidr");
        assert_eq!(validate(KIND_CIDR, "not-an-ip/24"), "bad_cidr");
        assert_eq!(validate(KIND_CIDR, ""), "empty");
        assert_eq!(validate(KIND_CIDR, "   "), "empty");
    }

    #[test]
    fn port_valid() {
        assert_eq!(validate(KIND_PORT, "1024"), "");
        assert_eq!(validate(KIND_PORT, "8080"), "");
        assert_eq!(validate(KIND_PORT, "65535"), "");
    }

    #[test]
    fn port_invalid() {
        assert_eq!(validate(KIND_PORT, "80"), "bad_port");
        assert_eq!(validate(KIND_PORT, "0"), "bad_port");
        assert_eq!(validate(KIND_PORT, "65536"), "bad_port");
        assert_eq!(validate(KIND_PORT, "abc"), "bad_port");
        assert_eq!(validate(KIND_PORT, ""), "empty");
    }

    #[test]
    fn domain_valid() {
        assert_eq!(validate(KIND_DOMAIN, "ads.example.com"), "");
        assert_eq!(validate(KIND_DOMAIN, "*.example.com"), "");
        assert_eq!(validate(KIND_DOMAIN, "ADS.EXAMPLE.COM"), "");
        assert_eq!(validate(KIND_DOMAIN, "ads.example.com."), "");
        assert_eq!(validate(KIND_DOMAIN, "example"), "");
    }

    #[test]
    fn domain_invalid() {
        assert_eq!(validate(KIND_DOMAIN, "ads .example.com"), "bad_domain");
        assert_eq!(
            validate(KIND_DOMAIN, "http://ads.example.com"),
            "has_scheme"
        );
        assert_eq!(validate(KIND_DOMAIN, "https://example.com"), "has_scheme");
        assert_eq!(
            validate(KIND_DOMAIN, "ads.example.com/path"),
            "has_path"
        );
        assert_eq!(
            validate(KIND_DOMAIN, "ads.example.com:8080"),
            "bad_domain"
        );
        assert_eq!(validate(KIND_DOMAIN, "a..b"), "bad_domain");
        assert_eq!(validate(KIND_DOMAIN, "-bad.com"), "bad_domain");
        assert_eq!(validate(KIND_DOMAIN, "exa*.com"), "bad_domain");
        assert_eq!(validate(KIND_DOMAIN, "*"), "bad_domain");
        assert_eq!(validate(KIND_DOMAIN, ""), "empty");
        assert_eq!(validate(KIND_DOMAIN, "   "), "empty");
    }

    #[test]
    fn unknown_kind() {
        assert_eq!(validate(9, "x"), "bad_kind");
    }
}
