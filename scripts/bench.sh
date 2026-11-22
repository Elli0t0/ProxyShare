#!/bin/sh
# ProxyShare benchmark — run on a laptop on the same Wi-Fi or hotspot
# as the phone, with the proxy running.
# Usage: sh scripts/bench.sh PHONE_IP [PORT]
#   PHONE_IP  the phone's address on the laptop's network
#   PORT      the SOCKS port (default 1080; split-mode default)
# Prints (a) 20 sequential new connections through socks5h with
# connect / TLS / first-byte timings, and (b) a 50 MB download with
# its average speed. Needs curl.
set -u

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }

PHONE_IP="${1:-}"
if [ -z "$PHONE_IP" ]; then
    echo "usage: sh scripts/bench.sh PHONE_IP [PORT]" >&2
    exit 1
fi
PORT="${2:-1080}"
PROXY="socks5h://$PHONE_IP:$PORT"

echo "== 20 sequential connections via $PROXY =="
i=1
while [ "$i" -le 20 ]; do
    curl -s -o /dev/null --proxy "$PROXY" \
        -w "conn $i: connect=%{time_connect}s tls=%{time_appconnect}s first-byte=%{time_starttransfer}s total=%{time_total}s\n" \
        https://example.com/
    i=$((i + 1))
done

echo "== 50 MB download =="
curl -s -o /dev/null --proxy "$PROXY" \
    -w "download: %{speed_download} bytes/s in %{time_total}s\n" \
    "https://speed.cloudflare.com/__down?bytes=50000000"
