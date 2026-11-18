#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
if [ -z "$BOOTCLASSPATH" ]; then
    export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
fi
trap '' HUP
# Inherit BOOTCLASSPATH from init.environ.rc when present, otherwise use Gen5W fallback.

if [ -d "/data/nemesis/apps/hkg.optimize" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.optimize"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.optimize"
fi

TMP_DIR="/dev/hkg_tmp"
mkdir -p "$TMP_DIR" 2>/dev/null
chmod 777 "$TMP_DIR" 2>/dev/null
mkdir -p /data/dalvik-cache /data/local/tmp/dalvik-cache "$INSTALL_DIR/config" 2>/dev/null

CONF_FILE="$INSTALL_DIR/config/optimize.conf"
GUARD_FILE="$INSTALL_DIR/config/anim_guard.conf"
BOOT_LOG="$TMP_DIR/hkg_optimize_boot.log"
APP_CP="$INSTALL_DIR/bin/optimize.jar"

# Wait for Android boot completion (max 120s)
waited=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$waited" -lt 120 ]; do
    sleep 2
    waited=$((waited + 2))
done

# Give early system services a short settle window
sleep 8

auto_boot="1"
if [ -f "$CONF_FILE" ]; then
    while IFS='=' read -r ck cv || [ -n "$ck" ]; do
        case "$ck" in
            auto_boot)
                case "$cv" in
                    0*|[Ff][Aa][Ll][Ss][Ee]*) auto_boot="0" ;;
                    1*|[Tt][Rr][Uu][Ee]*) auto_boot="1" ;;
                esac
                ;;
        esac
    done < "$CONF_FILE"
fi

if [ "$auto_boot" = "1" ]; then
    echo "=== hkg.optimize boot-apply $(date 2>/dev/null) ===" > "$BOOT_LOG" 2>/dev/null
    CLASSPATH="$APP_CP" /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain apply-boot "$INSTALL_DIR" >>"$BOOT_LOG" 2>&1

    # Wait for late navigation / telematics / external bridge daemons, then refresh scheduler/prio
    sleep 35
    echo "=== hkg.optimize refresh-sched $(date 2>/dev/null) ===" >> "$BOOT_LOG" 2>/dev/null
    CLASSPATH="$APP_CP" /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain refresh-sched "$INSTALL_DIR" >>"$BOOT_LOG" 2>&1
    exit 0
fi

# auto_boot=0: recover Settings animation scales if headunit rebooted while tweaks were active
if [ -f "$GUARD_FILE" ]; then
    cur_boot="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    saved_boot=""
    anim_win="1.0"
    anim_trans="1.0"
    anim_dur="1.0"
    while IFS='=' read -r gk gv; do
        case "$gk" in
            boot_id) saved_boot="$gv" ;;
            anim_win) anim_win="$gv" ;;
            anim_trans) anim_trans="$gv" ;;
            anim_dur) anim_dur="$gv" ;;
        esac
    done < "$GUARD_FILE"
    if [ -n "$saved_boot" ] && [ "$saved_boot" != "$cur_boot" ]; then
        CLASSPATH="$APP_CP" /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain restore-anim "$anim_win" "$anim_trans" "$anim_dur" >/dev/null 2>&1
    fi
    rm -f "$GUARD_FILE" 2>/dev/null
fi

exit 0
