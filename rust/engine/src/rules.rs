//! Routing rules: which network a connection uses, or whether it is
//! refused at all (0.6.6). Pure: no I/O, no sockets, no Android APIs.
//!
//! One rule per line, same syntax as hxmy:
//!
//! ```text
//! a.com        the domain and all its subdomains, any depth
//! *.a.com      exactly one extra label (sub.a.com, not a.com, not x.y.a.com)
//! =a.com       that exact host only
//! 1.2.3.4      one IP
//! 10.0.0.0/8   a CIDR (see cidr.rs)
//! ```
//!
//! Comments start with `#`, blank lines are ignored, matching is
//! case-insensitive and a trailing dot is ignored. Precedence: exact
//! (`=a.com`) beats the most specific domain match, which beats an
//! IP/CIDR match; a duplicate pattern is overwritten by the later
//! line. A domain target whose resolved address matches an IP/CIDR
//! rule is caught after DNS (see [`crate::connector`]): the domain
//! lookup runs before connect, the IP lookup after resolve, only when
//! no domain rule matched.
//!
//! The hot-reload text (see [`RuleSet::from_text`]) is one
//! `ACTION<TAB>pattern` line per rule, ACTION in `proxy` | `direct` |
//! `block`. Loading is atomic: the first invalid line rejects the
//! whole text and the previous set stays in force.

use std::collections::HashMap;
use std::net::IpAddr;

use crate::cidr::Cidr;

/// Where a connection goes: through the proxy's outbound network
/// (usually the VPN), out through the phone's real non-VPN network,
/// or refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    Proxy,
    Direct,
    Block,
}

impl Action {
    /// The wire name used in the snapshot line and the hot-reload text.
    pub fn as_str(&self) -> &'static str {
        match self {
            Action::Proxy => "proxy",
            Action::Direct => "direct",
            Action::Block => "block",
        }
    }

    /// Parse a hot-reload action name; None for anything else.
    pub fn from_name(name: &str) -> Option<Action> {
        match name {
            "proxy" => Some(Action::Proxy),
            "direct" => Some(Action::Direct),
            "block" => Some(Action::Block),
            _ => None,
        }
    }

    /// The ConnRecord storage code (0 = proxy, 1 = direct, 2 = block).
    pub fn to_u8(&self) -> u8 {
        match self {
            Action::Proxy => 0,
            Action::Direct => 1,
            Action::Block => 2,
        }
    }

    /// The ConnRecord storage code back to an action; anything else is
    /// proxy (the default).
    pub fn from_u8(v: u8) -> Action {
        match v {
            1 => Action::Direct,
            2 => Action::Block,
            _ => Action::Proxy,
        }
    }
}

#[derive(Default, Debug)]
pub struct RuleSet {
    exact: HashMap<String, Action>,     // =a.com
    suffix: HashMap<String, Action>,    // a.com (+ every subdomain)
    one_level: HashMap<String, Action>, // *.a.com
    nets: Vec<(Cidr, Action)>,          // IPs and CIDRs
}

impl RuleSet {
    pub fn add_line(&mut self, line: &str, action: Action) -> Result<(), String> {
        let l = line.split('#').next().unwrap_or("").trim().to_ascii_lowercase();
        if l.is_empty() {
            return Ok(());
        }
        if let Some(rest) = l.strip_prefix('=') {
            validate_host(rest)?;
            self.exact
                .insert(rest.trim_end_matches('.').to_string(), action);
        } else if let Some(rest) = l.strip_prefix("*.") {
            validate_host(rest)?;
            self.one_level
                .insert(rest.trim_end_matches('.').to_string(), action);
        } else if l.contains('/') || l.parse::<IpAddr>().is_ok() {
            let c: Cidr = Cidr::parse(&l)?; // IP without /N = host route
            self.nets.push((c, action));
        } else {
            validate_host(&l)?;
            self.suffix
                .insert(l.trim_end_matches('.').to_string(), action);
        }
        Ok(())
    }

