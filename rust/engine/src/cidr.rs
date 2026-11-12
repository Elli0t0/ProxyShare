//! CIDR network ranges (IPv4 and IPv6) for the allowed-clients list.
//!
//! A range is written `address/prefix`, e.g. `192.168.1.0/24` or
//! `2001:db8::/32`. A bare address without `/` means a single host
//! (`/32` for IPv4, `/128` for IPv6). The stored address is the masked
//! network address, so `192.168.1.5/24` displays as `192.168.1.0/24`.

use std::fmt;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::str::FromStr;

/// An IP network range: a base address plus a prefix length.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Cidr {
    /// The masked network address (host bits zeroed at parse time).
    addr: IpAddr,
    prefix: u8,
}

impl Cidr {
    /// Parse `192.168.1.0/24`, `2001:db8::/32`, or a bare IP (single host).
    pub fn parse(text: &str) -> Result<Self, String> {
        let text = text.trim();
        let (addr_text, prefix_text) = match text.split_once('/') {
            Some((addr, prefix)) => (addr, Some(prefix)),
            None => (text, None),
        };
        let addr = IpAddr::from_str(addr_text.trim())
            .map_err(|_| format!("cidr: invalid IP address '{addr_text}' in '{text}'"))?;
        let max_prefix = match addr {
            IpAddr::V4(_) => 32,
            IpAddr::V6(_) => 128,
        };
        let prefix = match prefix_text {
            Some(p) => p
                .trim()
                .parse::<u8>()
                .map_err(|_| format!("cidr: invalid prefix '{p}' in '{text}' (expected 0-{max_prefix})"))?,
            None => max_prefix,
        };
        if prefix > max_prefix {
            let family = if addr.is_ipv4() { 4 } else { 6 };
            return Err(format!(
                "cidr: prefix {prefix} in '{text}' is too large for an IPv{family} address (max {max_prefix})"
            ));
        }
        Ok(Self {
            addr: mask(addr, prefix),
            prefix,
        })
    }

    /// The network address (host bits zeroed).
    pub fn network(&self) -> IpAddr {
        self.addr
    }

    /// The prefix length.
    pub fn prefix(&self) -> u8 {
        self.prefix
    }

    /// True when `ip` is inside this range. An address of the other family
    /// is never inside.
    pub fn contains(&self, ip: &IpAddr) -> bool {
        match (self.addr, *ip) {
            (IpAddr::V4(net), IpAddr::V4(addr)) => mask_v4(addr, self.prefix) == net,
            (IpAddr::V6(net), IpAddr::V6(addr)) => mask_v6(addr, self.prefix) == net,
            _ => false,
        }
    }
}

impl fmt::Display for Cidr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}/{}", self.addr, self.prefix)
    }
}

fn mask(addr: IpAddr, prefix: u8) -> IpAddr {
    match addr {
        IpAddr::V4(v4) => IpAddr::V4(mask_v4(v4, prefix)),
        IpAddr::V6(v6) => IpAddr::V6(mask_v6(v6, prefix)),
    }
}

fn mask_v4(addr: Ipv4Addr, prefix: u8) -> Ipv4Addr {
    let bits = u32::from(addr);
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ipv4Addr::from(bits & mask)
}

fn mask_v6(addr: Ipv6Addr, prefix: u8) -> Ipv6Addr {
    let bits = u128::from(addr);
    let mask = if prefix == 0 { 0 } else { u128::MAX << (128 - prefix) };
    Ipv6Addr::from(bits & mask)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(text: &str) -> IpAddr {
        IpAddr::from_str(text).expect("test IP is valid")
    }

    #[test]
    fn v4_inside_and_outside() {
        let cidr = Cidr::parse("192.168.1.0/24").expect("valid CIDR");
        assert!(cidr.contains(&ip("192.168.1.1")));
        assert!(cidr.contains(&ip("192.168.1.255")));
        assert!(!cidr.contains(&ip("192.168.2.1")));
        assert!(!cidr.contains(&ip("10.0.0.1")));
    }

    #[test]
    fn v4_host_bits_are_masked() {
        let cidr = Cidr::parse("192.168.1.5/24").expect("valid CIDR");
        assert_eq!(cidr.network(), ip("192.168.1.0"));
        assert_eq!(cidr.to_string(), "192.168.1.0/24");
    }

    #[test]
    fn v4_single_host_and_all() {
        let host = Cidr::parse("10.0.0.5").expect("bare IP parses");
        assert_eq!(host.prefix(), 32);
        assert!(host.contains(&ip("10.0.0.5")));
        assert!(!host.contains(&ip("10.0.0.6")));
        let all = Cidr::parse("0.0.0.0/0").expect("valid CIDR");
        assert!(all.contains(&ip("8.8.8.8")));
    }

    #[test]
    fn v6_inside_and_outside() {
        let cidr = Cidr::parse("2001:db8::/32").expect("valid CIDR");
        assert!(cidr.contains(&ip("2001:db8::1")));
        assert!(cidr.contains(&ip("2001:db8:ffff::1")));
        assert!(!cidr.contains(&ip("2001:db9::1")));
    }

    #[test]
    fn v6_loopback_single_host() {
        let host = Cidr::parse("::1").expect("bare IPv6 parses");
        assert_eq!(host.prefix(), 128);
        assert!(host.contains(&ip("::1")));
        assert!(!host.contains(&ip("::2")));
    }

    #[test]
    fn families_never_contain_each_other() {
        let v4 = Cidr::parse("192.168.0.0/16").expect("valid CIDR");
        assert!(!v4.contains(&ip("::1")));
        let v6 = Cidr::parse("::/0").expect("valid CIDR");
        assert!(!v6.contains(&ip("192.168.1.1")));
    }

    #[test]
    fn invalid_inputs_are_errors() {
        assert!(Cidr::parse("192.168.1.0/33").is_err());
        assert!(Cidr::parse("::1/129").is_err());
        assert!(Cidr::parse("not-an-ip/24").is_err());
        assert!(Cidr::parse("192.168.1.0/abc").is_err());
        assert!(Cidr::parse("").is_err());
    }
}
