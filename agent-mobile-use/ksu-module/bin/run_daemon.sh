#!/system/bin/sh
MODDIR="/data/adb/modules/agent_mobile_use"
if [ ! -d "$MODDIR" ]; then
    MODDIR="$(cd "$(dirname "$0")/.." && pwd)"
fi

DEX_PATH="$MODDIR/bin/agent_vd.dex"
if [ ! -f "$DEX_PATH" ]; then
    DEX_PATH="/data/local/tmp/agent_vd.dex"
fi

# Auto detect physical screen metrics
WIDTH=1080
HEIGHT=2400
DPI=420

PHYS_SIZE=$(/system/bin/wm size 2>/dev/null | grep "Physical size:" | awk '{print $3}')
if [ -n "$PHYS_SIZE" ]; then
    W=$(echo "$PHYS_SIZE" | cut -d'x' -f1)
    H=$(echo "$PHYS_SIZE" | cut -d'x' -f2)
    if [ -n "$W" ] && [ -n "$H" ]; then
        WIDTH="$W"
        HEIGHT="$H"
    fi
fi

# Fix Issue #14: Prefer Override density over Physical density
OVERRIDE_DPI=$(/system/bin/wm density 2>/dev/null | grep "Override density:" | awk '{print $3}')
if [ -n "$OVERRIDE_DPI" ]; then
    DPI="$OVERRIDE_DPI"
else
    PHYS_DPI=$(/system/bin/wm density 2>/dev/null | grep "Physical density:" | awk '{print $3}')
    if [ -n "$PHYS_DPI" ]; then
        DPI="$PHYS_DPI"
    fi
fi

# Allow override from CLI args if provided
if [ -n "$1" ] && [ -n "$2" ]; then
    WIDTH="$1"
    HEIGHT="$2"
fi
if [ -n "$3" ]; then
    DPI="$3"
fi

echo "[run_daemon] Auto detected screen metrics: ${WIDTH}x${HEIGHT} @ ${DPI} DPI"

export ANDROID_ROOT=/system
export ANDROID_DATA=/data

# Android 10+ moved ART/i18n/tzdata into APEX. Only export these when they exist,
# otherwise app_process inherits a non-existent runtime root and dies on start.
[ -d /apex/com.android.art ] && export ANDROID_ART_ROOT=/apex/com.android.art
[ -d /apex/com.android.i18n ] && export ANDROID_I18N_ROOT=/apex/com.android.i18n
[ -d /apex/com.android.tzdata ] && export ANDROID_TZDATA_ROOT=/apex/com.android.tzdata

# Best source of truth: the classpath system_server was actually booted with.
# Root can read /proc/<pid>/environ; this is exact for any Android version.
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
if [ -z "$SDK_INT" ]; then SDK_INT=28; fi

# Fallback for Android 9 and older: no APEX, classes live under /system/framework.
if [ -z "$BOOTCLASSPATH" ] && [ "$SDK_INT" -lt 29 ] 2>/dev/null; then
    P_BCP=/system/framework/core-oj.jar:/system/framework/core-libart.jar:/system/framework/okhttp.jar:/system/framework/bouncycastle.jar:/system/framework/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar
    [ -f /system/framework/miui-framework.jar ] && P_BCP="$P_BCP:/system/framework/miui-framework.jar"
    export BOOTCLASSPATH="$P_BCP"
    export DEX2OATBOOTCLASSPATH="$P_BCP"
fi

if [ -z "$BOOTCLASSPATH" ]; then
    export BOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.adservices/javalib/framework-adservices.jar:/apex/com.android.adservices/javalib/framework-sdksandbox.jar:/apex/com.android.appsearch/javalib/framework-appsearch.jar:/apex/com.android.configinfrastructure/javalib/framework-configinfrastructure.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar:/apex/com.android.crashrecovery/javalib/framework-crashrecovery.jar:/apex/com.android.devicelock/javalib/framework-devicelock.jar:/apex/com.android.healthfitness/javalib/framework-healthfitness.jar:/apex/com.android.ipsec/javalib/android.net.ipsec.ike.jar:/apex/com.android.media/javalib/updatable-media.jar:/apex/com.android.mediaprovider/javalib/framework-mediaprovider.jar:/apex/com.android.mediaprovider/javalib/framework-pdf.jar:/apex/com.android.mediaprovider/javalib/framework-pdf-v.jar:/apex/com.android.mediaprovider/javalib/framework-photopicker.jar:/apex/com.android.ondevicepersonalization/javalib/framework-ondevicepersonalization.jar:/apex/com.android.os.statsd/javalib/framework-statsd.jar:/apex/com.android.permission/javalib/framework-permission.jar:/apex/com.android.permission/javalib/framework-permission-s.jar:/apex/com.android.profiling/javalib/framework-profiling.jar:/apex/com.android.scheduling/javalib/framework-scheduling.jar:/apex/com.android.sdkext/javalib/framework-sdkextensions.jar:/apex/com.android.tethering/javalib/framework-connectivity.jar:/apex/com.android.tethering/javalib/framework-connectivity-b.jar:/apex/com.android.tethering/javalib/framework-connectivity-t.jar:/apex/com.android.tethering/javalib/framework-tethering.jar:/apex/com.android.uwb/javalib/framework-ranging.jar:/apex/com.android.uwb/javalib/framework-uwb.jar:/apex/com.android.virt/javalib/framework-virtualization.jar:/apex/com.android.wifi/javalib/framework-wifi.jar

fi
if [ -z "$DEX2OATBOOTCLASSPATH" ]; then
    export DEX2OATBOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar

fi
export CLASSPATH="$DEX_PATH"
exec /system/bin/app_process /system/bin com.agent.DaemonMain "$WIDTH" "$HEIGHT" "$DPI"
