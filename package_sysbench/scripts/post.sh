#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data

if [ -d "/data/nemesis/apps/hkg.sysbench" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.sysbench"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.sysbench"
fi

mkdir -p /dev/hkg_tmp /data/nemesis/packages "$INSTALL_DIR/results" /data/dalvik-cache /data/local/tmp/dalvik-cache 2>/dev/null
chmod 777 /dev/hkg_tmp 2>/dev/null
if [ "$INSTALL_DIR" = "/data/nemesis/apps/hkg.sysbench" ]; then
    rm -f /data/nemesis/packages/hkg.sysbench 2>/dev/null
    ln -s "$INSTALL_DIR" /data/nemesis/packages/hkg.sysbench 2>/dev/null
fi

chmod 755 "$INSTALL_DIR/bin/launch.sh" "$INSTALL_DIR/svc/boot-probe.sh" 2>/dev/null
chmod 644 "$INSTALL_DIR/bin/sysbench.jar" "$INSTALL_DIR/NEMESIS.data" "$INSTALL_DIR/config/sysbench.conf" 2>/dev/null

# Clear stale Dalvik DEX caches and upgrade legacy mode=once service entry to mode=background
rm -f /data/dalvik-cache/*sysbench.jar* /data/local/tmp/dalvik-cache/*sysbench.jar* 2>/dev/null
mkdir -p /data/nemesis/pids 2>/dev/null
rm -f /data/nemesis/pids/hkg.sysbench+* 2>/dev/null
if [ -f "/data/nemesis/services.db" ] && grep "hkg.sysbench/sysbench-bootprobe.*|once" /data/nemesis/services.db >/dev/null 2>&1; then
    if [ -x /system/bin/busybox ]; then
        /system/bin/busybox sed 's/\(hkg\.sysbench\/sysbench-bootprobe|[^|]*|[^|]*|[^|]*|\)once/\1background/g' /data/nemesis/services.db > /data/nemesis/services.db.tmp 2>/dev/null
    fi
    if [ -s "/data/nemesis/services.db.tmp" ]; then
        mv -f /data/nemesis/services.db.tmp /data/nemesis/services.db 2>/dev/null
    else
        rm -f /data/nemesis/services.db.tmp 2>/dev/null
    fi
fi

# Clean up any leftover SysBench process from an earlier install/run
JAVA_PID_FILE="/dev/hkg_tmp/hkg_sysbench_java.pid"
if [ -f "$JAVA_PID_FILE" ]; then
    old_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        kill "$old_pid" >/dev/null 2>&1
    fi
fi
/system/bin/busybox pkill -f "com.hkg.sysbench.SysBenchMain" >/dev/null 2>&1
pkill -f "com.hkg.sysbench.SysBenchMain" >/dev/null 2>&1
rm -f "$JAVA_PID_FILE" /dev/hkg_tmp/hkg_sysbench_cmd /data/local/tmp/hkg_sysbench_java.pid /data/local/tmp/hkg_sysbench_cmd 2>/dev/null

# Open SysBench overlay immediately after installation (NEMESIS svcmgr starts svc/boot-probe.sh in mode=background)
/system/bin/sh "$INSTALL_DIR/bin/launch.sh" show </dev/null >/dev/null 2>&1 &

exit 0
