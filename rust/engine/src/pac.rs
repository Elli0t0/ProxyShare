//! The PAC / setup-page server (0.6.5): a tiny HTTP server on the
//! phone that serves /proxy.pac and a one-page setup site, so a laptop
//! can configure itself from a QR code. No auth on the PAC itself (it
//! only contains ip:port). Same client rules as the proxy
//! (allowed_clients / private_only); a rejected client gets the
//! connection closed with no reply. A bind failure must not stop the
//! proxy: the engine logs a WARN and reports pac_ok=false in the
//! snapshot.

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;

use crate::config::Config;
use crate::model::Protocol;

/// Most of a request the server will read: anything bigger is dropped
/// with no reply.
const MAX_REQUEST_BYTES: usize = 4096;

/// How long a client has to finish sending its request head.
const READ_TIMEOUT: Duration = Duration::from_secs(3);

const PAC_TEMPLATE: &str = r#"function FindProxyForURL(url, host) {
  if (isPlainHostName(host) || host === "localhost") return "DIRECT";
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host) &&
      (isInNet(host, "10.0.0.0", "255.0.0.0") ||
       isInNet(host, "172.16.0.0", "255.240.0.0") ||
       isInNet(host, "192.168.0.0", "255.255.0.0") ||
       isInNet(host, "127.0.0.0", "255.0.0.0"))) return "DIRECT";
  return "__CHAIN__";
}
"#;

/// The PAC body for `host` (the address the client connected to):
/// the proxy chain, then DIRECT as the fallback. IPv6 literals are
/// bracketed. `http` / `socks` are the ports the proxy listens on
/// (None when that protocol is off).
pub fn pac_body(host: &str, http: Option<u16>, socks: Option<u16>) -> String {
    let h = bracket_host(host);
    let mut parts: Vec<String> = Vec::new();
    if let Some(p) = http {
        parts.push(format!("PROXY {h}:{p}"));
    }
    if let Some(p) = socks {
        parts.push(format!("SOCKS5 {h}:{p}"));
    }
    parts.push("DIRECT".to_string());
    PAC_TEMPLATE.replace("__CHAIN__", &parts.join("; "))
}

/// Bracket an IPv6 literal for use inside a URL or a PAC file.
fn bracket_host(host: &str) -> String {
    if host.contains(':') {
        format!("[{host}]")
    } else {
        host.to_string()
    }
}

/// Which page a request line asks for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Route {
    Pac,
    Setup,
    NotFound,
}

pub fn route(request_line: &str) -> Route {
    // "GET /proxy.pac HTTP/1.1" -> Route::Pac; "GET / ..." or "/setup" -> Route::Setup; else NotFound
    let mut it = request_line.split_whitespace();
    match (it.next(), it.next()) {
        (Some("GET"), Some("/proxy.pac")) => Route::Pac,
        (Some("GET"), Some("/")) | (Some("GET"), Some("/setup")) => Route::Setup,
        _ => Route::NotFound,
    }
}

/// The setup page: "Auto (PAC)" with the PAC URL, "Manual" with the
/// HTTP/SOCKS host:port, and short steps for Windows, macOS, Linux
/// and Firefox. No JavaScript, no external resources. When auth is on
/// the page says the browser will ask for the username and password;
/// it never prints the password.
pub fn setup_page(
    host: &str,
    http: Option<u16>,
    socks: Option<u16>,
    pac_url: &str,
    auth_on: bool,
) -> String {
    let h = bracket_host(host);
    let mut manual = String::new();
    if let Some(p) = http {
        manual.push_str(&format!("<p>HTTP proxy: <code>{h}:{p}</code></p>\n"));
    }
    if let Some(p) = socks {
        manual.push_str(&format!("<p>SOCKS5 proxy: <code>{h}:{p}</code></p>\n"));
    }
    let auth = if auth_on {
        "<p>Your browser will ask for the username and password.</p>\n"
    } else {
        ""
    };
    format!(
        "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">\
        <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
        <title>ProxyShare setup</title></head>\n<body>\n\
        <h1>ProxyShare setup</h1>\n\
        <h2>Auto (PAC)</h2>\n<p>Open this URL in your browser's proxy settings: <code>{pac_url}</code></p>\n\
        <h2>Manual</h2>\n{manual}{auth}\
        <h2>Windows</h2>\n<p>Settings &gt; Network &amp; internet &gt; Proxy &gt; Use setup script, paste the PAC URL above.</p>\n\
        <h2>macOS</h2>\n<p>System Settings &gt; Network &gt; Wi-Fi &gt; Details &gt; Proxies &gt; Automatic Proxy Configuration, paste the PAC URL above.</p>\n\
        <h2>Linux</h2>\n<p>Settings &gt; Network &gt; Network Proxy &gt; Automatic, paste the PAC URL above.</p>\n\
        <h2>Firefox</h2>\n<p>Settings &gt; General &gt; Network Settings &gt; Automatic proxy configuration URL, paste the PAC URL above.</p>\n\
        </body></html>\n"
    )
}

