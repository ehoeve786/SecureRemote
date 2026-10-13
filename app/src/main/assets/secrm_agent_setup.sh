#!/data/data/com.termux/files/usr/bin/bash
# Sets up a TCP forwarder from 127.0.0.1:<port> to Bitwarden's SSH agent.
PORT="${1:-6000}"
BW_SOCK="$HOME/.bitwarden-ssh-agent.sock"

if [ ! -S "$BW_SOCK" ]; then
    termux-toast "Bitwarden SSH agent not running. Enable it in Bitwarden → Settings → SSH Agent."
    exit 1
fi

pkg install -y socat >/dev/null 2>&1 || {
    termux-toast "Failed to install socat"
    exit 2
}

pkill -f "socat.*TCP-LISTEN:$PORT" 2>/dev/null

nohup socat TCP-LISTEN:$PORT,bind=127.0.0.1,reuseaddr,fork \
     UNIX-CONNECT:$BW_SOCK >/dev/null 2>&1 &

sleep 1

if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then
    termux-toast "Agent forwarder active on 127.0.0.1:$PORT"
    exit 0
else
    termux-toast "Forwarder failed to start"
    exit 3
fi
