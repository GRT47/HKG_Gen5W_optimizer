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

RESULTS_DIR="$INSTALL_DIR/results"
OUT_FILE="$RESULTS_DIR/boot_kernel_probe.txt"
mkdir -p /dev/hkg_tmp "$RESULTS_DIR" 2>/dev/null
chmod 777 /dev/hkg_tmp 2>/dev/null

BB=""
if [ -x /system/bin/busybox ]; then
    BB="/system/bin/busybox"
fi

# Wait for Android boot completion (max 120s)
waited=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$waited" -lt 120 ]; do
    sleep 2
    waited=$((waited + 2))
done

# Always reset session-only tweaks on a fresh boot
rm -f /dev/hkg_tmp/hkg_sysbench_session.json /data/local/tmp/hkg_sysbench_session.json 2>/dev/null
GUARD_FILE="$RESULTS_DIR/boot_restore_guard.conf"
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
        CLASSPATH="$INSTALL_DIR/bin/sysbench.jar" /system/bin/app_process /system/bin com.hkg.sysbench.SysBenchMain restore-anim "$anim_win" "$anim_trans" "$anim_dur" >/dev/null 2>&1
        rm -f "$GUARD_FILE" 2>/dev/null
    fi
fi

write_kv() {
    label="$1"
    path="$2"
    if [ -r "$path" ]; then
        if [ -n "$BB" ]; then
            val="$($BB tr '\n\r' '  ' < "$path" 2>/dev/null | $BB sed 's/[[:space:]]*$//')"
        else
            val="$(cat "$path" 2>/dev/null)"
        fi
        echo "$label = $val"
    else
        echo "$label = <missing>"
    fi
}

