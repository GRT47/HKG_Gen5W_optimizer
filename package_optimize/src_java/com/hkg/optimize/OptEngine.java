package com.hkg.optimize;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Core kernel, VM, eMMC queue, multi-thread scheduler, IRQ dispersion, logger,
 * KSM, and WindowManager animation optimizer for Gen5W (TCC897x / Android 4.4.2).
 */
public final class OptEngine {

    public static final String[] LOGGER_SERVICES = new String[] {
        "eventlogdump",
        "tcc_logd",
        "klogger",
        "log_summary",
        "logcatdump",
        "mtlc_logging"
    };

    private static final String[] CRITICAL_IRQ_TOKENS = new String[] {
        "timer", "arch_timer", "wdt", "watchdog", "uart", "serial", "tty",
        "ipi", "resched", "function call", "single function", "pmu", "perf"
    };

    private static final String[] GC_THREAD_TOKENS = new String[] {
        "gc", "compiler", "heap", "signal catcher", "referencequeue", "finalizer", "jdwp", "jit"
    };

    public enum ProcRole {
        NONE,
        PROTECTED_SELF,
        SURFACEFLINGER,
        SYSTEM_SERVER,
        NAVI,
        FOREGROUND_UI,
        EXTERNAL_DAEMON,
        DXB_BLOAT,
        BG_BLOAT
    }

    public static final class LiveStatus {
        public String vfsCachePressure = "?";
        public String swappiness = "?";
        public String readAheadKb = "?";
        public String rqAffinity = "?";
        public String blockDev = "mmcblk0";
        public String ksmRun = "?";
        public String ksmSleepMs = "?";
        public int runningLoggers = 0;
        public double cpu0IrqPct = -1.0;
        public int dispersedIrqs = 0;
        public float animWin = 1.0f;
        public float animTrans = 1.0f;
        public float animDur = 1.0f;
        public int schedBoostedThreads = 0;
        public int politeDaemons = 0;
        public final Set<String> activeTweaks = new LinkedHashSet<String>();
    }

    private static Method mSetThreadPriority;
    private static boolean mSetThreadPriorityChecked = false;
    private static Method mSysPropGet;
    private static Method mSysPropSet;
    private static boolean mSysPropChecked = false;

    private OptEngine() {
    }

    public static BaselineCaptureResult ensureBaseline(Object androidContext) {
        OptModel.BaselineState st = OptModel.loadBaseline();
        if (st != null) {
            return new BaselineCaptureResult(st, false);
        }
        st = captureFreshBaseline(androidContext);
        OptModel.saveBaseline(st);
        return new BaselineCaptureResult(st, true);
    }

    public static final class BaselineCaptureResult {
        public final OptModel.BaselineState state;
        public final boolean freshlyCaptured;

        public BaselineCaptureResult(OptModel.BaselineState state, boolean freshlyCaptured) {
            this.state = state;
            this.freshlyCaptured = freshlyCaptured;
        }
    }

    public static OptModel.BaselineState captureFreshBaseline(Object androidContext) {
        OptModel.BaselineState st = new OptModel.BaselineState();
        st.bootId = OptModel.readBootId();
        st.ksmRun = OptModel.readTrimmed("/sys/kernel/mm/ksm/run");
        st.ksmSleepMs = OptModel.readTrimmed("/sys/kernel/mm/ksm/sleep_millisecs");
        st.vfsCachePressure = OptModel.readTrimmed("/proc/sys/vm/vfs_cache_pressure");
        st.swappiness = OptModel.readTrimmed("/proc/sys/vm/swappiness");
        st.dirtyBgRatio = OptModel.readTrimmed("/proc/sys/vm/dirty_background_ratio");
        st.dirtyRatio = OptModel.readTrimmed("/proc/sys/vm/dirty_ratio");
        st.minFreeKbytes = OptModel.readTrimmed("/proc/sys/vm/min_free_kbytes");

        String bdev = findBlockDevName();
        st.blockDev = bdev;
        if (!bdev.isEmpty()) {
            String q = "/sys/block/" + bdev + "/queue";
            st.readAheadKb = OptModel.readTrimmed(q + "/read_ahead_kb");
            st.nrRequests = OptModel.readTrimmed(q + "/nr_requests");
            st.rqAffinity = OptModel.readTrimmed(q + "/rq_affinity");
        }

        float[] anim = readAnimScales(androidContext);
        st.animWin = anim[0];
        st.animTrans = anim[1];
        st.animDur = anim[2];

        snapshotIrqMasks(st.irqMasks);
        return st;
    }

