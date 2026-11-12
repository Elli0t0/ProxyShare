//! Sniff the first byte of a connection to tell HTTP from SOCKS.
//!
//! The first byte of a SOCKS4 greeting is 0x04 and of a SOCKS5 greeting
//! is 0x05; anything else is treated as HTTP. The peek does not consume
//! the byte, so the handler that runs next still sees the whole stream.

use std::time::Duration;
use tokio::net::TcpStream;

use crate::model::Protocol;

/// How long to wait for the first byte before giving up on a connection.
const PEEK_TIMEOUT: Duration = Duration::from_secs(5);

/// Read the first byte of `stream` (without consuming it) and classify
/// the protocol. Returns None when the client closed, errored, or sent
/// nothing within the peek timeout.
pub async fn sniff_protocol(stream: &mut TcpStream) -> Option<Protocol> {
    let mut buf = [0u8; 1];
    let peeked = tokio::time::timeout(PEEK_TIMEOUT, stream.peek(&mut buf)).await;
    match peeked {
        Ok(Ok(1)) => Some(classify(buf[0])),
        _ => None,
    }
}

/// 0x04 is a SOCKS4 greeting, 0x05 a SOCKS5 greeting, anything else HTTP.
pub fn classify(first_byte: u8) -> Protocol {
    match first_byte {
        0x04 => Protocol::Socks4,
        0x05 => Protocol::Socks5,
        _ => Protocol::Http,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_bytes() {
        assert_eq!(classify(0x04), Protocol::Socks4);
        assert_eq!(classify(0x05), Protocol::Socks5);
        assert_eq!(classify(b'G'), Protocol::Http); // "GET ..."
        assert_eq!(classify(0x00), Protocol::Http);
    }
}