{
    echo "================================================================"
    echo " HKG Gen5W Boot Kernel & Tunables Probe"
    echo "================================================================"
    echo "Date             : $(date 2>/dev/null)"
    echo "Uptime           : $(cat /proc/uptime 2>/dev/null)"
    echo "Platform         : $(getprop ro.board.platform)"
    echo "Hardware         : $(getprop ro.hardware)"
    echo "Product2         : $(getprop ro.build.product2)"
    echo "Branch           : $(getprop ro.build.branch)"
    echo "Debuggable       : $(getprop ro.debuggable)"
    echo "PerfMode         : $(getprop ro.product.opt.performancemode)"
    echo "EventLogProp     : $(getprop persist.sys.eventlog.enabled)"
    echo "Svc.eventlogdump : $(getprop init.svc.eventlogdump)"
    echo "Svc.tcc_logd     : $(getprop init.svc.tcc_logd)"
    echo "Svc.klogger      : $(getprop init.svc.klogger)"
    echo "Svc.log_summary  : $(getprop init.svc.log_summary)"
    echo ""
    echo "[Kernel Version]"
    cat /proc/version 2>/dev/null
    echo ""
    echo "[MMC Block Queue (/sys/block/mmcblk0/queue)]"
    for f in scheduler read_ahead_kb nr_requests rq_affinity nomerges add_random rotational iostats; do
        write_kv "mmcblk0.$f" "/sys/block/mmcblk0/queue/$f"
    done
    echo ""
    echo "[VM Parameters (/proc/sys/vm)]"
    for f in swappiness dirty_ratio dirty_background_ratio dirty_expire_centisecs dirty_writeback_centisecs vfs_cache_pressure min_free_kbytes extra_free_kbytes overcommit_memory page-cluster; do
        write_kv "vm.$f" "/proc/sys/vm/$f"
    done
    echo ""
    echo "[Scheduler Parameters (/proc/sys/kernel)]"
    for f in sched_latency_ns sched_min_granularity_ns sched_wakeup_granularity_ns sched_rt_runtime_us sched_rt_period_us sched_compat_yield sched_child_runs_first; do
        write_kv "kernel.$f" "/proc/sys/kernel/$f"
    done
    echo ""
    echo "[KSM (/sys/kernel/mm/ksm)]"
    for f in run pages_to_scan sleep_millisecs pages_shared pages_sharing pages_unshared full_scans; do
        write_kv "ksm.$f" "/sys/kernel/mm/ksm/$f"
    done
    echo ""
    echo "[LowMemoryKiller (/sys/module/lowmemorykiller/parameters)]"
    for f in minfree adj debug_level cost; do
        write_kv "lmk.$f" "/sys/module/lowmemorykiller/parameters/$f"
    done
    echo ""
    echo "[CPUFreq (/sys/devices/system/cpu)]"
    for c in cpu0 cpu1 cpu2 cpu3; do
        if [ -d "/sys/devices/system/cpu/$c/cpufreq" ]; then
            for f in scaling_governor scaling_cur_freq scaling_min_freq scaling_max_freq scaling_available_governors scaling_available_frequencies; do
                write_kv "$c.$f" "/sys/devices/system/cpu/$c/cpufreq/$f"
            done
        else
            echo "$c.cpufreq = <not present>"
        fi
    done
    echo ""
    echo "[Thermal Zones (/sys/class/thermal)]"
    for z in /sys/class/thermal/thermal_zone*; do
        if [ -d "$z" ]; then
            zn="${z##*/}"
            write_kv "$zn.type" "$z/type"
            write_kv "$zn.temp" "$z/temp"
            write_kv "$zn.policy" "$z/policy"
        fi
    done
    echo ""
    echo "[Cgroup (/dev/cpuctl)]"
    write_kv "cpuctl.cpu.shares" "/dev/cpuctl/cpu.shares"
    write_kv "cpuctl.apps.cpu.shares" "/dev/cpuctl/apps/cpu.shares"
    write_kv "cpuctl.bg.cpu.shares" "/dev/cpuctl/apps/bg_non_interactive/cpu.shares"
    write_kv "cpuctl.bg.rt_runtime_us" "/dev/cpuctl/apps/bg_non_interactive/cpu.rt_runtime_us"
    echo ""
    echo "[IRQ Affinity Snapshot & Non-Destructive Write Capability Test]"
    TEST_IRQ=""
    if [ -n "$BB" ] && [ -r /proc/interrupts ]; then
        TEST_IRQ="$($BB awk '/mip4_ts|fts/ {gsub(":", "", $1); print $1; exit}' /proc/interrupts 2>/dev/null)"
    fi
    if [ -z "$TEST_IRQ" ] && [ -r /proc/irq/35/smp_affinity ]; then
        TEST_IRQ="35"
    fi
    if [ -n "$TEST_IRQ" ] && [ -r "/proc/irq/$TEST_IRQ/smp_affinity" ]; then
        if [ -n "$BB" ]; then
            cur_aff="$($BB tr -d ' \n\r' < "/proc/irq/$TEST_IRQ/smp_affinity" 2>/dev/null)"
        else
            cur_aff="$(cat "/proc/irq/$TEST_IRQ/smp_affinity" 2>/dev/null)"
        fi
        echo "test_irq = $TEST_IRQ (before=$cur_aff)"
        if echo "$cur_aff" > "/proc/irq/$TEST_IRQ/smp_affinity" 2>/dev/null; then
            echo "SMP_AFFINITY_WRITABLE=yes"
        else
            echo "SMP_AFFINITY_WRITABLE=no"
        fi
    else
        echo "SMP_AFFINITY_WRITABLE=unknown"
    fi
    for p in /proc/irq/*/smp_affinity; do
        if [ -r "$p" ]; then
            dirp="${p%/smp_affinity}"
            irqn="${dirp##*/}"
            if [ -n "$BB" ]; then
                val="$($BB tr -d '\n\r' < "$p" 2>/dev/null)"
            else
                val="$(cat "$p" 2>/dev/null)"
            fi
            echo "irq[$irqn].smp_affinity = $val"
        fi
    done
    echo ""
    echo "[/proc/interrupts]"
    cat /proc/interrupts 2>/dev/null
    echo ""
    echo "[/proc/meminfo]"
    cat /proc/meminfo 2>/dev/null
    echo ""
    echo "[/proc/mounts]"
    cat /proc/mounts 2>/dev/null
} > "$OUT_FILE" 2>&1

chmod 644 "$OUT_FILE" 2>/dev/null

# Copy probe to USB if an actual USB filesystem is mounted
for mp in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/media_rw/usb1; do
    if [ -n "$BB" ] && $BB grep -q " $mp " /proc/mounts 2>/dev/null; then
        mkdir -p "$mp/HKG_SysBench" 2>/dev/null
        cp "$OUT_FILE" "$mp/HKG_SysBench/boot_kernel_probe.txt" 2>/dev/null
        break
    fi
done

exit 0