    public static String applyAllConfigured(String installDir, OptModel.OptConfig cfg, Object androidContext) {
        OptModel.BaselineState baseline = ensureBaseline(androidContext).state;
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < OptModel.ALL_TWEAK_KEYS.length; i++) {
            String key = OptModel.ALL_TWEAK_KEYS[i];
            if (cfg.isEnabled(key)) {
                String msg = applyTweakInternal(installDir, key, cfg, baseline, androidContext);
                if (log.length() > 0) {
                    log.append(" | ");
                }
                log.append(key).append(':').append(msg);
            }
        }
        OptModel.saveBaseline(baseline);
        OptModel.syncActiveConf(baseline, cfg);
        return log.toString();
    }

    public static String applySingleTweak(String installDir, String key, OptModel.OptConfig cfg, Object androidContext) {
        OptModel.BaselineState baseline = ensureBaseline(androidContext).state;
        String msg = applyTweakInternal(installDir, key, cfg, baseline, androidContext);
        OptModel.saveBaseline(baseline);
        OptModel.syncActiveConf(baseline, cfg);
        return msg;
    }

    public static String revertSingleTweak(String installDir, String key, OptModel.OptConfig cfg, Object androidContext) {
        OptModel.BaselineState baseline = OptModel.loadBaseline();
        String msg = revertTweakInternal(installDir, key, baseline, androidContext);
        if (baseline != null) {
            baseline.activeTweaks.remove(key);
            OptModel.saveBaseline(baseline);
        }
        OptModel.syncActiveConf(baseline, cfg);
        return msg;
    }

    public static String revertAllToStock(String installDir, OptModel.OptConfig cfg, Object androidContext) {
        OptModel.BaselineState baseline = OptModel.loadBaseline();
        StringBuilder log = new StringBuilder();
        for (int i = OptModel.ALL_TWEAK_KEYS.length - 1; i >= 0; i--) {
            String key = OptModel.ALL_TWEAK_KEYS[i];
            String msg = revertTweakInternal(installDir, key, baseline, androidContext);
            if (log.length() > 0) {
                log.append(" | ");
            }
            log.append(key).append(':').append(msg);
            if (baseline != null) {
                baseline.activeTweaks.remove(key);
            }
        }
        if (baseline != null) {
            OptModel.saveBaseline(baseline);
        }
        OptModel.syncActiveConf(baseline, cfg);
        return log.toString();
    }

    public static String refreshSchedOnly(String installDir, OptModel.OptConfig cfg, Object androidContext) {
        if (cfg != null && !cfg.isEnabled(OptModel.KEY_SCHED)) {
            return "skipped(auto-disabled)";
        }
        OptModel.BaselineState baseline = ensureBaseline(androidContext).state;
        String msg = applySched(false);
        baseline.activeTweaks.add(OptModel.KEY_SCHED);
        OptModel.saveBaseline(baseline);
        OptModel.syncActiveConf(baseline, cfg);
        return msg;
    }

    private static String applyTweakInternal(
            String installDir,
            String key,
            OptModel.OptConfig cfg,
            OptModel.BaselineState baseline,
            Object androidContext) {
        String res;
        if (OptModel.KEY_LOGGERS.equals(key)) {
            res = stopDiskLoggers();
        } else if (OptModel.KEY_KSM.equals(key)) {
            res = applyKsm();
        } else if (OptModel.KEY_VM_MMC.equals(key)) {
            res = applyVmAndMmc(baseline);
        } else if (OptModel.KEY_SCHED.equals(key)) {
            res = applySched(false);
        } else if (OptModel.KEY_IRQ.equals(key)) {
            res = applyIrqDispersion(baseline);
        } else if (OptModel.KEY_ANIM.equals(key)) {
            res = applyAnimPreset(installDir, cfg.animPreset, baseline, androidContext);
        } else {
            return "unknown";
        }
        baseline.activeTweaks.add(key);
        return res;
    }

    private static String revertTweakInternal(
            String installDir,
            String key,
            OptModel.BaselineState baseline,
            Object androidContext) {
        if (OptModel.KEY_LOGGERS.equals(key)) {
            return "loggers_remain_manual_until_reboot";
        } else if (OptModel.KEY_KSM.equals(key)) {
            return revertKsm(baseline);
        } else if (OptModel.KEY_VM_MMC.equals(key)) {
            return revertVmAndMmc(baseline);
        } else if (OptModel.KEY_SCHED.equals(key)) {
            return applySched(true);
        } else if (OptModel.KEY_IRQ.equals(key)) {
            return revertIrqDispersion(baseline);
        } else if (OptModel.KEY_ANIM.equals(key)) {
            return revertAnim(installDir, baseline, androidContext);
        }
        return "unknown";
    }

    // =========================================================================
    // 1. Background Disk Loggers
    // =========================================================================

    private static String stopDiskLoggers() {
        int stoppedSvc = 0;
        for (int i = 0; i < LOGGER_SERVICES.length; i++) {
            String svc = LOGGER_SERVICES[i];
            String state = getSystemProp("init.svc." + svc);
            if ("running".equals(state) || "restarting".equals(state)) {
                setSystemProp("ctl.stop", svc);
                runToolboxQuiet("/system/bin/stop", svc);
                stoppedSvc++;
            }
        }

        int killedPid = 0;
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries != null) {
            for (int i = 0; i < entries.length; i++) {
                File f = entries[i];
                if (!f.isDirectory()) {
                    continue;
                }
                String name = f.getName();
                if (!isAllDigits(name)) {
                    continue;
                }
                int pid = OptModel.parseIntSafe(name, -1);
                if (pid <= 1) {
                    continue;
                }
                String cmd = readProcCmdline(name);
                String comm = OptModel.readTrimmed("/proc/" + name + "/comm");
                if (isExactDiskLoggerProcess(cmd, comm)) {
                    try {
                        Runtime.getRuntime().exec(new String[] { "/system/bin/kill", "-TERM", name }).waitFor();
                        killedPid++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return "svc=" + stoppedSvc + ",pid=" + killedPid;
    }

    public static boolean isExactDiskLoggerProcess(String cmdline, String comm) {
        String cmdLow = cmdline == null ? "" : cmdline.trim().toLowerCase(Locale.US);
        String commLow = comm == null ? "" : comm.trim().toLowerCase(Locale.US);
        // Never touch child logcat pipes or shell bridges
        if ("logcat".equals(commLow) || cmdLow.startsWith("logcat ") || cmdLow.startsWith("/system/bin/logcat")) {
            return false;
        }
        for (int i = 0; i < LOGGER_SERVICES.length; i++) {
            String target = LOGGER_SERVICES[i];
            if (target.equals(commLow) || target.equals(cmdLow)) {
                return true;
            }
            if (cmdLow.startsWith("/system/bin/" + target) || cmdLow.startsWith("/vendor/bin/" + target)) {
                int nextIdx = ("/system/bin/" + target).length();
                if (cmdLow.length() == nextIdx || cmdLow.charAt(nextIdx) == ' ') {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countRunningLoggers() {
        int running = 0;
        for (int i = 0; i < LOGGER_SERVICES.length; i++) {
            String state = getSystemProp("init.svc." + LOGGER_SERVICES[i]);
            if ("running".equals(state)) {
                running++;
            }
        }
        return running;
    }

    // =========================================================================
    // 2. KSM
    // =========================================================================

    private static String applyKsm() {
        boolean r1 = OptModel.writeProcSys("/sys/kernel/mm/ksm/run", "0");
        boolean r2 = OptModel.writeProcSys("/sys/kernel/mm/ksm/sleep_millisecs", "1000");
        return "run=" + (r1 ? "0" : "na") + ",sleep=" + (r2 ? "1000" : "na");
    }

    private static String revertKsm(OptModel.BaselineState baseline) {
        String runVal = (baseline != null && !baseline.ksmRun.isEmpty()) ? baseline.ksmRun : "0";
        String sleepVal = (baseline != null && !baseline.ksmSleepMs.isEmpty()) ? baseline.ksmSleepMs : "80";
        boolean r1 = OptModel.writeProcSys("/sys/kernel/mm/ksm/run", runVal);
        boolean r2 = OptModel.writeProcSys("/sys/kernel/mm/ksm/sleep_millisecs", sleepVal);
        return "run=" + (r1 ? runVal : "na") + ",sleep=" + (r2 ? sleepVal : "na");
    }

    // =========================================================================
    // 3. VM & eMMC Queue
    // =========================================================================

    private static String applyVmAndMmc(OptModel.BaselineState baseline) {
        boolean vfs = OptModel.writeProcSys("/proc/sys/vm/vfs_cache_pressure", "60");
        boolean swp = OptModel.writeProcSys("/proc/sys/vm/swappiness", "10");
        OptModel.writeProcSys("/proc/sys/vm/dirty_background_ratio", "5");
        OptModel.writeProcSys("/proc/sys/vm/dirty_ratio", "15");
        int curMinFree = OptModel.parseIntSafe(OptModel.readTrimmed("/proc/sys/vm/min_free_kbytes"), 0);
        if (curMinFree > 0 && curMinFree < 16384) {
            OptModel.writeProcSys("/proc/sys/vm/min_free_kbytes", "16384");
        }

        String bdev = (baseline != null && !baseline.blockDev.isEmpty()) ? baseline.blockDev : findBlockDevName();
        boolean ra = false;
        String rqApplied = "na";
        if (!bdev.isEmpty()) {
            String q = "/sys/block/" + bdev + "/queue";
            ra = OptModel.writeProcSys(q + "/read_ahead_kb", "512");
            if (!OptModel.writeProcSys(q + "/nr_requests", "256")) {
                OptModel.writeProcSys(q + "/nr_requests", "128");
            }
            if (OptModel.writeProcSys(q + "/rq_affinity", "2")
                    && "2".equals(OptModel.readTrimmed(q + "/rq_affinity"))) {
                rqApplied = "2";
            } else if (OptModel.writeProcSys(q + "/rq_affinity", "1")) {
                rqApplied = "1";
            }
        }
        return "vfs=" + (vfs ? "60" : "na") + ",swap=" + (swp ? "10" : "na")
                + ",ra=" + (ra ? "512" : "na") + ",rq=" + rqApplied;
    }

    private static String revertVmAndMmc(OptModel.BaselineState baseline) {
        String vfsVal = (baseline != null && !baseline.vfsCachePressure.isEmpty()) ? baseline.vfsCachePressure : "100";
        String swpVal = (baseline != null && !baseline.swappiness.isEmpty()) ? baseline.swappiness : "60";
        String dbgVal = (baseline != null && !baseline.dirtyBgRatio.isEmpty()) ? baseline.dirtyBgRatio : "10";
        String drVal = (baseline != null && !baseline.dirtyRatio.isEmpty()) ? baseline.dirtyRatio : "20";
        String mfVal = (baseline != null && !baseline.minFreeKbytes.isEmpty()) ? baseline.minFreeKbytes : "";

        OptModel.writeProcSys("/proc/sys/vm/vfs_cache_pressure", vfsVal);
        OptModel.writeProcSys("/proc/sys/vm/swappiness", swpVal);
        OptModel.writeProcSys("/proc/sys/vm/dirty_background_ratio", dbgVal);
        OptModel.writeProcSys("/proc/sys/vm/dirty_ratio", drVal);
        if (!mfVal.isEmpty()) {
            OptModel.writeProcSys("/proc/sys/vm/min_free_kbytes", mfVal);
        }

        String bdev = (baseline != null && !baseline.blockDev.isEmpty()) ? baseline.blockDev : findBlockDevName();
        String raVal = (baseline != null && !baseline.readAheadKb.isEmpty()) ? baseline.readAheadKb : "128";
        String nrVal = (baseline != null && !baseline.nrRequests.isEmpty()) ? baseline.nrRequests : "128";
        String rqVal = (baseline != null && !baseline.rqAffinity.isEmpty()) ? baseline.rqAffinity : "1";
        if (!bdev.isEmpty()) {
            String q = "/sys/block/" + bdev + "/queue";
            OptModel.writeProcSys(q + "/read_ahead_kb", raVal);
            OptModel.writeProcSys(q + "/nr_requests", nrVal);
            OptModel.writeProcSys(q + "/rq_affinity", rqVal);
        }
        return "vfs=" + vfsVal + ",swap=" + swpVal + ",ra=" + raVal;
    }

    public static String findBlockDevName() {
        String[] candidates = new String[] { "mmcblk0", "mmcblk1", "vda", "sda" };
        for (int i = 0; i < candidates.length; i++) {
            File f = new File("/sys/block/" + candidates[i] + "/queue/read_ahead_kb");
            if (f.exists()) {
                return candidates[i];
            }
        }
        return "";
    }

    // =========================================================================
    // 4. Multi-thread Scheduler & Polite Daemon Priority
    // =========================================================================

    private static String applySched(boolean revertToDefault) {
        int threadsChanged = 0;
        int procsChanged = 0;

        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) {
            return "proc_unreadable";
        }
        for (int i = 0; i < entries.length; i++) {
            File pDir = entries[i];
            if (!pDir.isDirectory()) {
                continue;
            }
            String pidStr = pDir.getName();
            if (!isAllDigits(pidStr)) {
                continue;
            }
            int pid = OptModel.parseIntSafe(pidStr, -1);
            if (pid <= 1) {
                continue;
            }
            String cmd = readProcCmdline(pidStr);
            String comm = OptModel.readTrimmed("/proc/" + pidStr + "/comm");
            ProcRole role = classifyProcess(cmd, comm);
            if (role == ProcRole.NONE || role == ProcRole.PROTECTED_SELF) {
                continue;
            }

            if (revertToDefault) {
                int c = applyPriorityToTasks(pidStr, role, true);
                if (c > 0) {
                    threadsChanged += c;
                    procsChanged++;
                }
                if (role == ProcRole.BG_BLOAT) {
                    writeCgroupTask(pidStr, false);
                    runToolboxQuiet("/system/bin/ionice", pidStr, "be", "4");
                }
                continue;
            }

            int c = applyPriorityToTasks(pidStr, role, false);
            if (c > 0) {
                threadsChanged += c;
                procsChanged++;
            }
            if (role == ProcRole.SURFACEFLINGER || role == ProcRole.NAVI || role == ProcRole.FOREGROUND_UI) {
                writeCgroupTask(pidStr, false);
            } else if (role == ProcRole.BG_BLOAT) {
                writeCgroupTask(pidStr, true);
                if (!runToolboxQuiet("/system/bin/ionice", pidStr, "idle", "7")) {
                    runToolboxQuiet("/system/bin/ionice", pidStr, "be", "7");
                }
            }
        }
        return (revertToDefault ? "reverted" : "boosted") + " procs=" + procsChanged + ",tids=" + threadsChanged;
    }

    private static int applyPriorityToTasks(String pidStr, ProcRole role, boolean resetZero) {
        File taskDir = new File("/proc/" + pidStr + "/task");
        File[] tasks = taskDir.listFiles();
        if (tasks == null || tasks.length == 0) {
            int tid = OptModel.parseIntSafe(pidStr, -1);
            if (tid <= 1) {
                return 0;
            }
            int prio = resetZero ? 0 : targetNiceForRole(role, "");
            if (prio == Integer.MIN_VALUE) {
                return 0;
            }
            return setThreadNice(tid, prio) ? 1 : 0;
        }
        int count = 0;
        for (int i = 0; i < tasks.length; i++) {
            String tidStr = tasks[i].getName();
            if (!isAllDigits(tidStr)) {
                continue;
            }
            int tid = OptModel.parseIntSafe(tidStr, -1);
            if (tid <= 1) {
                continue;
            }
            String tcomm = OptModel.readTrimmed("/proc/" + pidStr + "/task/" + tidStr + "/comm");
            int prio = resetZero ? 0 : targetNiceForRole(role, tcomm);
            if (prio == Integer.MIN_VALUE) {
                continue;
            }
            if (setThreadNice(tid, prio)) {
                count++;
            }
        }
        return count;
    }

    public static int targetNiceForRole(ProcRole role, String threadComm) {
        if (role == ProcRole.SURFACEFLINGER) {
            return -8;
        }
        if (role == ProcRole.SYSTEM_SERVER) {
            return isSystemServerUiThread(threadComm) ? -8 : Integer.MIN_VALUE;
        }
        if (role == ProcRole.NAVI) {
            return shouldSkipGcThread(threadComm) ? Integer.MIN_VALUE : -6;
        }
        if (role == ProcRole.FOREGROUND_UI) {
            return shouldSkipGcThread(threadComm) ? Integer.MIN_VALUE : -4;
        }
        if (role == ProcRole.EXTERNAL_DAEMON) {
            return shouldSkipGcThread(threadComm) ? Integer.MIN_VALUE : 4;
        }
        if (role == ProcRole.DXB_BLOAT) {
            return 6;
        }
        if (role == ProcRole.BG_BLOAT) {
            return 10;
        }
        return Integer.MIN_VALUE;
    }

    public static ProcRole classifyProcess(String cmdline, String comm) {
        String cmd = cmdline == null ? "" : cmdline.trim().toLowerCase(Locale.US);
        String c = comm == null ? "" : comm.trim().toLowerCase(Locale.US);
        String combined = cmd + " " + c;

        if (cmd.isEmpty() && c.isEmpty()) {
            return ProcRole.NONE;
        }
        // Kernel threads (usually bracketed or empty cmdline)
        if (cmd.isEmpty() || cmd.startsWith("[")) {
            return ProcRole.NONE;
        }
        // Own tools and core system boundaries must never be demoted
        if (combined.contains("com.hkg.optimize")
                || combined.contains("com.hkg.sysbench")
                || combined.contains("servicemanager")
                || "zygote".equals(cmd) || "zygote".equals(c)
                || cmd.endsWith("/adbd") || "adbd".equals(c)
                || cmd.startsWith("/sbin/")) {
            return ProcRole.PROTECTED_SELF;
        }

        if ("/system/bin/surfaceflinger".equals(cmd) || "surfaceflinger".equals(c) || "surfaceflinger".equals(cmd)) {
            return ProcRole.SURFACEFLINGER;
        }
        if ("system_server".equals(cmd) || "system_server".equals(c)) {
            return ProcRole.SYSTEM_SERVER;
        }
        if (cmd.contains("com.mnsoft.navi") || c.contains("mnsoft.navi")) {
            return ProcRole.NAVI;
        }
        if (cmd.contains("com.lge.launcher")
                || cmd.contains("com.mobis.launcher")
                || cmd.contains("com.hkmc.launcher")
                || cmd.contains("com.android.launcher")
                || cmd.contains("com.android.systemui")
                || cmd.contains("splitscreen")
                || cmd.contains("projection")
                || cmd.contains("carplay")
                || cmd.contains("androidauto")) {
            return ProcRole.FOREGROUND_UI;
        }
        // External bridge / HUD app_process daemons: polite nice +4 without bg cgroup penalty
        if (combined.contains("carrotnavi")
                || combined.contains("com.carrot")
                || combined.contains("carrotbridge")
                || combined.contains("turzxmonitor")
                || combined.contains("turzx")) {
            return ProcRole.EXTERNAL_DAEMON;
        }
        if (cmd.startsWith("/system/bin/app_process") || cmd.startsWith("app_process")) {
            if (!isFrameworkCliHelper(cmd)) {
                return ProcRole.EXTERNAL_DAEMON;
            }
            return ProcRole.NONE;
        }
        if (cmd.contains("tcc_dxb_service") || c.contains("tcc_dxb")) {
            return ProcRole.DXB_BLOAT;
        }
        if (cmd.contains("com.lge.weather")
                || cmd.contains("com.mobis.weather")
                || cmd.contains("com.hkmc.sports")
                || cmd.contains("com.mobis.vrm")
                || cmd.contains(".weather")
                || cmd.contains(".sports")
                || cmd.contains("voicememo")
                || cmd.endsWith("/vrm")
                || "vrm".equals(cmd)
                || "vrm".equals(c)) {
            return ProcRole.BG_BLOAT;
        }
        return ProcRole.NONE;
    }

    private static boolean isFrameworkCliHelper(String cmdLower) {
        return cmdLower.contains("com.android.commands.")
                || cmdLower.contains("com.android.uiautomator.");
    }

    public static boolean shouldSkipGcThread(String threadComm) {
        if (threadComm == null || threadComm.trim().isEmpty()) {
            return false;
        }
        String low = threadComm.trim().toLowerCase(Locale.US);
        for (int i = 0; i < GC_THREAD_TOKENS.length; i++) {
            if (low.contains(GC_THREAD_TOKENS[i])) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSystemServerUiThread(String threadComm) {
        if (threadComm == null) {
            return false;
        }
        String t = threadComm.trim().toLowerCase(Locale.US);
        if (t.isEmpty() || shouldSkipGcThread(t)) {
            return false;
        }
        return "system_server".equals(t)
                || t.startsWith("android.ui")
                || t.startsWith("android.anim")
                || t.startsWith("android.display")
                || t.contains("activitymanager")
                || t.contains("windowmanager")
                || t.contains("powermanager")
                || t.contains("inputdispatcher")
                || t.contains("inputreader")
                || t.startsWith("binder_")
                || "ui".equals(t);
    }

    private static void writeCgroupTask(String pidStr, boolean bgNonInteractive) {
        String path = bgNonInteractive
                ? "/dev/cpuctl/apps/bg_non_interactive/tasks"
                : "/dev/cpuctl/apps/tasks";
        if (new File(path).exists()) {
            OptModel.writeProcSys(path, pidStr);
        } else if (!bgNonInteractive && new File("/dev/cpuctl/tasks").exists()) {
            OptModel.writeProcSys("/dev/cpuctl/tasks", pidStr);
        }
    }

    // =========================================================================
    // 5. IRQ Dispersion (CPU1/CPU2/CPU3, protecting timer/WDT/UART/IPI)
    // =========================================================================

    private static void snapshotIrqMasks(Map<String, String> out) {
        out.clear();
        File irqRoot = new File("/proc/irq");
        File[] dirs = irqRoot.listFiles();
        if (dirs == null) {
            return;
        }
        Arrays.sort(dirs);
        for (int i = 0; i < dirs.length; i++) {
            File d = dirs[i];
            if (!d.isDirectory() || !isAllDigits(d.getName())) {
                continue;
            }
            String mask = OptModel.readTrimmed(d.getAbsolutePath() + "/smp_affinity");
            if (!mask.isEmpty()) {
                out.put(d.getName(), mask);
            }
        }
    }

    public static Map<String, String> computeIrqAssignments(
            List<String> interruptsLines,
            int numCores,
            Map<String, Boolean> existingIrqCheck) {
        Map<String, String> plan = new LinkedHashMap<String, String>();
        if (numCores < 2 || interruptsLines == null) {
            return plan;
        }
        int targetCores = Math.min(numCores, 8);
        int rr = 0;
        for (int i = 0; i < interruptsLines.size(); i++) {
            String line = interruptsLines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String irqNum = line.substring(0, colon).trim();
            if (!isAllDigits(irqNum)) {
                continue;
            }
            if (existingIrqCheck != null) {
                Boolean exists = existingIrqCheck.get(irqNum);
                if (exists == null || !exists.booleanValue()) {
                    continue;
                }
            }
            String low = line.toLowerCase(Locale.US);
            if (isProtectedIrqLine(low)) {
                continue;
            }
            // Round-robin over CPU1 .. CPU(n-1) -> masks 2, 4, 8, ...
            int cpuIdx = 1 + (rr % (targetCores - 1));
            rr++;
            plan.put(irqNum, Integer.toHexString(1 << cpuIdx));
        }
        return plan;
    }

    private static boolean isProtectedIrqLine(String lineLower) {
        for (int i = 0; i < CRITICAL_IRQ_TOKENS.length; i++) {
            if (lineLower.contains(CRITICAL_IRQ_TOKENS[i])) {
                return true;
            }
        }
        return false;
    }

    private static String applyIrqDispersion(OptModel.BaselineState baseline) {
        int numCores = readOnlineCpuCount();
        if (numCores < 2) {
            return "single_core_skip";
        }
        List<String> lines = OptModel.readLines("/proc/interrupts");
        Map<String, Boolean> writableCheck = new LinkedHashMap<String, Boolean>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String irqNum = line.substring(0, colon).trim();
            if (isAllDigits(irqNum)) {
                File f = new File("/proc/irq/" + irqNum + "/smp_affinity");
                writableCheck.put(irqNum, Boolean.valueOf(f.exists()));
            }
        }

        // Snapshot before first overwrite if empty
        if (baseline != null && baseline.irqMasks.isEmpty()) {
            snapshotIrqMasks(baseline.irqMasks);
        }

        Map<String, String> plan = computeIrqAssignments(lines, numCores, writableCheck);
        int moved = 0;
        for (Map.Entry<String, String> e : plan.entrySet()) {
            String path = "/proc/irq/" + e.getKey() + "/smp_affinity";
            String cur = OptModel.readTrimmed(path);
            if (OptModel.writeProcSys(path, e.getValue())) {
                moved++;
            } else if (OptModel.writeProcSys(path, "e")) {
                moved++;
            }
            if (baseline != null && !cur.isEmpty() && !baseline.irqMasks.containsKey(e.getKey())) {
                baseline.irqMasks.put(e.getKey(), cur);
            }
        }
        return "cores=" + numCores + ",moved=" + moved;
    }

    private static String revertIrqDispersion(OptModel.BaselineState baseline) {
        if (baseline == null || baseline.irqMasks.isEmpty()) {
            return "no_baseline_irq";
        }
        int restored = 0;
        for (Map.Entry<String, String> e : baseline.irqMasks.entrySet()) {
            String path = "/proc/irq/" + e.getKey() + "/smp_affinity";
            if (OptModel.writeProcSys(path, e.getValue())) {
                restored++;
            }
        }
        return "restored=" + restored;
    }

    public static int readOnlineCpuCount() {
        String online = OptModel.readTrimmed("/sys/devices/system/cpu/online");
        if (!online.isEmpty()) {
            if (online.contains("-")) {
                String[] parts = online.split("-");
                if (parts.length == 2) {
                    int a = OptModel.parseIntSafe(parts[0], 0);
                    int b = OptModel.parseIntSafe(parts[1], 0);
                    if (b >= a) {
                        return (b - a) + 1;
                    }
                }
            } else if (online.contains(",")) {
                return online.split(",").length;
            }
        }
        int avail = Runtime.getRuntime().availableProcessors();
        return avail > 0 ? avail : 1;
    }

    private static double computeCpu0IrqPct() {
        List<String> lines = OptModel.readLines("/proc/interrupts");
        if (lines.isEmpty()) {
            return -1.0;
        }
        int headerCols = 0;
        String header = lines.get(0).trim();
        if (!header.isEmpty()) {
            String[] hdrTokens = header.split("\\s+");
            for (int i = 0; i < hdrTokens.length; i++) {
                if (hdrTokens[i].startsWith("CPU")) {
                    headerCols++;
                }
            }
        }
        if (headerCols <= 0) {
            return -1.0;
        }
        long cpu0Sum = 0;
        long totalSum = 0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String irqId = line.substring(0, colon).trim();
            if (!isAllDigits(irqId)) {
                continue;
            }
            String[] tokens = line.substring(colon + 1).trim().split("\\s+");
            if (tokens.length < headerCols) {
                continue;
            }
            for (int c = 0; c < headerCols; c++) {
                long v = parseLongSafe(tokens[c], -1L);
                if (v < 0L) {
                    break;
                }
                if (c == 0) {
                    cpu0Sum += v;
                }
                totalSum += v;
            }
        }
        if (totalSum <= 0L) {
            return -1.0;
        }
        return (cpu0Sum * 100.0) / totalSum;
    }

    // =========================================================================
    // 6. WindowManager / Settings.Global Animation Scale + Boot Guard
    // =========================================================================

    private static String applyAnimPreset(
            String installDir,
            String preset,
            OptModel.BaselineState baseline,
            Object androidContext) {
        float[] scales = OptModel.getAnimScalesForPreset(preset);
        writeAnimScales(androidContext, scales[0], scales[1], scales[2]);
        float baseW = baseline != null ? baseline.animWin : 1.0f;
        float baseT = baseline != null ? baseline.animTrans : 1.0f;
        float baseD = baseline != null ? baseline.animDur : 1.0f;
        String guardContent = OptModel.formatAnimGuardConf(OptModel.readBootId(), baseW, baseT, baseD);
        OptModel.writeFileUtf8(installDir + "/config/anim_guard.conf", guardContent);
        return String.format(Locale.US, "%.2f/%.2f/%.2f",
                Float.valueOf(scales[0]), Float.valueOf(scales[1]), Float.valueOf(scales[2]));
    }

    private static String revertAnim(String installDir, OptModel.BaselineState baseline, Object androidContext) {
        float w = baseline != null ? baseline.animWin : 1.0f;
        float t = baseline != null ? baseline.animTrans : 1.0f;
        float d = baseline != null ? baseline.animDur : 1.0f;
        writeAnimScales(androidContext, w, t, d);
        File guard = new File(installDir + "/config/anim_guard.conf");
        if (guard.exists()) {
            guard.delete();
        }
        return String.format(Locale.US, "%.2f/%.2f/%.2f",
                Float.valueOf(w), Float.valueOf(t), Float.valueOf(d));
    }

    public static float[] readAnimScales(Object androidContext) {
        float[] out = new float[] { 1.0f, 1.0f, 1.0f };
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "window");
            if (binder != null) {
                Class<?> iBinderClass = Class.forName("android.os.IBinder");
                Class<?> stubClass = Class.forName("android.view.IWindowManager$Stub");
                Object iwm = stubClass.getMethod("asInterface", iBinderClass).invoke(null, binder);
                if (iwm != null) {
                    Method getScale = iwm.getClass().getMethod("getAnimationScale", int.class);
                    for (int i = 0; i < 3; i++) {
                        Object val = getScale.invoke(iwm, Integer.valueOf(i));
                        if (val instanceof Number) {
                            float f = ((Number) val).floatValue();
                            if (f >= 0.0f && f <= 10.0f) {
                                out[i] = f;
                            }
                        }
                    }
                    return out;
                }
            }
        } catch (Throwable ignored) {
        }
        Object cr = getContentResolver(androidContext);
        if (cr != null) {
            try {
                Class<?> settingsGlobal = Class.forName("android.provider.Settings$Global");
                Class<?> crClass = Class.forName("android.content.ContentResolver");
                Method getFloat = settingsGlobal.getMethod("getFloat", crClass, String.class, float.class);
                out[0] = ((Float) getFloat.invoke(null, cr, "window_animation_scale", Float.valueOf(1.0f))).floatValue();
                out[1] = ((Float) getFloat.invoke(null, cr, "transition_animation_scale", Float.valueOf(1.0f))).floatValue();
                out[2] = ((Float) getFloat.invoke(null, cr, "animator_duration_scale", Float.valueOf(1.0f))).floatValue();
                return out;
            } catch (Throwable ignored) {
            }
        }
        out[0] = OptModel.parseFloatSafe(runCommandCapture("/system/bin/settings", "get", "global", "window_animation_scale"), 1.0f);
        out[1] = OptModel.parseFloatSafe(runCommandCapture("/system/bin/settings", "get", "global", "transition_animation_scale"), 1.0f);
        out[2] = OptModel.parseFloatSafe(runCommandCapture("/system/bin/settings", "get", "global", "animator_duration_scale"), 1.0f);
        return out;
    }

    public static boolean writeAnimScales(Object androidContext, float win, float trans, float dur) {
        boolean appliedWm = applyWindowManagerScalesDirect(win, trans, dur);
        boolean wroteGlobal = false;
        Object cr = getContentResolver(androidContext);
        String wStr = String.format(Locale.US, "%.2f", Float.valueOf(win));
        String tStr = String.format(Locale.US, "%.2f", Float.valueOf(trans));
        String dStr = String.format(Locale.US, "%.2f", Float.valueOf(dur));
        if (cr != null) {
            try {
                Class<?> settingsGlobal = Class.forName("android.provider.Settings$Global");
                Class<?> crClass = Class.forName("android.content.ContentResolver");
                Method putFloat = settingsGlobal.getMethod("putFloat", crClass, String.class, float.class);
                putFloat.invoke(null, cr, "window_animation_scale", Float.valueOf(win));
                putFloat.invoke(null, cr, "transition_animation_scale", Float.valueOf(trans));
                putFloat.invoke(null, cr, "animator_duration_scale", Float.valueOf(dur));
                wroteGlobal = true;
            } catch (Throwable ignored) {
            }
        }
        if (!wroteGlobal && !appliedWm) {
            boolean a = runToolboxQuiet("/system/bin/settings", "put", "global", "window_animation_scale", wStr);
            boolean b = runToolboxQuiet("/system/bin/settings", "put", "global", "transition_animation_scale", tStr);
            boolean c = runToolboxQuiet("/system/bin/settings", "put", "global", "animator_duration_scale", dStr);
            wroteGlobal = a && b && c;
        }
        return appliedWm || wroteGlobal;
    }

    private static boolean applyWindowManagerScalesDirect(float win, float trans, float dur) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "window");
            if (binder == null) {
                return false;
            }
            Class<?> iBinderClass = Class.forName("android.os.IBinder");
            Class<?> stubClass = Class.forName("android.view.IWindowManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", iBinderClass);
            Object iwm = asInterface.invoke(null, binder);
            if (iwm == null) {
                return false;
            }
            Class<?> iwmClass = Class.forName("android.view.IWindowManager");
            try {
                Method setScale = iwmClass.getMethod("setAnimationScale", int.class, float.class);
                setScale.invoke(iwm, Integer.valueOf(0), Float.valueOf(win));
                setScale.invoke(iwm, Integer.valueOf(1), Float.valueOf(trans));
                setScale.invoke(iwm, Integer.valueOf(2), Float.valueOf(dur));
                return true;
            } catch (Throwable ignored) {
                Method setScales = iwmClass.getMethod("setAnimationScales", float[].class);
                setScales.invoke(iwm, new Object[] { new float[] { win, trans, dur } });
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getContentResolver(Object androidContext) {
        if (androidContext == null) {
            return null;
        }
        try {
            Method m = androidContext.getClass().getMethod("getContentResolver");
            return m.invoke(androidContext);
        } catch (Throwable t) {
            return null;
        }
    }

    // =========================================================================
    // Live Telemetry & Inspection
    // =========================================================================

    public static LiveStatus readLiveStatus(Object androidContext) {
        LiveStatus st = new LiveStatus();
        st.vfsCachePressure = fallbackQ(OptModel.readTrimmed("/proc/sys/vm/vfs_cache_pressure"));
        st.swappiness = fallbackQ(OptModel.readTrimmed("/proc/sys/vm/swappiness"));
        String bdev = findBlockDevName();
        st.blockDev = bdev.isEmpty() ? "mmcblk0" : bdev;
        if (!bdev.isEmpty()) {
            st.readAheadKb = fallbackQ(OptModel.readTrimmed("/sys/block/" + bdev + "/queue/read_ahead_kb"));
            st.rqAffinity = fallbackQ(OptModel.readTrimmed("/sys/block/" + bdev + "/queue/rq_affinity"));
        }
        st.ksmRun = fallbackQ(OptModel.readTrimmed("/sys/kernel/mm/ksm/run"));
        st.ksmSleepMs = fallbackQ(OptModel.readTrimmed("/sys/kernel/mm/ksm/sleep_millisecs"));
        st.runningLoggers = countRunningLoggers();
        st.cpu0IrqPct = computeCpu0IrqPct();

        OptModel.BaselineState baseline = OptModel.loadBaseline();
        if (baseline != null) {
            st.activeTweaks.addAll(baseline.activeTweaks);
            for (Map.Entry<String, String> e : baseline.irqMasks.entrySet()) {
                String now = OptModel.readTrimmed("/proc/irq/" + e.getKey() + "/smp_affinity");
                if (!now.isEmpty() && !now.equalsIgnoreCase(e.getValue())) {
                    st.dispersedIrqs++;
                }
            }
        }

        float[] anim = readAnimScales(androidContext);
        st.animWin = anim[0];
        st.animTrans = anim[1];
        st.animDur = anim[2];

        inspectSchedQuick(st);
        return st;
    }

    private static void inspectSchedQuick(LiveStatus st) {
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.length; i++) {
            File f = entries[i];
            if (!f.isDirectory() || !isAllDigits(f.getName())) {
                continue;
            }
            String cmd = readProcCmdline(f.getName());
            String comm = OptModel.readTrimmed("/proc/" + f.getName() + "/comm");
            ProcRole role = classifyProcess(cmd, comm);
            if (role == ProcRole.SURFACEFLINGER || role == ProcRole.NAVI || role == ProcRole.FOREGROUND_UI) {
                File[] tasks = new File("/proc/" + f.getName() + "/task").listFiles();
                if (tasks != null) {
                    st.schedBoostedThreads += tasks.length;
                }
            } else if (role == ProcRole.EXTERNAL_DAEMON) {
                st.politeDaemons++;
            }
        }
    }

    // =========================================================================
    // Reflection & Low-Level Process Helpers
    // =========================================================================

    private static boolean setThreadNice(int tid, int prio) {
        if (!mSetThreadPriorityChecked) {
            mSetThreadPriorityChecked = true;
            try {
                Class<?> procClass = Class.forName("android.os.Process");
                mSetThreadPriority = procClass.getMethod("setThreadPriority", int.class, int.class);
            } catch (Throwable ignored) {
            }
        }
        if (mSetThreadPriority != null) {
            try {
                mSetThreadPriority.invoke(null, Integer.valueOf(tid), Integer.valueOf(prio));
                return true;
            } catch (Throwable ignored) {
            }
        }
        return runToolboxQuiet("/system/bin/renice", String.valueOf(prio), String.valueOf(tid));
    }

    public static String getSystemProp(String key) {
        initSysProps();
        if (mSysPropGet != null) {
            try {
                Object val = mSysPropGet.invoke(null, key, "");
                return val == null ? "" : val.toString().trim();
            } catch (Throwable ignored) {
            }
        }
        return runCommandCapture("/system/bin/getprop", key);
    }

    public static void setSystemProp(String key, String val) {
        initSysProps();
        if (mSysPropSet != null) {
            try {
                mSysPropSet.invoke(null, key, val);
                return;
            } catch (Throwable ignored) {
            }
        }
        runToolboxQuiet("/system/bin/setprop", key, val);
    }

    private static void initSysProps() {
        if (mSysPropChecked) {
            return;
        }
        mSysPropChecked = true;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            mSysPropGet = sp.getMethod("get", String.class, String.class);
            mSysPropSet = sp.getMethod("set", String.class, String.class);
        } catch (Throwable ignored) {
        }
    }

    private static String readProcCmdline(String pidStr) {
        String raw = OptModel.readFileUtf8("/proc/" + pidStr + "/cmdline");
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replace('\0', ' ').trim();
    }

    private static boolean runToolboxQuiet(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().close();
            p.getOutputStream().close();
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String runCommandCapture(String... cmd) {
        Process p = null;
        BufferedReader br = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static long parseLongSafe(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String fallbackQ(String s) {
        return (s == null || s.isEmpty()) ? "?" : s;
    }
}
