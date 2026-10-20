#!/system/bin/sh
# Launcher for agent_vd.dex (DaemonMain) on Android 9 / MIUI 10.
#
# Differences from the upstream script:
#  - BOOTCLASSPATH comes from system_server's own environment (exact for any SDK);
#  - the /apex fallback only applies to SDK >= 29 (Android 10+);
#  - an SDK 28 fallback list under /system/framework is used when /proc is unreadable.
#
# Usage: run_daemon.sh [WIDTH] [HEIGHT] [DPI]   (ignored in VD_NO_DISPLAY=1 helper mode)

MODDIR=${0%/*}/..
[ -d "$MODDIR/bin" ] || MODDIR=/data/adb/modules/agent_mobile_use_mi9

DEX_PATH="$MODDIR/bin/agent_vd.dex"
[ -f "$DEX_PATH" ] || DEX_PATH=/data/local/tmp/agent/agent_vd.dex
[ -f "$DEX_PATH" ] || DEX_PATH=/data/local/tmp/agent_vd_mi9.dex

WIDTH=1080
HEIGHT=2340
DPI=440

PHYS_SIZE=$(/system/bin/wm size 2>/dev/null | grep "Physical size:" | awk '{print $3}')
if [ -n "$PHYS_SIZE" ]; then
    W=$(echo "$PHYS_SIZE" | cut -d'x' -f1)
    H=$(echo "$PHYS_SIZE" | cut -d'x' -f2)
    [ -n "$W" ] && [ -n "$H" ] && WIDTH="$W" && HEIGHT="$H"
fi

OVERRIDE_DPI=$(/system/bin/wm density 2>/dev/null | grep "Override density:" | awk '{print $3}')
if [ -n "$OVERRIDE_DPI" ]; then
    DPI="$OVERRIDE_DPI"
else
    PHYS_DPI=$(/system/bin/wm density 2>/dev/null | grep "Physical density:" | awk '{print $3}')
    [ -n "$PHYS_DPI" ] && DPI="$PHYS_DPI"
fi

[ -n "$1" ] && [ -n "$2" ] && [ "$1" != "0" ] && WIDTH="$1" && HEIGHT="$2"
[ -n "$3" ] && [ "$3" != "0" ] && DPI="$3"

export ANDROID_ROOT=/system
export ANDROID_DATA=/data
[ -d /apex/com.android.art ] && export ANDROID_ART_ROOT=/apex/com.android.art
[ -d /apex/com.android.i18n ] && export ANDROID_I18N_ROOT=/apex/com.android.i18n
[ -d /apex/com.android.tzdata ] && export ANDROID_TZDATA_ROOT=/apex/com.android.tzdata

if [ -z "$BOOTCLASSPATH" ]; then
    SS_PID=$(/system/bin/pidof system_server 2>/dev/null | awk '{print $1}')
    if [ -n "$SS_PID" ] && [ -r "/proc/$SS_PID/environ" ]; then
        BCP_SS=$(tr '\0' '\n' < "/proc/$SS_PID/environ" | grep '^BOOTCLASSPATH=' | head -1 | cut -d= -f2-)
        D2O_SS=$(tr '\0' '\n' < "/proc/$SS_PID/environ" | grep '^DEX2OATBOOTCLASSPATH=' | head -1 | cut -d= -f2-)
        [ -n "$BCP_SS" ] && export BOOTCLASSPATH="$BCP_SS"
        [ -n "$D2O_SS" ] && export DEX2OATBOOTCLASSPATH="$D2O_SS"
    fi
fi

SDK_INT=$(getprop ro.build.version.sdk 2>/dev/null)
[ -z "$SDK_INT" ] && SDK_INT=28

if [ -z "$BOOTCLASSPATH" ] && [ "$SDK_INT" -lt 29 ] 2>/dev/null; then
    P_BCP=/system/framework/core-oj.jar:/system/framework/core-libart.jar:/system/framework/okhttp.jar:/system/framework/bouncycastle.jar:/system/framework/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar
    [ -f /system/framework/miui-framework.jar ] && P_BCP="$P_BCP:/system/framework/miui-framework.jar"
    export BOOTCLASSPATH="$P_BCP"
    export DEX2OATBOOTCLASSPATH="$P_BCP"
fi

[ -z "$DEX2OATBOOTCLASSPATH" ] && export DEX2OATBOOTCLASSPATH="$BOOTCLASSPATH"

export CLASSPATH="$DEX_PATH"
echo "[run_daemon] dex=$DEX_PATH size=${WIDTH}x${HEIGHT}@${DPI} helper=$VD_NO_DISPLAY"
exec /system/bin/app_process /system/bin com.agent.DaemonMain "$WIDTH" "$HEIGHT" "$DPI"
