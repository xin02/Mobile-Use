#!/system/bin/sh
# Magisk installer for the Android 9 adaptation of agent-mobile-use.
SKIPUNZIP=0

ui_print "- Agent Mobile Use (mi9 / Android 9)"
ui_print "- SDK: $(getprop ro.build.version.sdk)  device: $(getprop ro.product.model)"

mkdir -p /data/local/tmp/agent
chmod 0777 /data/local/tmp/agent

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/run_daemon.sh" 0 0 0755
set_perm "$MODPATH/bin/chroot_start.sh" 0 0 0755
set_perm "$MODPATH/bin/vd_server_mi9" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

# Publish a copy under /data/local/tmp so a broken module can still be driven manually.
cp -f "$MODPATH/bin/agent_vd.dex" /data/local/tmp/agent/agent_vd.dex
cp -f "$MODPATH/bin/vd_server_mi9" /data/local/tmp/agent/vd_server_mi9
cp -f "$MODPATH/bin/run_daemon.sh" /data/local/tmp/agent/run_daemon.sh
chmod 0755 /data/local/tmp/agent/vd_server_mi9 /data/local/tmp/agent/run_daemon.sh

ui_print "- Installed. Reboot to start the gateway on 127.0.0.1:3070."

# DSH agent preset registration: without this patch the mobile-use preset never
# appears in the DSH preset dropdown (the stock composition only ships
# standard/ptc/minimal/cordis), so the model answers "cannot operate the phone".
# Applied at install time because the chroot is already provisioned by then.
if [ -d /data/local/ubuntu/root/.dsh/profiles/web ]; then
    cp -f "$MODPATH/bin/dsh-cordis.patch.yml" /data/local/ubuntu/root/.dsh/profiles/web/cordis.patch.yml
    ui_print "- DSH mobile-use preset patch installed"
fi
