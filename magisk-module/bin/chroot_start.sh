#!/system/bin/sh
# Bring up the Ubuntu chroot that hosts DSH (DeepSeek Harness) on the phone itself.
# Idempotent: safe to run at boot and by hand.
ROOT=/data/local/ubuntu
LOG=$ROOT/root/dsh.log

[ -d "$ROOT" ] || { echo "[chroot] $ROOT missing"; exit 0; }

mkdir -p "$ROOT/proc" "$ROOT/sys" "$ROOT/dev/pts" "$ROOT/root"
mountpoint -q "$ROOT/proc" || mount -t proc proc "$ROOT/proc" 2>/dev/null
mountpoint -q "$ROOT/sys" || mount -t sysfs sysfs "$ROOT/sys" 2>/dev/null
mountpoint -q "$ROOT/dev" || mount -o bind /dev "$ROOT/dev" 2>/dev/null
mountpoint -q "$ROOT/dev/pts" || mount -t devpts devpts "$ROOT/dev/pts" 2>/dev/null

# AliDNS: 8.8.8.8 is frequently unreachable from mainland networks.
echo "nameserver 223.5.5.5" > "$ROOT/etc/resolv.conf"
echo "127.0.0.1 localhost" > "$ROOT/etc/hosts"

# Already running? Nothing to do.
if netstat -ltn 2>/dev/null | grep -q "127.0.0.1:3080"; then
    echo "[chroot] dsh web already listening on 3080"
    exit 0
fi

# The DeepSeek key lives beside the harness home, not in this script, so it can be
# rotated without touching the module. DSH resolves DEEPSEEK_API_KEY from the launch
# environment (the stored-refs path does not resolve for this provider).
ENV_FILE=$ROOT/root/.dsh/deepseek.env
APIKEY=""
if [ -f "$ENV_FILE" ]; then
    APIKEY=$(sed -n 's/^DEEPSEEK_API_KEY=//p' "$ENV_FILE" | head -1 | tr -d '\r')
fi

chroot "$ROOT" /bin/bash -c "export PATH=/opt/node/bin:/usr/bin:/bin; export HOME=/root; export DEEPSEEK_API_KEY='$APIKEY'; cd /root; setsid nohup dsh web --no-open > /root/dsh.log 2>&1 < /dev/null &"
sleep 6
if netstat -ltn 2>/dev/null | grep -q "127.0.0.1:3080"; then
    TOKEN=$(grep -o "token=[A-Za-z0-9_-]*" "$LOG" 2>/dev/null | head -1 | cut -d= -f2)
    echo "[chroot] dsh web up: http://127.0.0.1:3080/?token=$TOKEN"
else
    echo "[chroot] dsh web failed to start; see $LOG"
fi
