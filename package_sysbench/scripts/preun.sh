#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

JAVA_PID_FILE="/dev/hkg_tmp/hkg_sysbench_java.pid"
CMD_FILE="/dev/hkg_tmp/hkg_sysbench_cmd"

# Revert any active session-only tweaks before uninstalling
if [ -d "/data/nemesis/apps/hkg.sysbench" ]; then
    INST_DIR="/data/nemesis/apps/hkg.sysbench"
else
    INST_DIR="/data/nemesis/packages/hkg.sysbench"
fi
if [ -f "$INST_DIR/bin/sysbench.jar" ]; then
    CLASSPATH="$INST_DIR/bin/sysbench.jar" /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain cli-tweaks off "$INST_DIR" >/dev/null 2>&1
fi
rm -f /dev/hkg_tmp/hkg_sysbench_session.json /data/local/tmp/hkg_sysbench_session.json >/dev/null 2>&1

echo "stop" > "$CMD_FILE" 2>/dev/null
if [ -f "$JAVA_PID_FILE" ]; then
    old_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        kill "$old_pid" >/dev/null 2>&1
    fi
fi

/system/bin/busybox pkill -f "com.hkg.sysbench.SysBenchMain" >/dev/null 2>&1
pkill -f "com.hkg.sysbench.SysBenchMain" >/dev/null 2>&1
rm -f "$JAVA_PID_FILE" "$CMD_FILE" /dev/hkg_tmp/hkg_sysbench.log /dev/hkg_tmp/hkg_sysbench.log.old /data/local/tmp/hkg_sysbench* >/dev/null 2>&1
rm -f /data/nemesis/packages/hkg.sysbench >/dev/null 2>&1
exit 0
