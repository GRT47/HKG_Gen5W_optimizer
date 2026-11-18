#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
# Inherit BOOTCLASSPATH from init.environ.rc so .odex dependency tables stay valid.

if [ -d "/data/nemesis/apps/hkg.optimize" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.optimize"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.optimize"
fi

TMP_DIR="/dev/hkg_tmp"
mkdir -p "$TMP_DIR" 2>/dev/null
chmod 777 "$TMP_DIR" 2>/dev/null
mkdir -p /data/dalvik-cache /data/local/tmp/dalvik-cache "$INSTALL_DIR/config" 2>/dev/null

JAVA_PID_FILE="$TMP_DIR/hkg_optimize_java.pid"
CMD_FILE="$TMP_DIR/hkg_optimize_cmd"
LOG_FILE="$TMP_DIR/hkg_optimize.log"
APP_CP="$INSTALL_DIR/bin/optimize.jar"

is_opt_pid_alive() {
    pid="$1"
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -r "/proc/$pid/maps" ]; then
        if [ -x /system/bin/busybox ]; then
            /system/bin/busybox grep -q "optimize.jar" "/proc/$pid/maps" 2>/dev/null && return 0
        elif grep -q "optimize.jar" "/proc/$pid/maps" 2>/dev/null; then
            return 0
        fi
    fi
    if [ -r "/proc/$pid/cmdline" ] && [ -x /system/bin/busybox ]; then
        if /system/bin/busybox tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | /system/bin/busybox grep -qE "OptimizeMain|com\.hkg\.optimize|app_process"; then
            return 0
        fi
    fi
    return 1
}

# Synchronous CLI runner for ADB / SSH / scripting
case "$1" in
    cli-on)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain cli on "$INSTALL_DIR"
        ;;
    cli-off)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain cli off "$INSTALL_DIR"
        ;;
    cli-status)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain cli status "$INSTALL_DIR"
        ;;
    cli-apply-boot)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain apply-boot "$INSTALL_DIR"
        ;;
    cli-refresh-sched)
        CLASSPATH="$APP_CP" exec /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain refresh-sched "$INSTALL_DIR"
        ;;
esac

java_alive=0
java_pid=""
if [ -f "$JAVA_PID_FILE" ]; then
    java_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if is_opt_pid_alive "$java_pid"; then
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
        /system/bin/busybox pkill -f "com.hkg.optimize.OptimizeMain" >/dev/null 2>&1
        /system/bin/busybox pkill -f "com.hkg.optimize" >/dev/null 2>&1
    fi
    rm -f "$JAVA_PID_FILE" "$CMD_FILE" 2>/dev/null
    exit 0
fi

case "$1" in
    apply)
        CMD="apply"
        ;;
    revert)
        CMD="revert"
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
        /system/bin/busybox setsid /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain daemon "$INSTALL_DIR" </dev/null >>"$LOG_FILE" 2>&1 &
    else
        /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain daemon "$INSTALL_DIR" </dev/null >>"$LOG_FILE" 2>&1 &
    fi
    bg_pid="$!"
    if [ ! -s "$JAVA_PID_FILE" ] && [ -n "$bg_pid" ]; then
        echo "$bg_pid" > "$JAVA_PID_FILE" 2>/dev/null
    fi
fi

exit 0