/// One HTTP response: status line, content type, length, close.
pub fn http_response(status: u16, reason: &str, content_type: &str, body: &str) -> String {
    format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    )
}

/// The HTTP and SOCKS ports the PAC body advertises, from the config:
/// in split mode the configured ports when their protocol is enabled,
/// in single mode the single port for each enabled protocol.
pub fn pac_ports(config: &Config) -> (Option<u16>, Option<u16>) {
    let socks_on = config
        .protocols
        .iter()
        .any(|p| matches!(p, Protocol::Socks4 | Protocol::Socks5));
    match (config.http_port, config.socks_port) {
        (None, None) => (
            if config.protocols.contains(&Protocol::Http) {
                Some(config.port)
            } else {
                None
            },
            if socks_on { Some(config.port) } else { None },
        ),
        (h, s) => (
            if h.is_some() && config.protocols.contains(&Protocol::Http) {
                h
            } else {
                None
            },
            if s.is_some() && socks_on { s } else { None },
        ),
    }
}

/// Accept loop for the PAC server: same client rules as the proxy
/// (`client_allowed`). A rejected client gets the connection closed
/// with no reply. Runs until `shutdown` fires; each connection is
/// handled on its own task.
pub async fn pac_accept_loop(
    listener: TcpListener,
    config: Config,
    http: Option<u16>,
    socks: Option<u16>,
    mut shutdown: watch::Receiver<bool>,
) {
    loop {
        tokio::select! {
            _ = shutdown.changed() => break,
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, peer)) => {
                        if !crate::engine::client_allowed(&config, &peer.ip()) {
                            continue; // stream dropped: no reply
                        }
                        let auth_on = config.auth_user.is_some();
                        let pac_port = config.pac_port;
                        tokio::spawn(async move {
                            handle_pac_conn(stream, http, socks, pac_port, auth_on).await;
                        });
                    }
                    Err(e) => {
                        crate::diag::log_warn(format!("pac accept error: {e}"));
                        if *shutdown.borrow() {
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(10)).await;
                    }
                }
            }
        }
    }
}

/// One PAC connection: read at most 4 KB of request (3 s timeout),
/// route it, reply once, and close. A request over 4 KB, or one that
/// never completes a header, is dropped with no reply. The proxy host
/// in the PAC and the setup page is the address the client connected
/// to (`local_addr`), so it works on hotspot, Wi-Fi and USB without
/// the user choosing an address.
pub async fn handle_pac_conn(
    mut stream: TcpStream,
    http: Option<u16>,
    socks: Option<u16>,
    pac_port: u16,
    auth_on: bool,
) {
    let host = match stream.local_addr() {
        Ok(addr) => addr.ip().to_string(),
        Err(_) => return,
    };
    let read = async {
        let mut buf: Vec<u8> = Vec::new();
        let mut chunk = [0u8; 1024];
        loop {
            match stream.read(&mut chunk).await {
                Ok(0) => return None, // client closed
                Ok(n) => {
                    buf.extend_from_slice(&chunk[..n]);
                    if buf.len() > MAX_REQUEST_BYTES {
                        return None; // over 4 KB: drop
                    }
                    if find_head_end(&buf).is_some() {
                        return Some(buf);
                    }
                }
                Err(_) => return None,
            }
        }
    };
    let buf = match tokio::time::timeout(READ_TIMEOUT, read).await {
        Ok(Some(buf)) => buf,
        _ => return, // timeout, client close, or oversized: no reply
    };
    let text = String::from_utf8_lossy(&buf);
    let request_line = text.lines().next().unwrap_or("");
    let pac_url = format!("http://{}:{pac_port}/proxy.pac", bracket_host(&host));
    let response = match route(request_line) {
        Route::Pac => http_response(
            200,
            "OK",
            "application/x-ns-proxy-autoconfig",
            &pac_body(&host, http, socks),
        ),
        Route::Setup => http_response(
            200,
            "OK",
            "text/html; charset=utf-8",
            &setup_page(&host, http, socks, &pac_url, auth_on),
        ),
        Route::NotFound => http_response(404, "Not Found", "text/plain; charset=utf-8", "Not found"),
    };
    let _ = stream.write_all(response.as_bytes()).await;
    // The stream is dropped here: close after reply.
}