    pub fn lookup(&self, host: &str) -> Option<Action> {
        let host = host.trim_end_matches('.').to_ascii_lowercase();
        if let Ok(ip) = host.parse::<IpAddr>() {
            return self.lookup_ip(&ip);
        }
        if let Some(a) = self.exact.get(&host) {
            return Some(*a);
        }
        if let Some((_, parent)) = host.split_once('.') {
            if let Some(a) = self.one_level.get(parent) {
                return Some(*a);
            }
        }
        let mut cur: &str = &host;
        loop {
            if let Some(a) = self.suffix.get(cur) {
                return Some(*a);
            }
            match cur.split_once('.') {
                Some((_, rest)) => cur = rest,
                None => break,
            }
        }
        None
    }

    /// The IP/CIDR rules only, for a resolved address (see the module
    /// docs): the later line wins, so the list is scanned back to front.
    pub fn lookup_ip(&self, ip: &IpAddr) -> Option<Action> {
        self.nets
            .iter()
            .rev()
            .find(|(c, _)| c.contains(ip))
            .map(|(_, a)| *a)
    }

    /// Parse hot-reload text: one `ACTION<TAB>pattern` line per rule.
    /// Blank lines and comment lines are skipped. Returns the set and
    /// the number of rule lines parsed. The first invalid line fails
    /// the whole text with its (1-based) line number, and nothing is
    /// applied: the caller keeps the previous set.
    pub fn from_text(text: &str) -> Result<(RuleSet, usize), (usize, String)> {
        let mut set = RuleSet::default();
        let mut count = 0;
        for (i, line) in text.lines().enumerate() {
            let line_no = i + 1;
            let (action_name, pattern) = match line.split_once('\t') {
                Some(v) => v,
                None => {
                    let trimmed = line.trim();
                    if trimmed.is_empty() || trimmed.starts_with('#') {
                        continue;
                    }
                    return Err((
                        line_no,
                        format!("expected 'ACTION<TAB>pattern', got '{line}'"),
                    ));
                }
            };
            let pattern_trimmed = pattern.trim();
            if pattern_trimmed.is_empty() || pattern_trimmed.starts_with('#') {
                continue;
            }
            let action = Action::from_name(
                action_name.trim().to_ascii_lowercase().as_str(),
            )
            .ok_or_else(|| {
                (
                    line_no,
                    format!(
                        "unknown action '{action_name}' (expected proxy, direct or block)"
                    ),
                )
            })?;
            set.add_line(pattern, action)
                .map_err(|e| (line_no, e))?;
            count += 1;
        }
        Ok((set, count))
    }
}

fn validate_host(h: &str) -> Result<(), String> {
    let h = h.trim_end_matches('.');
    if h.is_empty() || h.len() > 253 || h.starts_with('.') || h.contains("..") {
        return Err(format!("bad domain: {h}"));
    }
    if !h
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.' || b == b'_')
    {
        return Err(format!("bad domain: {h}"));
    }
    Ok(())
}

/// One named rule set (0.6.8): a flat set of plain domains,
/// suffix-matched — an entry `a.com` matches `a.com` and every
/// subdomain, any depth. Unlike RuleSet there are no `*` / `=` /
/// IP / CIDR forms: one domain per line, `#` comments and blank
/// lines skipped. Built for the 61k-domain OISD ads list, so the
/// whole set must stay under ~8 MB (see `approx_bytes`).
#[derive(Clone, Default, Debug)]
pub struct DomainSet {
    domains: HashSet<String>,
}

impl DomainSet {
    /// Parse list text. Returns the set and the number of unique
    /// domains. The first invalid line fails the whole text with its
    /// (1-based) line number, like [`RuleSet::from_text`], so the
    /// caller keeps the previous set.
    pub fn from_text(text: &str) -> Result<(DomainSet, usize), (usize, String)> {
        let mut set = DomainSet::default();
        for (i, line) in text.lines().enumerate() {
            let line_no = i + 1;
            let l = line.split('#').next().unwrap_or("").trim().to_ascii_lowercase();
            if l.is_empty() {
                continue;
            }
            validate_host(&l).map_err(|e| (line_no, e))?;
            set.domains.insert(l.trim_end_matches('.').to_string());
        }
        let count = set.domains.len();
        Ok((set, count))
    }

