#!/system/bin/sh
# Late-start service for the mi9 adaptation.
# Starts (1) the privileged helper (clipboard/injection) and (2) the HTTP gateway.

MODDIR=${0%/*}
AGENT_DIR=/data/local/tmp/agent
LOG=$AGENT_DIR/service.log

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done
sleep 5

mkdir -p "$AGENT_DIR"
chmod 0777 "$AGENT_DIR"
rm -f "$AGENT_DIR/vd_stop"

{
    echo "[service] $(date) starting on SDK $(getprop ro.build.version.sdk)"
} >> "$LOG" 2>&1

start_helper() {
    if pgrep -x app_process >/dev/null 2>&1; then
        echo "[service] helper already running" >> "$LOG"
        return
    fi
    VD_NO_DISPLAY=1 nohup sh "$MODDIR/bin/run_daemon.sh" 0 0 0 >> "$AGENT_DIR/helper.log" 2>&1 &
    echo "[service] helper started (pid $!)" >> "$LOG"
}

start_gateway() {
    if pgrep -x vd_server_mi9 >/dev/null 2>&1; then
        echo "[service] gateway already running" >> "$LOG"
        return
    fi
    nohup "$MODDIR/bin/vd_server_mi9" >> "$AGENT_DIR/gateway.log" 2>&1 &
    echo "[service] gateway started (pid $!)" >> "$LOG"
}

start_helper
sleep 3
start_gateway

# DSH (the agent brain) lives in the Ubuntu chroot on the phone itself; bring it up too.
if [ -x "$MODDIR/bin/chroot_start.sh" ]; then
    "$MODDIR/bin/chroot_start.sh" >> "$AGENT_DIR/chroot.log" 2>&1
fi

# Keep the gateway alive if it exits (MIUI kills non-foreground processes aggressively).
(
    while true; do
        sleep 30
        pgrep -x vd_server_mi9 >/dev/null 2>&1 || start_gateway
        pgrep -x app_process >/dev/null 2>&1 || start_helper
    done
) &
