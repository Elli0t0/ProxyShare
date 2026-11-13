//! Bind an outbound socket to an Android Network (net_handle_t).
//!
//! `android_setsocknetwork` (libandroid) binds a socket fd to the
//! Network whose handle the caller passes. 0 means "default network"
//! and is a no-op here. On non-Android builds this does nothing, so
//! host tests keep working.

use std::io;
use std::os::fd::RawFd;

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    fn android_setsocknetwork(network: u64, fd: i32) -> i32;
}

/// Bind a socket to an Android Network (net_handle_t). 0 means "default network".
/// On non-Android builds this does nothing, so host tests keep working.
pub fn bind_socket_to_network(fd: RawFd, network: u64) -> io::Result<()> {
    if network == 0 {
        return Ok(());
    }
    #[cfg(target_os = "android")]
    {
        // SAFETY: fd is a valid, open socket owned by the caller for the whole call.
        let rc = unsafe { android_setsocknetwork(network, fd) };
        if rc != 0 {
            return Err(io::Error::last_os_error());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = fd;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;

    fn open_socket() -> std::net::TcpListener {
        std::net::TcpListener::bind("127.0.0.1:0").expect("bind a socket for the test")
    }

    #[test]
    fn zero_handle_is_a_noop_ok() {
        let socket = open_socket();
        assert!(bind_socket_to_network(socket.as_raw_fd(), 0).is_ok());
    }

    /// A non-zero handle is a no-op Ok on non-Android; on Android it
    /// would call the real function, so this test does not run there.
    #[cfg(not(target_os = "android"))]
    #[test]
    fn nonzero_handle_is_a_noop_ok_on_host() {
        let socket = open_socket();
        assert!(bind_socket_to_network(socket.as_raw_fd(), 7).is_ok());
    }
}
