English | [简体中文](nas-fnos.zh-CN.md)

# Feiniu NAS (fnOS) as a relay

fnOS is built on **Debian 12 (bookworm)** — it is essentially an always-on Linux box, which makes it a great WOL relay. Especially useful when your router is a stock firmware with no shell: **leave the router alone and let the NAS do the broadcasting**.

Prerequisites: the NAS sits in the **same LAN** as the target host, and the NAS does not sleep.

## 1. Enable SSH

SSH is off by default on fnOS and must be enabled manually:

1. Web console → **System settings → SSH**
2. Turn on the **SSH service** switch
3. Turn on **Administrator privileges** (otherwise the admin account cannot log in)
4. Port defaults to `22` (changeable)

Then log in from a computer:

```sh
ssh <admin user>@<NAS IP> -p 22
```

## 2. Install socat

fnOS is Debian, so apt just works:

```sh
sudo apt update
sudo apt install -y socat
socat -V | head -3        # confirm WITH_UDP 1
```

## 3. Write the relay script (waits for the tunnel automatically)

After the NAS reboots, the tunnel (ZeroTier / Tailscale / wg) may come up later than the service, so put the waiting inside the process again:

```sh
sudo tee /usr/local/bin/wolrelay.sh >/dev/null <<'EOF'
#!/bin/sh
# find the tunnel NIC, wait for an IPv4, then exec socat and forward to the LAN broadcast
LAN_IF=${LAN_IF:-enp1s0}          # change to the NIC connected to the LAN
LAN_BCAST=$(ip -4 addr show "$LAN_IF" 2>/dev/null | awk '/brd /{for(i=1;i<=NF;i++) if($i=="brd"){print $(i+1); exit}}')
[ -z "$LAN_BCAST" ] && LAN_BCAST=255.255.255.255

while :; do
    IF=""
    for c in $(ip -o link show | awk -F': ' '{print $2}' | cut -d@ -f1); do
        case "$c" in zt*|tailscale*|wg*) IF="$c"; break;; esac
    done
    [ -z "$IF" ] && { sleep 2; continue; }
    TIP=$(ip -4 addr show "$IF" 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -n1)
    [ -n "$TIP" ] && break
    sleep 2
done

echo "ready: $TIP:9 ($IF) -> $LAN_BCAST:9"
exec socat -ly "UDP4-RECVFROM:9,bind=$TIP,reuseaddr,fork" \
             "UDP4-DATAGRAM:$LAN_BCAST:9,broadcast"
EOF
sudo chmod +x /usr/local/bin/wolrelay.sh
```

To find the NIC name: `ip -4 addr show | grep -E 'inet |brd'` and pick the one with a LAN address.

## 4. Register it as a systemd service

```sh
sudo tee /etc/systemd/system/wolrelay.service >/dev/null <<'EOF'
[Unit]
Description=WOL relay (UDP 9 -> LAN broadcast)
After=network-online.target tailscaled.service zerotier-one.service
Wants=network-online.target

[Service]
Type=simple
Environment=LAN_IF=enp1s0
ExecStart=/usr/local/bin/wolrelay.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now wolrelay
sudo systemctl status wolrelay --no-pager
```

Watch the log:

```sh
journalctl -u wolrelay -f
# expected: ready: 10.147.20.5:9 (ztab1234cd) -> 192.168.1.255:9
```

## 5. Verify

1. Confirm the port is listening: `sudo ss -lnup | grep ':9 '`
2. Send one wake packet from the phone (mobile data, tunnel connected)
3. `journalctl -u wolrelay -n 20` should show one 102-byte reception record, and the host should wake

If nothing appears in the log, the packet never arrived: check that the tunnel is connected and that `bind` is the tunnel NIC's address.

## 6. Firewall and port conflicts

- If fnOS has its firewall enabled (there is a switch under security/firewall in the console), allow **UDP 9**;
- Port taken: `sudo ss -lnup | grep ':9 '`, kill the other process with `sudo kill <pid>`, then `systemctl restart wolrelay`;
- **Do not** set `bind` to `0.0.0.0`: the NAS will receive its own broadcast back in a self-loop and peg the CPU.

## 7. Docker alternative (optional)

If you prefer containers, socat can run as a host-network container:

```sh
docker run -d --name wolrelay --restart=always --network host \
  alpine/socat -ly \
  "UDP4-RECVFROM:9,bind=<tunnel ip>,reuseaddr,fork" \
  "UDP4-DATAGRAM:<LAN broadcast>:9,broadcast"
```

The downside is having to recreate the container whenever the tunnel address changes, so the systemd + wait-script approach above is preferable.

## 8. Notes

- **The NAS must not sleep**: disk spin-down does not affect the NIC, but whole-system sleep or shutdown kills the relay;
- If the NAS itself also needs to be woken, that is a separate matter (WOL support on motherboard/NIC) and unrelated to the relay;
- When the tunnel address changes after a reconnect, just restart the process so the script picks up the new address: `sudo systemctl restart wolrelay`.