    /// True when the host itself or any of its parents is in the set.
    pub fn contains(&self, host: &str) -> bool {
        let host = host.trim_end_matches('.').to_ascii_lowercase();
        let mut cur: &str = &host;
        loop {
            if self.domains.contains(cur) {
                return true;
            }
            match cur.split_once('.') {
                Some((_, rest)) => cur = rest,
                None => return false,
            }
        }
    }

    pub fn len(&self) -> usize {
        self.domains.len()
    }

    pub fn is_empty(&self) -> bool {
        self.domains.is_empty()
    }

    /// Rough memory footprint in bytes (the strings plus the table),
    /// for the load test's sanity bound.
    pub fn approx_bytes(&self) -> usize {
        let strings: usize = self
            .domains
            .iter()
            .map(|d| std::mem::size_of::<String>() + d.capacity())
            .sum();
        strings + self.domains.capacity() * (std::mem::size_of::<String>() + 1)
    }
}

/// One named rule set as the engine holds it: an id, the action its
/// hits take, and the domain set. Entries stay in push order; a set
/// replaced by id keeps its position (see [`crate::Engine::set_rule_set`]).
#[derive(Clone, Debug)]
pub struct RuleSetEntry {
    pub id: String,
    pub action: Action,
    pub set: DomainSet,
}

