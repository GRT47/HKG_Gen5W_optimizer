#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
if [ -z "$BOOTCLASSPATH" ]; then
    export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
fi

TMP_DIR="/dev/hkg_tmp"
JAVA_PID_FILE="$TMP_DIR/hkg_optimize_java.pid"
CMD_FILE="$TMP_DIR/hkg_optimize_cmd"

# Revert any active optimizations before uninstalling
if [ -d "/data/nemesis/apps/hkg.optimize" ]; then
    INST_DIR="/data/nemesis/apps/hkg.optimize"
else
    INST_DIR="/data/nemesis/packages/hkg.optimize"
fi
if [ -f "$INST_DIR/bin/optimize.jar" ]; then
    CLASSPATH="$INST_DIR/bin/optimize.jar" /system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain cli off "$INST_DIR" >/dev/null 2>&1
fi

echo "stop" > "$CMD_FILE" 2>/dev/null
if [ -f "$JAVA_PID_FILE" ]; then
    old_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        kill "$old_pid" >/dev/null 2>&1
    fi
fi

/system/bin/busybox pkill -f "com.hkg.optimize.OptimizeMain" >/dev/null 2>&1
pkill -f "com.hkg.optimize.OptimizeMain" >/dev/null 2>&1
/system/bin/busybox pkill -f "com.hkg.optimize" >/dev/null 2>&1
if [ -f "/system/framework/hmgapi-hook.jar" ] && [ -d "/data/hmgapi/mods/hkg-optimize" ]; then
    CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine remove hkg-optimize --from-nemesis >/dev/null 2>&1
    if [ -x /system/bin/hmgapid ]; then
        /system/bin/hmgapid engine apply >/dev/null 2>&1
    fi
    pm uninstall com.hkg.optimize.app >/dev/null 2>&1
fi

rm -f "$TMP_DIR/hkg_optimize"* >/dev/null 2>&1
rm -f /data/nemesis/packages/hkg.optimize >/dev/null 2>&1
exit 0
