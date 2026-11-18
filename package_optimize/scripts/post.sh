#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
if [ -z "$BOOTCLASSPATH" ]; then
    export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
fi

if [ -d "/data/nemesis/apps/hkg.optimize" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.optimize"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.optimize"
fi

TMP_DIR="/dev/hkg_tmp"
mkdir -p "$TMP_DIR" 2>/dev/null
chmod 777 "$TMP_DIR" 2>/dev/null
mkdir -p /data/nemesis/packages /data/nemesis/apps "$INSTALL_DIR/config" /data/dalvik-cache /data/local/tmp/dalvik-cache 2>/dev/null

if [ "$INSTALL_DIR" = "/data/nemesis/apps/hkg.optimize" ]; then
    rm -f /data/nemesis/packages/hkg.optimize 2>/dev/null
    ln -s "$INSTALL_DIR" /data/nemesis/packages/hkg.optimize 2>/dev/null
elif [ ! -e "/data/nemesis/apps/hkg.optimize" ]; then
    ln -s "$INSTALL_DIR" /data/nemesis/apps/hkg.optimize 2>/dev/null
fi

chmod 755 "$INSTALL_DIR/bin/launch.sh" "$INSTALL_DIR/svc/boot-apply.sh" "$INSTALL_DIR/hmgapi_mod/bin/open-ui.sh" 2>/dev/null
chmod 644 "$INSTALL_DIR/bin/optimize.jar" "$INSTALL_DIR/NEMESIS.data" "$INSTALL_DIR/config/optimize.conf" 2>/dev/null

# Clear stale Dalvik DEX caches and old NEMESIS service markers / mode=once entries
rm -f /data/dalvik-cache/*optimize.jar* /data/local/tmp/dalvik-cache/*optimize.jar* 2>/dev/null
mkdir -p /data/nemesis/pids 2>/dev/null
rm -f /data/nemesis/pids/hkg.optimize+* 2>/dev/null
if [ -f "/data/nemesis/services.db" ] && grep "hkg.optimize/optimize-bootapply.*|once" /data/nemesis/services.db >/dev/null 2>&1; then
    if [ -x /system/bin/busybox ]; then
        /system/bin/busybox sed 's/\(hkg\.optimize\/optimize-bootapply|[^|]*|[^|]*|[^|]*|\)once/\1background/g' /data/nemesis/services.db > /data/nemesis/services.db.tmp 2>/dev/null
    fi
    if [ -s "/data/nemesis/services.db.tmp" ]; then
        mv -f /data/nemesis/services.db.tmp /data/nemesis/services.db 2>/dev/null
    else
        rm -f /data/nemesis/services.db.tmp 2>/dev/null
    fi
fi

# Clean up any leftover Optimize process from an earlier install/run
JAVA_PID_FILE="$TMP_DIR/hkg_optimize_java.pid"
if [ -f "$JAVA_PID_FILE" ]; then
    old_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        kill "$old_pid" >/dev/null 2>&1
    fi
fi
/system/bin/busybox pkill -f "com.hkg.optimize.OptimizeMain" >/dev/null 2>&1
pkill -f "com.hkg.optimize.OptimizeMain" >/dev/null 2>&1
/system/bin/busybox pkill -f "com.hkg.optimize" >/dev/null 2>&1
rm -f "$JAVA_PID_FILE" "$TMP_DIR/hkg_optimize_cmd" 2>/dev/null

# Register Home Screen shortcut mod if HMG-API is installed on this head unit
if [ -f "/system/framework/hmgapi-hook.jar" ] && [ -d "/data/hmgapi" ] && [ -d "$INSTALL_DIR/hmgapi_mod" ]; then
    HMG_LOG="$TMP_DIR/hkg_optimize_hmgapi.log"
    MOD_KEY_DIR="/data/hmgapi/local-keys"
    MOD_KEY="$MOD_KEY_DIR/hkg-optimize.key"
    STAGE_ROOT="$TMP_DIR/hkg_opt_mod_stage"
    STAGE_MOD="$STAGE_ROOT/hkg-optimize"
    echo "=== hkg.optimize HMG-API sync $(date 2>/dev/null) ===" > "$HMG_LOG" 2>/dev/null
    mkdir -p "$MOD_KEY_DIR" 2>/dev/null
    chmod 700 "$MOD_KEY_DIR" 2>/dev/null
    if [ ! -s "$MOD_KEY" ]; then
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Signing keygen "$MOD_KEY" >>"$HMG_LOG" 2>&1
    fi
    rm -rf "$STAGE_ROOT" 2>/dev/null
    mkdir -p "$STAGE_MOD/bin" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/mod.json" "$STAGE_MOD/mod.json" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/HkgOptimizeShortcut.apk" "$STAGE_MOD/HkgOptimizeShortcut.apk" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/"*.png "$STAGE_MOD/" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/bin/open-ui.sh" "$STAGE_MOD/bin/open-ui.sh" 2>/dev/null
    chmod 755 "$STAGE_MOD/bin/open-ui.sh" 2>/dev/null

    CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Signing sign "$STAGE_MOD" "$MOD_KEY" >>"$HMG_LOG" 2>&1

    case "$(pm path com.hkg.optimize.app 2>/dev/null)" in
        package:*)
            pm uninstall com.hkg.optimize.app >>"$HMG_LOG" 2>&1
            ;;
    esac

    if ! CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine install "$STAGE_MOD" --from-nemesis --force >>"$HMG_LOG" 2>&1; then
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine remove hkg-optimize --from-nemesis >>"$HMG_LOG" 2>&1
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine install "$STAGE_MOD" --from-nemesis --force >>"$HMG_LOG" 2>&1
    fi

    if [ -x /system/bin/hmgapid ]; then
        /system/bin/hmgapid engine apply >>"$HMG_LOG" 2>&1
    else
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine apps >>"$HMG_LOG" 2>&1
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine publish-state >>"$HMG_LOG" 2>&1
    fi
    rm -rf "$STAGE_ROOT" 2>/dev/null
fi

# Open Optimize overlay immediately after installation for visual confirmation
/system/bin/sh "$INSTALL_DIR/bin/launch.sh" show </dev/null >/dev/null 2>&1 &

exit 0
