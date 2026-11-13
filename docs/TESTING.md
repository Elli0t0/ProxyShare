# Manual testing

Run through this on the phone after installing a new version over the
previous one. Mark each item pass/fail; a fail means a bug report with
the version number, not a silent retry.

## First run

- [ ] App opens (no crash on launch).
- [ ] Home shows Stopped, the address card lists the phone's Wi-Fi and
      hotspot IPs, and the credentials card shows the generated password
      (tap to reveal, copy works).
- [ ] The proxy starts (Start → Running, notification "Running on port
      <port>" appears) and stops (Stop → Stopped, notification gone).

## Laptop through the proxy

- [ ] Home → "Connect your laptop": pick the Wi-Fi address, copy the
      curl (HTTP) command, run it on the laptop — `ifconfig.me` returns
      the phone's IP, not the laptop's.
- [ ] Same with the curl (SOCKS5) command.
- [ ] Export the `http_proxy`/`https_proxy` line in a laptop terminal
      and open a website in the browser — it loads through the phone.
- [ ] With auth on, the commands include `user:pass@`; with auth off,
      they do not. A wrong password is rejected (curl exits 407/403).
- [ ] Windows: Settings → Network → Proxy → Manual proxy setup with the
      shown host/port routes traffic. Firefox: manual SOCKS v5 with
      "Proxy DNS when using SOCKS v5" routes traffic and resolves DNS
      through the proxy.

## Live monitor

- [ ] Connections tab with nothing connected shows the empty state.
- [ ] With laptop traffic flowing, the tab lists the client IP, and each
      row shows host:port, protocol, a moving live speed and a counting
      duration. Leaving the tab and coming back still updates.

## Settings

- [ ] Port 80 and 70000 are rejected (error shown, not saved); 8080 is
      accepted. Bind address lists the current interface IPs.
- [ ] Turning off the last protocol is not possible (its switch is
      disabled). Auth off/on keeps the stored password; Regenerate
      changes it.
- [ ] Adding `192.168.1.0/24` works; `300.1.1.1` and `1.2.3.4/33` are
      rejected. Removing a CIDR row removes it.
- [ ] Changing a setting while the proxy runs shows "Restart the proxy
      to apply"; Restart applies it (new port answers, old port does
      not). Theme System/Light/Dark applies at once, no restart.

## Outbound and exit IP

- [ ] "Check exit IP" shows the phone's public IP.
- [ ] VPN only: with the VPN on, traffic goes through it; turning the
      VPN off stops the proxy (kill switch) instead of falling back.
- [ ] Wi-Fi / Mobile mode: the proxy starts only when that network is
      available; otherwise it shows "No <mode> network available".

## Upgrade

- [ ] Installing over the previous version keeps settings, password and
      theme; the app still opens and the proxy still starts.