/// Offset just past the blank line that ends the request head, if the
/// head is complete.
fn find_head_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|i| i + 4)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[test]
    fn pac_body_http_and_socks() {
        let body = pac_body("192.168.1.2", Some(8080), Some(1080));
        assert!(body.contains("function FindProxyForURL"));
        assert!(body.contains("PROXY 192.168.1.2:8080; SOCKS5 192.168.1.2:1080; DIRECT"));
        assert!(body.contains("isPlainHostName"));
    }

    #[test]
    fn pac_body_socks_only() {
        let body = pac_body("192.168.1.2", None, Some(1080));
        assert!(body.contains("SOCKS5 192.168.1.2:1080; DIRECT"));
        assert!(!body.contains("PROXY "));
    }

    #[test]
    fn pac_body_http_only() {
        let body = pac_body("192.168.1.2", Some(8080), None);
        assert!(body.contains("PROXY 192.168.1.2:8080; DIRECT"));
        assert!(!body.contains("SOCKS5"));
    }

    #[test]
    fn pac_body_brackets_ipv6_host() {
        let body = pac_body("fd00::1", Some(8080), Some(1080));
        assert!(body.contains("PROXY [fd00::1]:8080; SOCKS5 [fd00::1]:1080; DIRECT"));
    }

    #[test]
    fn route_cases() {
        assert_eq!(route("GET /proxy.pac HTTP/1.1"), Route::Pac);
        assert_eq!(route("GET / HTTP/1.1"), Route::Setup);
        assert_eq!(route("GET /setup HTTP/1.1"), Route::Setup);
        assert_eq!(route("GET /other HTTP/1.1"), Route::NotFound);
    }

    #[test]
    fn route_post_is_not_found() {
        assert_eq!(route("POST /proxy.pac HTTP/1.1"), Route::NotFound);
        assert_eq!(route(""), Route::NotFound);
    }

    #[test]
    fn setup_page_has_both_sections_and_steps() {
        let page = setup_page(
            "192.168.1.2",
            Some(8080),
            Some(1080),
            "http://192.168.1.2:8899/proxy.pac",
            false,
        );
        assert!(page.contains("Auto (PAC)"));
        assert!(page.contains("http://192.168.1.2:8899/proxy.pac"));
        assert!(page.contains("Manual"));
        assert!(page.contains("HTTP proxy: <code>192.168.1.2:8080</code>"));
        assert!(page.contains("SOCKS5 proxy: <code>192.168.1.2:1080</code>"));
        assert!(page.contains("Windows"));
        assert!(page.contains("macOS"));
        assert!(page.contains("Linux"));
        assert!(page.contains("Firefox"));
        assert!(!page.contains("username and password"));
        assert!(!page.contains("secret"));
    }

    #[test]
    fn setup_page_notes_auth_without_printing_secrets() {
        let page = setup_page(
            "192.168.1.2",
            Some(8080),
            None,
            "http://192.168.1.2:8899/proxy.pac",
            true,
        );
        assert!(page.contains("Your browser will ask for the username and password."));
        assert!(!page.contains("SOCKS5 proxy:"));
    }

    #[test]
    fn pac_ports_single_and_split_mode() {
        let single = Config::parse("").expect("defaults parse");
        assert_eq!(pac_ports(&single), (Some(1080), Some(1080)));
        let http_only = Config::parse("protocols = http").expect("http-only parses");
        assert_eq!(pac_ports(&http_only), (Some(1080), None));
        let split = Config::parse("http_port = 8080\nsocks_port = 1080")
            .expect("split parses");
        assert_eq!(pac_ports(&split), (Some(8080), Some(1080)));
        let split_http_off = Config::parse("http_port = 8080\nsocks_port = 1080\nprotocols = socks5")
            .expect("split socks-only parses");
        assert_eq!(pac_ports(&split_http_off), (None, Some(1080)));
    }

    #[test]
    fn http_response_shape() {
        let r = http_response(200, "OK", "text/plain; charset=utf-8", "hi");
        assert!(r.starts_with("HTTP/1.1 200 OK\r\n"));
        assert!(r.contains("Content-Type: text/plain; charset=utf-8\r\n"));
        assert!(r.contains("Content-Length: 2\r\n"));
        assert!(r.ends_with("\r\n\r\nhi"));
    }

    /// Run the PAC accept loop on a loopback port with `config_text`,
    /// connect one client, send `request`, and read the reply to EOF.
    async fn run_loop_once(config_text: &str, request: &[u8]) -> Vec<u8> {
        let config = Config::parse(config_text).expect("test config parses");
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let (http, socks) = pac_ports(&config);
        let task = tokio::spawn(pac_accept_loop(
            listener,
            config,
            http,
            socks,
            shutdown_rx,
        ));
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        client.write_all(request).await.expect("write");
        let mut reply = Vec::new();
        client
            .read_to_end(&mut reply)
            .await
            .expect("read reply to EOF");
        let _ = shutdown_tx.send(true);
        task.abort();
        reply
    }

    #[tokio::test]
    async fn loop_serves_pac() {
        let reply = run_loop_once(
            "",
            b"GET /proxy.pac HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
        )
        .await;
        let text = String::from_utf8_lossy(&reply);
        assert!(text.starts_with("HTTP/1.1 200 OK\r\n"), "{text}");
        assert!(
            text.contains("Content-Type: application/x-ns-proxy-autoconfig\r\n"),
            "{text}"
        );
        assert!(text.contains("function FindProxyForURL"), "{text}");
        // The host in the PAC is the address the client connected to.
        assert!(text.contains("PROXY 127.0.0.1:1080; SOCKS5 127.0.0.1:1080; DIRECT"), "{text}");
    }

    #[tokio::test]
    async fn loop_serves_setup_page() {
        let reply = run_loop_once("", b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").await;
        let text = String::from_utf8_lossy(&reply);
        assert!(text.starts_with("HTTP/1.1 200 OK\r\n"), "{text}");
        assert!(text.contains("Content-Type: text/html; charset=utf-8\r\n"), "{text}");
        assert!(text.contains("Auto (PAC)"), "{text}");
        assert!(
            text.contains("http://127.0.0.1:8899/proxy.pac"),
            "the setup page links the PAC URL on the connected address, got: {text}"
        );
    }

    #[tokio::test]
    async fn loop_unknown_path_is_404() {
        let reply = run_loop_once("", b"GET /nope HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").await;
        let text = String::from_utf8_lossy(&reply);
        assert!(text.starts_with("HTTP/1.1 404 Not Found\r\n"), "{text}");
    }

    #[tokio::test]
    async fn loop_rejected_client_gets_nothing() {
        let reply = run_loop_once(
            "allowed_clients = 10.0.0.0/8",
            b"GET /proxy.pac HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
        )
        .await;
        assert!(
            reply.is_empty(),
            "a client outside allowed_clients gets no reply, got {} bytes",
            reply.len()
        );
    }

    #[tokio::test]
    async fn request_over_4kb_is_dropped() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let mut client = TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let (server, _) = listener.accept().await.expect("accept");
        let handle = tokio::spawn(handle_pac_conn(server, Some(8080), Some(1080), 8899, false));
        client
            .write_all(&vec![b'A'; 5000])
            .await
            .expect("write oversized request");
        let mut reply = Vec::new();
        client
            .read_to_end(&mut reply)
            .await
            .expect("read reply to EOF");
        assert!(
            reply.is_empty(),
            "an oversized request gets no reply, got {} bytes",
            reply.len()
        );
        handle.await.expect("handler finishes");
    }
}
