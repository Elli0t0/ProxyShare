//! SOCKS5 UDP datagram headers (pure functions, no sockets).
//!
//! A UDP datagram relayed through SOCKS5 starts with:
//! RSV(2) = 0, FRAG(1) = 0, ATYP, DST.ADDR, DST.PORT, then DATA.
//! A datagram with FRAG != 0 is dropped (fragmented datagrams are not
//! supported). These functions only parse and build the header; the
//! actual UDP sockets live elsewhere.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use crate::socks5::Target;

/// Returns (target, port, offset where DATA starts).
pub fn parse_udp_header(buf: &[u8]) -> Option<(Target, u16, usize)> {
    if buf.len() < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
        return None;
    }
    match buf[3] {
        0x01 => {
            if buf.len() < 10 {
                return None;
            }
            let ip = Ipv4Addr::new(buf[4], buf[5], buf[6], buf[7]);
            Some((Target::Ip(IpAddr::V4(ip)), u16::from_be_bytes([buf[8], buf[9]]), 10))
        }
        0x03 => {
            let n = usize::from(*buf.get(4)?);
            let end = 5 + n;
            if buf.len() < end + 2 {
                return None;
            }
            let name = std::str::from_utf8(&buf[5..end]).ok()?.to_string();
            Some((Target::Domain(name), u16::from_be_bytes([buf[end], buf[end + 1]]), end + 2))
        }
        0x04 => {
            if buf.len() < 22 {
                return None;
            }
            let mut a = [0u8; 16];
            a.copy_from_slice(&buf[4..20]);
            Some((Target::Ip(IpAddr::V6(a.into())), u16::from_be_bytes([buf[20], buf[21]]), 22))
        }
        _ => None,
    }
}

pub fn build_udp_header(src: SocketAddr) -> Vec<u8> {
    let mut v = vec![0, 0, 0];
    match src.ip() {
        IpAddr::V4(a) => {
            v.push(0x01);
            v.extend_from_slice(&a.octets());
        }
        IpAddr::V6(a) => {
            v.push(0x04);
            v.extend_from_slice(&a.octets());
        }
    }
    v.extend_from_slice(&src.port().to_be_bytes());
    v
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv6Addr;

    #[test]
    fn v4_round_trip() {
        let mut d = build_udp_header(SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4)),
            5678,
        ));
        d.extend_from_slice(b"hello");
        let (target, port, offset) = parse_udp_header(&d).expect("v4 parses");
        assert_eq!(target, Target::Ip(IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))));
        assert_eq!(port, 5678);
        assert_eq!(offset, 10);
        assert_eq!(&d[offset..], b"hello");
    }

    #[test]
    fn domain_round_trip() {
        let mut d = vec![0, 0, 0, 0x03, 4, b'e', b'x', b'a', b'm', 0x1F, 0x90];
        d.extend_from_slice(b"data");
        let (target, port, offset) = parse_udp_header(&d).expect("domain parses");
        assert_eq!(target, Target::Domain("exam".to_string()));
        assert_eq!(port, 8080);
        assert_eq!(offset, 11);
        assert_eq!(&d[offset..], b"data");
    }

    #[test]
    fn v6_round_trip() {
        let mut d = build_udp_header(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443));
        d.extend_from_slice(b"hi");
        let (target, port, offset) = parse_udp_header(&d).expect("v6 parses");
        assert_eq!(target, Target::Ip(IpAddr::V6(Ipv6Addr::LOCALHOST)));
        assert_eq!(port, 443);
        assert_eq!(offset, 22);
        assert_eq!(&d[offset..], b"hi");
    }

    #[test]
    fn truncated_returns_none() {
        assert_eq!(parse_udp_header(&[]), None);
        assert_eq!(parse_udp_header(&[0, 0, 0]), None);
        assert_eq!(parse_udp_header(&[0, 0, 0, 0x01, 1, 2, 3]), None);
        assert_eq!(parse_udp_header(&[0, 0, 0, 0x03, 5, b'a', b'b']), None);
        assert_eq!(parse_udp_header(&[0, 0, 0, 0x04, 0, 0]), None);
    }

    #[test]
    fn frag_nonzero_returns_none() {
        let mut d = build_udp_header(SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4)),
            80,
        ));
        d[2] = 1;
        assert_eq!(parse_udp_header(&d), None);
    }
}
