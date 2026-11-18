#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
# Inherit BOOTCLASSPATH from init.environ.rc so .odex dependency tables stay valid.

if [ -d "/data/nemesis/apps/hkg.sysbench" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.sysbench"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.sysbench"
fi
mkdir -p /dev/hkg_tmp /data/dalvik-cache /data/local/tmp/dalvik-cache "$INSTALL_DIR/results" 2>/dev/null
chmod 777 /dev/hkg_tmp 2>/dev/null

JAVA_PID_FILE="/dev/hkg_tmp/hkg_sysbench_java.pid"
CMD_FILE="/dev/hkg_tmp/hkg_sysbench_cmd"
LOG_FILE="/dev/hkg_tmp/hkg_sysbench.log"
APP_CP="$INSTALL_DIR/bin/sysbench.jar"

is_sysbench_pid_alive() {
    pid="$1"
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -r "/proc/$pid/maps" ]; then
        if [ -x /system/bin/busybox ]; then
            /system/bin/busybox grep -q "sysbench.jar" "/proc/$pid/maps" 2>/dev/null && return 0
        elif grep -q "sysbench.jar" "/proc/$pid/maps" 2>/dev/null; then
            return 0
        fi
    fi
    if [ -r "/proc/$pid/cmdline" ] && [ -x /system/bin/busybox ]; then
        if /system/bin/busybox tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | /system/bin/busybox grep -qE "SysBenchMain|app_process"; then
            return 0
        fi
    fi
    return 1
}

# Synchronous CLI runner for ADB / SSH / emulator verification
case "$1" in
    cli-before)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain cli before "$INSTALL_DIR"
        ;;
    cli-after)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain cli after "$INSTALL_DIR"
        ;;
    cli-quick)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain cli quick "$INSTALL_DIR"
        ;;
    cli-tweaks-on)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain cli-tweaks on "$INSTALL_DIR"
        ;;
    cli-tweaks-off)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain cli-tweaks off "$INSTALL_DIR"
        ;;
    cli-tweaks-status)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain cli-tweaks status "$INSTALL_DIR"
        ;;
esac

java_alive=0
java_pid=""
if [ -f "$JAVA_PID_FILE" ]; then
    java_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if is_sysbench_pid_alive "$java_pid"; then
        java_alive=1
    else
        rm -f "$JAVA_PID_FILE" 2>/dev/null
        java_pid=""
    fi
fi

if [ "$1" = "stop" ]; then
    echo "stop" > "$CMD_FILE" 2>/dev/null
    sleep 1
    if [ "$java_alive" = "1" ] && [ -n "$java_pid" ]; then
        kill "$java_pid" >/dev/null 2>&1
    fi
    if [ -x /system/bin/busybox ]; then
        /system/bin/busybox pkill -f "com.hkg.sysbench.SysBenchMain" >/dev/null 2>&1
    fi
    rm -f "$JAVA_PID_FILE" "$CMD_FILE" 2>/dev/null
    exit 0
fi

case "$1" in
    before)
        CMD="run_before"
        ;;
    after)
        CMD="run_after"
        ;;
    quick)
        CMD="run_quick"
        ;;
    export)
        CMD="export"
        ;;
    toggle)
        CMD="toggle"
        ;;
    *)
        CMD="show"
        ;;
esac

echo "$CMD" > "$CMD_FILE" 2>/dev/null
chmod 666 "$CMD_FILE" 2>/dev/null

if [ "$java_alive" = "0" ]; then
    if [ -f "$LOG_FILE" ] && [ -x /system/bin/busybox ]; then
        log_sz="$(/system/bin/busybox wc -c < "$LOG_FILE" 2>/dev/null)"
        if [ -n "$log_sz" ] && [ "$log_sz" -gt 262144 ] 2>/dev/null; then
            mv -f "$LOG_FILE" "${LOG_FILE}.old" 2>/dev/null
        fi
    fi
    trap '' HUP
    export CLASSPATH="$APP_CP"
    if [ -x /system/bin/busybox ]; then
        /system/bin/busybox setsid /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain daemon "$INSTALL_DIR" </dev/null >>"$LOG_FILE" 2>&1 &
    else
        /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain daemon "$INSTALL_DIR" </dev/null >>"$LOG_FILE" 2>&1 &
    fi
    bg_pid="$!"
    if [ ! -s "$JAVA_PID_FILE" ] && [ -n "$bg_pid" ]; then
        echo "$bg_pid" > "$JAVA_PID_FILE" 2>/dev/null
    fi
fi

exit 0