/// Look up the route for a host: the manual [`RuleSet`] first, then
/// the rule sets in order (first hit wins). Returns the action and
/// whether it came from a rule set (a set Block is counted in the
/// snapshot's `blocked_by_set`). No rule at all returns None, which
/// the caller reads as the default Proxy.
pub fn lookup_route(
    rules: &RuleSet,
    sets: &[RuleSetEntry],
    host: &str,
) -> Option<(Action, bool)> {
    if let Some(a) = rules.lookup(host) {
        return Some((a, false));
    }
    for entry in sets {
        if entry.set.contains(host) {
            return Some((entry.action, true));
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    fn ip(text: &str) -> IpAddr {
        IpAddr::from_str(text).expect("test IP is valid")
    }

    fn set(text: &str) -> RuleSet {
        // The hot-reload text is the easiest way to build a set in
        // tests: one ACTION<TAB>pattern line per rule.
        RuleSet::from_text(text).expect("test rules are valid").0
    }

    #[test]
    fn suffix_matches_domain_and_every_subdomain() {
        let s = set("direct\ta.com\n");
        assert_eq!(s.lookup("a.com"), Some(Action::Direct));
        assert_eq!(s.lookup("sub.a.com"), Some(Action::Direct));
        assert_eq!(s.lookup("x.y.a.com"), Some(Action::Direct));
        assert_eq!(s.lookup("b.com"), None);
        assert_eq!(s.lookup("not-a.com"), None);
    }

    #[test]
    fn one_level_matches_exactly_one_label() {
        let s = set("direct\t*.a.com\n");
        assert_eq!(s.lookup("sub.a.com"), Some(Action::Direct));
        assert_eq!(s.lookup("a.com"), None);
        assert_eq!(s.lookup("x.y.a.com"), None);
    }

    #[test]
    fn exact_matches_only_that_host() {
        let s = set("block\t=a.com\n");
        assert_eq!(s.lookup("a.com"), Some(Action::Block));
        assert_eq!(s.lookup("sub.a.com"), None);
    }

    #[test]
    fn exact_beats_suffix_and_most_specific_suffix_wins() {
        let s = set("direct\ta.com\nblock\t=a.com\n");
        assert_eq!(s.lookup("a.com"), Some(Action::Block));
        // The exact rule only covers a.com itself: subdomains stay direct.
        assert_eq!(s.lookup("sub.a.com"), Some(Action::Direct));
        let s = set("direct\ta.com\nblock\tsub.a.com\n");
        assert_eq!(s.lookup("sub.a.com"), Some(Action::Block));
        assert_eq!(s.lookup("a.com"), Some(Action::Direct));
        assert_eq!(s.lookup("x.sub.a.com"), Some(Action::Block));
    }

    #[test]
    fn trailing_dot_and_uppercase_are_ignored() {
        let s = set("direct\tA.COM\n");
        assert_eq!(s.lookup("a.com."), Some(Action::Direct));
        assert_eq!(s.lookup("SUB.A.COM"), Some(Action::Direct));
    }

    #[test]
    fn ip_and_cidr_match_resolved_addresses() {
        let s = set("block\t10.0.0.0/8\ndirect\t1.2.3.4\n");
        assert_eq!(s.lookup("10.1.2.3"), Some(Action::Block));
        assert_eq!(s.lookup("1.2.3.4"), Some(Action::Direct));
        assert_eq!(s.lookup("1.2.3.5"), None);
        assert_eq!(s.lookup_ip(&ip("10.9.9.9")), Some(Action::Block));
        assert_eq!(s.lookup_ip(&ip("8.8.8.8")), None);
    }

    #[test]
    fn later_duplicate_wins() {
        let s = set("direct\ta.com\nblock\ta.com\n");
        assert_eq!(s.lookup("a.com"), Some(Action::Block));
        let s = set("block\t10.0.0.0/8\ndirect\t10.0.0.0/8\n");
        assert_eq!(s.lookup("10.1.1.1"), Some(Action::Direct));
    }

    #[test]
    fn invalid_lines_report_the_right_line_number() {
        assert_eq!(
            RuleSet::from_text("direct\ta.com\nbogus\tb.com\n").unwrap_err(),
            (
                2,
                "unknown action 'bogus' (expected proxy, direct or block)".to_string()
            )
        );
        assert_eq!(
            RuleSet::from_text("direct a.com\n").unwrap_err(),
            (
                1,
                "expected 'ACTION<TAB>pattern', got 'direct a.com'".to_string()
            )
        );
        assert!(matches!(
            RuleSet::from_text("direct\tbad domain!\n"),
            Err((1, _))
        ));
        assert!(matches!(
            RuleSet::from_text("direct\t10.0.0.0/33\n"),
            Err((1, _))
        ));
    }

    #[test]
    fn comments_and_blank_lines_are_skipped() {
        let (s, count) = RuleSet::from_text(
            "# a comment\n\ndirect\ta.com # trailing comment\n  \nblock\tb.com\n",
        )
        .expect("comments are skipped");
        assert_eq!(count, 2);
        assert_eq!(s.lookup("a.com"), Some(Action::Direct));
        assert_eq!(s.lookup("b.com"), Some(Action::Block));
    }

    #[test]
    fn empty_set_matches_nothing() {
        let s = RuleSet::default();
        assert_eq!(s.lookup("a.com"), None);
        assert_eq!(s.lookup("1.2.3.4"), None);
        assert_eq!(s.lookup_ip(&ip("10.0.0.1")), None);
        let (s, count) = RuleSet::from_text("").expect("empty text is valid");
        assert_eq!(count, 0);
        assert_eq!(s.lookup("a.com"), None);
    }

    #[test]
    fn sixty_thousand_domains_load_fast_and_lookup_fast() {
        let mut s = RuleSet::default();
        let start = std::time::Instant::now();
        for i in 0..60_000 {
            s.add_line(&format!("host{i}.example.com"), Action::Direct)
                .expect("valid rule");
        }
        assert!(
            start.elapsed() < std::time::Duration::from_millis(200),
            "60k rules must load in < 200 ms, took {:?}",
            start.elapsed()
        );
        let start = std::time::Instant::now();
        let n = 10_000u32;
        for i in 0..n {
            assert_eq!(
                s.lookup(&format!("sub.host{i}.example.com")),
                Some(Action::Direct)
            );
        }
        let per_lookup = start.elapsed() / n;
        assert!(
            per_lookup < std::time::Duration::from_micros(5),
            "lookups must average < 5 us, took {per_lookup:?}"
        );
    }

    fn domain_set(text: &str) -> DomainSet {
        DomainSet::from_text(text).expect("test set is valid").0
    }

    fn entry(id: &str, action: Action, text: &str) -> RuleSetEntry {
        RuleSetEntry {
            id: id.to_string(),
            action,
            set: domain_set(text),
        }
    }

    #[test]
    fn set_suffix_matches_host_and_every_subdomain() {
        let s = domain_set("a.com\n");
        assert!(s.contains("a.com"));
        assert!(s.contains("sub.a.com"));
        assert!(s.contains("x.y.a.com"));
        assert!(s.contains("A.COM."));
        assert!(!s.contains("b.com"));
        assert!(!s.contains("not-a.com"));
        assert!(!s.contains("a.com.evil.com"));
    }

    #[test]
    fn set_from_text_skips_comments_blanks_and_dupes() {
        let (s, count) = DomainSet::from_text(
            "# ads\n\na.com # trailing\na.com\nB.COM\n",
        )
        .expect("comments and dupes are skipped");
        // Duplicates count once: the set holds 2 unique domains.
        assert_eq!(count, 2);
        assert_eq!(s.len(), 2);
        assert!(s.contains("sub.b.com"));
    }

    #[test]
    fn set_from_text_reports_the_bad_line() {
        assert!(matches!(
            DomainSet::from_text("a.com\nbad domain!\n"),
            Err((2, _))
        ));
        assert!(matches!(
            DomainSet::from_text("*.a.com\n"),
            Err((1, _))
        ));
        assert!(matches!(DomainSet::from_text(""), Ok((_, 0))));
    }

    #[test]
    fn manual_rule_overrides_set() {
        let manual = set("direct\ta.com\n");
        let sets = [entry("ads", Action::Block, "a.com\nb.com\n")];
        // The manual rule wins, and the hit is not from a set.
        assert_eq!(
            lookup_route(&manual, &sets, "a.com"),
            Some((Action::Direct, false))
        );
        // No manual rule: the set answers, flagged as a set hit.
        assert_eq!(
            lookup_route(&manual, &sets, "b.com"),
            Some((Action::Block, true))
        );
        assert_eq!(lookup_route(&manual, &sets, "c.com"), None);
    }

    #[test]
    fn first_set_wins() {
        let manual = RuleSet::default();
        let sets = [
            entry("one", Action::Direct, "a.com\n"),
            entry("two", Action::Block, "a.com\n"),
        ];
        assert_eq!(
            lookup_route(&manual, &sets, "a.com"),
            Some((Action::Direct, true))
        );
    }

    #[test]
    fn sixty_thousand_domains_load_fast_and_stay_small() {
        let mut text = String::new();
        for i in 0..60_000 {
            text.push_str(&format!("host{i}.example.com\n"));
        }
        let start = std::time::Instant::now();
        let (s, count) = DomainSet::from_text(&text).expect("60k lines are valid");
        assert_eq!(count, 60_000);
        assert!(
            start.elapsed() < std::time::Duration::from_millis(500),
            "60k domains must load in < 500 ms, took {:?}",
            start.elapsed()
        );
        assert!(
            s.approx_bytes() < 8 * 1024 * 1024,
            "60k domains must stay under 8 MB, estimated {} bytes",
            s.approx_bytes()
        );
        let start = std::time::Instant::now();
        let n = 10_000u32;
        for i in 0..n {
            assert!(s.contains(&format!("sub.host{i}.example.com")));
        }
        let per_lookup = start.elapsed() / n;
        assert!(
            per_lookup < std::time::Duration::from_micros(5),
            "set lookups must average < 5 us, took {per_lookup:?}"
        );
    }
}
