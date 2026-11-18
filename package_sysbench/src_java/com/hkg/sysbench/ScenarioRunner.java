package com.hkg.sysbench;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes controlled Gen5W responsiveness benchmark phases:
 * - Phase 1: Zero-fork Idle contention & eMMC I/O baseline
 * - Phase 2: Binder IPC micro-latency & real Activity switch WaitTime
 * - Phase 3: Touch scroll SurfaceFlinger --latency Jank% & active scheduler Runqueue Delay
 */
public final class ScenarioRunner {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public interface ProgressListener {
        void onProgress(String phaseText);
    }

    public static final class SfLatencyStats {
        public double refreshMs = 16.67;
        public int frameCount;
        public int jankCount;
        public int severeJankCount;
        public double jankPct;
        public double p50FrameMs;
        public double p95FrameMs;
        public double p99FrameMs;
        public double maxFrameMs;
        public double renderReadyP95Ms;
    }

    private ScenarioRunner() {
    }

    public static BenchReport.BenchResult run(
            String slot,
            boolean quickMode,
            int idleSeconds,
            int launchRounds,
            int swipeCount,
            Object sysContext,
            File resultsDir,
            ProgressListener listener,
            boolean[] cancelFlag) {

        BenchReport.BenchResult res = new BenchReport.BenchResult();
        long nowMs = System.currentTimeMillis();
        res.slot = slot;
        res.quickMode = quickMode;
        res.timestampMs = nowMs;
        res.dateStr = BenchReport.formatTimestamp(nowMs);
        res.platform = KernelCollectors.readBoardPlatform();
        res.product2 = KernelCollectors.getSystemProperty("ro.build.product2", "");
        res.uptimeSec = KernelCollectors.readUptimeSeconds();
        res.smpAffinityWritable = readSmpAffinityProbeStatus(resultsDir);
        String extOpt = TweakManager.readExternalOptActiveCsv();
        res.activeTweaks = !extOpt.isEmpty()
                ? ("hkg.optimize:" + extOpt)
                : TweakManager.getActiveSummaryCsv(TweakManager.loadSession(resultsDir));

        if (quickMode) {
            idleSeconds = Math.min(idleSeconds, 5);
            launchRounds = Math.min(launchRounds, 1);
            swipeCount = Math.min(swipeCount, 4);
        }

        if (isRearCamOpen()) {
            res.abortedReason = "rear_camera_opened";
            return res;
        }

        readAnimationScales(res);

        // -----------------------------------------------------------------
        // Phase 1: Idle Contention & I/O Baseline (zero forks, zero disk writes)
        // -----------------------------------------------------------------
        notifyListener(listener, "Phase 1/3: 아이들 I/O·스케줄러 측정 (" + idleSeconds + "초)...");
        runShellCmd(new String[]{"am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"}, 5000L);
        sleepInterruptible(1500L, cancelFlag);
        if (checkAbort(res, cancelFlag)) return res;

        KernelCollectors.MemAndTunableSnapshot memStart = KernelCollectors.MemAndTunableSnapshot.capture();
        res.thermalStartC = memStart.thermalMaxC;

        long t0Ns = System.nanoTime();
        KernelCollectors.CpuSnapshot cpu0 = KernelCollectors.CpuSnapshot.capture();
        KernelCollectors.IrqSnapshot irq0 = KernelCollectors.IrqSnapshot.capture();
        KernelCollectors.DiskSnapshot disk0 = KernelCollectors.DiskSnapshot.capture();
        KernelCollectors.ProcSchedSnapshot proc0 = KernelCollectors.ProcSchedSnapshot.capture();

        long remainingMs = idleSeconds * 1000L;
        while (remainingMs > 0L) {
            long chunk = Math.min(2000L, remainingMs);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException ignored) {
                break;
            }
            remainingMs -= chunk;
            if (checkAbort(res, cancelFlag)) return res;
        }

        long t1Ns = System.nanoTime();
        KernelCollectors.CpuSnapshot cpu1 = KernelCollectors.CpuSnapshot.capture();
        KernelCollectors.IrqSnapshot irq1 = KernelCollectors.IrqSnapshot.capture();
        KernelCollectors.DiskSnapshot disk1 = KernelCollectors.DiskSnapshot.capture();
        KernelCollectors.ProcSchedSnapshot proc1 = KernelCollectors.ProcSchedSnapshot.capture();
        KernelCollectors.MemAndTunableSnapshot memMid = KernelCollectors.MemAndTunableSnapshot.capture();

        double idleSec = Math.max(0.1, (t1Ns - t0Ns) / 1000000000.0);
        KernelCollectors.CpuDelta idleCpu = KernelCollectors.CpuDelta.compute(cpu0, cpu1, idleSec);
        KernelCollectors.IrqDelta idleIrq = KernelCollectors.IrqDelta.compute(irq0, irq1);
        KernelCollectors.DiskDelta idleDisk = KernelCollectors.DiskDelta.compute(disk0, disk1, idleSec);
        KernelCollectors.ProcSchedDelta idleProc = KernelCollectors.ProcSchedDelta.compute(
                proc0, proc1, idleSec, Math.max(1, cpu1.cores.size()));

        res.idleDurationSec = idleSec;
        res.idleCpuBusyPct = idleCpu.totalBusyPct;
        res.idleCpu0BusyPct = (idleCpu.coreBusyPct.length > 0) ? idleCpu.coreBusyPct[0] : idleCpu.totalBusyPct;
        res.idleIowaitPct = idleCpu.iowaitPct;
        res.idleIowaitMsPerMin = idleCpu.iowaitMsPerMin;
        res.idleIrqPct = idleCpu.irqPct;
        res.idleCpu0PeriphIrqPct = idleIrq.cpu0PeripheralSharePct;
        res.idlePeriphIrqDelta = idleIrq.peripheralTotalDelta;
        res.idleEmmcIrqDelta = idleIrq.emmcDelta;
        res.idleDiskWriteKbPerMin = idleDisk.writeKbPerMin;
        res.idleDiskWriteOpsPerMin = idleDisk.writeOpsPerMin;
        res.idleDiskIoBusyMsPerMin = idleDisk.ioBusyMsPerMin;
        res.idleUiRunDelayMsPerSec = idleProc.uiRunDelayMsPerSec;
        res.idleUiRunDelayMs = idleProc.uiRunDelayMs;
        res.idleKsmdCpuMsPerMin = idleProc.ksmdCpuMsPerMin;
        res.idleLoggerCpuMsPerMin = idleProc.loggerCpuMsPerMin;
        res.idleTopCpuProcs = idleProc.topCpuProcs;
        res.pgmajfaultDelta = Math.max(0L, memMid.pgmajfault - memStart.pgmajfault);

        // -----------------------------------------------------------------
        // Phase 2: Binder IPC & App Launch / Switch Latency
        // -----------------------------------------------------------------
        notifyListener(listener, "Phase 2/3: Binder IPC & 화면 진입 지연 측정 중...");
        measureBinderIpcLatency(res);
        if (checkAbort(res, cancelFlag)) return res;

        List<String> targets = resolveLaunchTargets(sysContext);
        List<Double> waitTimes = new ArrayList<Double>();
        Map<String, List<Double>> perPkg = new LinkedHashMap<String, List<Double>>();
        if (targets.size() >= 2) {
            // Pre-position on the last target so the first measured launch transitions tasks cleanly
            runShellCmd(new String[]{"am", "start", "-W", "-n", targets.get(targets.size() - 1)}, 10000L);
            sleepInterruptible(500L, cancelFlag);
        }
        for (int r = 0; r < launchRounds; r++) {
            for (int t = 0; t < targets.size(); t++) {
                if (checkAbort(res, cancelFlag)) return res;
                String comp = targets.get(t);
                if (targets.size() == 1) {
                    runShellCmd(new String[]{"am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"}, 8000L);
                    sleepInterruptible(1200L, cancelFlag);
                    if (checkAbort(res, cancelFlag)) return res;
                }

                String amOut = runShellCmd(new String[]{"am", "start", "-W", "-n", comp}, 10000L);
                double waitMs = parseAmStartWaitTimeMs(amOut);
                if (waitMs > 0.0) {
                    waitTimes.add(Double.valueOf(waitMs));
                    String shortName = shortenComponent(comp);
                    List<Double> lst = perPkg.get(shortName);
                    if (lst == null) {
                        lst = new ArrayList<Double>();
                        perPkg.put(shortName, lst);
                    }
                    lst.add(Double.valueOf(waitMs));
                }
                sleepInterruptible(450L, cancelFlag);
            }
        }
        if (!waitTimes.isEmpty()) {
            double sum = 0.0;
            double maxW = 0.0;
            for (int i = 0; i < waitTimes.size(); i++) {
                double w = waitTimes.get(i).doubleValue();
                sum += w;
                if (w > maxW) maxW = w;
            }
            res.launchSamplesCount = waitTimes.size();
            res.launchAvgWaitMs = sum / waitTimes.size();
            res.launchMaxWaitMs = maxW;
        }
        StringBuilder detSb = new StringBuilder();
        for (Map.Entry<String, List<Double>> ent : perPkg.entrySet()) {
            List<Double> vals = ent.getValue();
            double s = 0.0;
            for (int i = 0; i < vals.size(); i++) s += vals.get(i).doubleValue();
            if (detSb.length() > 0) detSb.append(", ");
            detSb.append(ent.getKey()).append("=").append(Math.round(s / vals.size())).append("ms");
        }
        res.launchDetail = detSb.toString();

        // -----------------------------------------------------------------
        // Phase 3: Touch Scroll Frame Latency & Active Load Contention
        // -----------------------------------------------------------------
        notifyListener(listener, "Phase 3/3: 스크롤 프레임 Jank & 활성 부하 계측 중...");
        String scrollTarget = targets.isEmpty() ? "com.android.settings/.Settings" : targets.get(0);
        runShellCmd(new String[]{"am", "start", "-W", "-n", scrollTarget}, 8000L);
        sleepInterruptible(900L, cancelFlag);
        if (checkAbort(res, cancelFlag)) return res;

        String winDump = runShellCmd(new String[]{"dumpsys", "window", "windows"}, 5000L);
        String focusWin = parseFocusedWindowName(winDump);
        res.focusedWindow = focusWin;

        boolean horizontalSwipe = focusWin.contains("com.android.settings.Settings");
        boolean useGlobalLayer = focusWin.isEmpty();

        long t2Ns = System.nanoTime();
        KernelCollectors.CpuSnapshot aCpu0 = KernelCollectors.CpuSnapshot.capture();
        KernelCollectors.IrqSnapshot aIrq0 = KernelCollectors.IrqSnapshot.capture();
        KernelCollectors.DiskSnapshot aDisk0 = KernelCollectors.DiskSnapshot.capture();
        KernelCollectors.ProcSchedSnapshot aProc0 = KernelCollectors.ProcSchedSnapshot.capture();

        List<Double> allDeltas = new ArrayList<Double>();
        List<Double> allRender = new ArrayList<Double>();
        double[] refreshHolder = new double[]{16.6667};

        for (int s = 0; s < swipeCount; s++) {
            if (checkAbort(res, cancelFlag)) return res;
            String[] clearCmd = !useGlobalLayer
                    ? new String[]{"dumpsys", "SurfaceFlinger", "--latency-clear", focusWin}
                    : new String[]{"dumpsys", "SurfaceFlinger", "--latency-clear"};
            runShellCmd(clearCmd, 4000L);

            String[] swipeCmd;
            if (horizontalSwipe) {
                swipeCmd = ((s % 2) == 0)
                        ? new String[]{"input", "swipe", "1300", "400", "500", "400", "280"}
                        : new String[]{"input", "swipe", "500", "400", "1300", "400", "280"};
            } else {
                swipeCmd = ((s % 2) == 0)
                        ? new String[]{"input", "swipe", "1100", "540", "1100", "200", "280"}
                        : new String[]{"input", "swipe", "1100", "200", "1100", "540", "280"};
            }
            runShellCmd(swipeCmd, 4000L);
            sleepInterruptible(420L, cancelFlag);
            if (checkAbort(res, cancelFlag)) return res;

            String[] latCmd = !useGlobalLayer
                    ? new String[]{"dumpsys", "SurfaceFlinger", "--latency", focusWin}
                    : new String[]{"dumpsys", "SurfaceFlinger", "--latency"};
            String sfRaw = runShellCmd(latCmd, 5000L);
            int beforeCount = allDeltas.size();
            collectRawLatencySamples(sfRaw, allDeltas, allRender, refreshHolder);
            if (s == 0 && !useGlobalLayer && (allDeltas.size() - beforeCount) < 2) {
                String sfGlobal = runShellCmd(new String[]{"dumpsys", "SurfaceFlinger", "--latency"}, 5000L);
                List<Double> tmpDeltas = new ArrayList<Double>();
                List<Double> tmpRender = new ArrayList<Double>();
                double[] tmpRef = new double[]{refreshHolder[0]};
                collectRawLatencySamples(sfGlobal, tmpDeltas, tmpRender, tmpRef);
                if (!tmpDeltas.isEmpty()) {
                    allDeltas.addAll(tmpDeltas);
                    allRender.addAll(tmpRender);
                    refreshHolder[0] = tmpRef[0];
                    useGlobalLayer = true;
                }
            }
        }

        long t3Ns = System.nanoTime();
        KernelCollectors.CpuSnapshot aCpu1 = KernelCollectors.CpuSnapshot.capture();
        KernelCollectors.IrqSnapshot aIrq1 = KernelCollectors.IrqSnapshot.capture();
        KernelCollectors.DiskSnapshot aDisk1 = KernelCollectors.DiskSnapshot.capture();
        KernelCollectors.ProcSchedSnapshot aProc1 = KernelCollectors.ProcSchedSnapshot.capture();

        SfLatencyStats sfStats = buildSfLatencyStats(allDeltas, allRender, refreshHolder[0]);

        double activeSec = Math.max(0.1, (t3Ns - t2Ns) / 1000000000.0);
        KernelCollectors.CpuDelta actCpu = KernelCollectors.CpuDelta.compute(aCpu0, aCpu1, activeSec);
        KernelCollectors.IrqDelta actIrq = KernelCollectors.IrqDelta.compute(aIrq0, aIrq1);
        KernelCollectors.DiskDelta actDisk = KernelCollectors.DiskDelta.compute(aDisk0, aDisk1, activeSec);
        KernelCollectors.ProcSchedDelta actProc = KernelCollectors.ProcSchedDelta.compute(
                aProc0, aProc1, activeSec, Math.max(1, aCpu1.cores.size()));

        res.sfFrameCount = sfStats.frameCount;
        res.sfJankCount = sfStats.jankCount;
        res.sfSevereJankCount = sfStats.severeJankCount;
        res.sfJankPct = sfStats.jankPct;
        res.sfRefreshMs = sfStats.refreshMs;
        res.sfP50FrameMs = sfStats.p50FrameMs;
        res.sfP95FrameMs = sfStats.p95FrameMs;
        res.sfP99FrameMs = sfStats.p99FrameMs;
        res.sfMaxFrameMs = sfStats.maxFrameMs;
        res.sfRenderReadyP95Ms = sfStats.renderReadyP95Ms;

        res.activeDurationSec = activeSec;
        res.activeUiRunDelayMsPerSec = actProc.uiRunDelayMsPerSec;
        res.activeUiRunDelayMs = actProc.uiRunDelayMs;
        res.activeSfDelayMs = actProc.surfaceFlingerDelayMs;
        res.activeSystemServerDelayMs = actProc.systemServerDelayMs;
        res.activeCpu0PeriphIrqPct = actIrq.cpu0PeripheralSharePct;
        res.activeTouchIrqDelta = actIrq.touchDelta;
        res.activeDisplayIrqDelta = actIrq.displayDelta;
        res.activeGpuIrqDelta = actIrq.gpuDelta;
        res.activeEmmcIrqDelta = actIrq.emmcDelta;
        res.activeIowaitMsPerMin = actCpu.iowaitMsPerMin;
        res.activeDiskIoBusyMsPerMin = actDisk.ioBusyMsPerMin;
        res.activeTopCpuProcs = actProc.topCpuProcs;

        // Final system & tunable snapshot
        KernelCollectors.MemAndTunableSnapshot memEnd = KernelCollectors.MemAndTunableSnapshot.capture();
        res.memTotalKb = memEnd.memTotalKb;
        res.memAvailKb = memEnd.availApproxKb;
        res.processCount = actProc.processCountEnd > 0 ? actProc.processCountEnd : idleProc.processCountEnd;
        res.ksmRun = memEnd.ksmRun;
        res.ksmSleepMs = memEnd.ksmSleepMs;
        res.readAheadKb = memEnd.readAheadKb;
        res.swappiness = memEnd.swappiness;
        res.dirtyRatio = memEnd.dirtyRatio;
        res.dirtyBgRatio = memEnd.dirtyBgRatio;
        res.thermalEndC = memEnd.thermalMaxC;
        res.cpu0Governor = memEnd.cpu0Governor;
        res.cpu0CurKhz = memEnd.cpu0CurKhz;
        res.eventlogRunning = memEnd.eventlogRunning;
        res.tccLogdRunning = memEnd.tccLogdRunning;
        res.kloggerRunning = memEnd.kloggerRunning;

        // Return Home cleanly after benchmark
        runShellCmd(new String[]{"am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"}, 5000L);
        return res;
    }

    private static boolean isRearCamOpen() {
        return "1".equals(KernelCollectors.getSystemProperty("persist.sys.rearcam.open", "0"));
    }

    private static boolean checkAbort(BenchReport.BenchResult res, boolean[] cancelFlag) {
        if (cancelFlag != null && cancelFlag.length > 0 && cancelFlag[0]) {
            res.abortedReason = "cancelled";
            return true;
        }
        if (isRearCamOpen()) {
            res.abortedReason = "rear_camera_opened";
            return true;
        }
        return false;
    }

    public static void sleepInterruptible(long ms, boolean[] cancelFlag) {
        long rem = ms;
        while (rem > 0L) {
            if (cancelFlag != null && cancelFlag.length > 0 && cancelFlag[0]) {
                return;
            }
            long step = Math.min(200L, rem);
            try {
                Thread.sleep(step);
            } catch (InterruptedException ignored) {
                return;
            }
            rem -= step;
        }
    }

    private static void notifyListener(ProgressListener l, String msg) {
        if (l != null) {
            l.onProgress(msg);
        }
    }

    private static String readSmpAffinityProbeStatus(File resultsDir) {
        if (resultsDir == null) {
            return "unknown";
        }
        String raw = KernelCollectors.readFileUtf8(new File(resultsDir, "boot_kernel_probe.txt").getAbsolutePath());
        if (raw.contains("SMP_AFFINITY_WRITABLE=yes")) return "yes";
        if (raw.contains("SMP_AFFINITY_WRITABLE=no")) return "no";
        return "unknown";
    }

    private static void readAnimationScales(BenchReport.BenchResult res) {
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Object winBinder = smCls.getMethod("getService", String.class).invoke(null, "window");
            if (winBinder != null) {
                Class<?> stubCls = Class.forName("android.view.IWindowManager$Stub");
                Object wm = stubCls.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, winBinder);
                Method getScale = wm.getClass().getMethod("getAnimationScale", int.class);
                res.animWindowScale = ((Float) getScale.invoke(wm, Integer.valueOf(0))).doubleValue();
                res.animTransitionScale = ((Float) getScale.invoke(wm, Integer.valueOf(1))).doubleValue();
                res.animDurationScale = ((Float) getScale.invoke(wm, Integer.valueOf(2))).doubleValue();
                return;
            }
        } catch (Throwable ignored) {
        }
        res.animWindowScale = KernelCollectors.parseDoubleSafe(runShellCmd(new String[]{"settings", "get", "global", "window_animation_scale"}, 2000L), 1.0);
        res.animTransitionScale = KernelCollectors.parseDoubleSafe(runShellCmd(new String[]{"settings", "get", "global", "transition_animation_scale"}, 2000L), 1.0);
        res.animDurationScale = KernelCollectors.parseDoubleSafe(runShellCmd(new String[]{"settings", "get", "global", "animator_duration_scale"}, 2000L), 1.0);
    }

    private static void measureBinderIpcLatency(BenchReport.BenchResult res) {
        final int nSamples = 500;
        double[] us = new double[nSamples];
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method checkSvc = smCls.getMethod("checkService", String.class);
            Object winBinder = checkSvc.invoke(null, "window");
            Object wm = null;
            Method getScale = null;
            if (winBinder != null) {
                try {
                    Class<?> stubCls = Class.forName("android.view.IWindowManager$Stub");
                    wm = stubCls.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, winBinder);
                    getScale = wm.getClass().getMethod("getAnimationScale", int.class);
                } catch (Throwable ignored) {
                    wm = null;
                }
            }
            for (int w = 0; w < 20; w++) {
                if (wm != null && getScale != null) {
                    getScale.invoke(wm, Integer.valueOf(0));
                } else {
                    checkSvc.invoke(null, "window");
                }
            }
            double sum = 0.0;
            for (int i = 0; i < nSamples; i++) {
                long s = System.nanoTime();
                if (wm != null && getScale != null) {
                    getScale.invoke(wm, Integer.valueOf(0));
                } else {
                    checkSvc.invoke(null, "window");
                }
                long e = System.nanoTime();
                double dUs = (e - s) / 1000.0;
                us[i] = dUs;
                sum += dUs;
            }
            Arrays.sort(us);
            res.binderAvgUs = sum / nSamples;
            res.binderP99Us = percentile(us, 0.99);
            res.binderMaxUs = us[nSamples - 1];
        } catch (Throwable ignored) {
        }
    }

    private static List<String> resolveLaunchTargets(Object sysContext) {
        String[][] knownTargets = new String[][]{
                {"com.android.generalsettings", "com.android.generalsettings/.GeneralSettings"},
                {"com.android.soundsettings", "com.android.soundsettings/.SoundSetting"},
                {"com.android.settings", "com.android.settings/.Settings"}
        };
        List<String> resolved = new ArrayList<String>();
        for (int i = 0; i < knownTargets.length; i++) {
            String pkg = knownTargets[i][0];
            String defComp = knownTargets[i][1];
            if (new File("/data/data/" + pkg).isDirectory()) {
                resolved.add(defComp);
            } else if (sysContext != null) {
                try {
                    Object pm = sysContext.getClass().getMethod("getPackageManager").invoke(sysContext);
                    Method getLaunch = pm.getClass().getMethod("getLaunchIntentForPackage", String.class);
                    Object intent = getLaunch.invoke(pm, pkg);
                    if (intent != null) {
                        Object comp = intent.getClass().getMethod("getComponent").invoke(intent);
                        if (comp != null) {
                            String shortStr = (String) comp.getClass().getMethod("flattenToShortString").invoke(comp);
                            if (shortStr != null && !shortStr.isEmpty()) {
                                resolved.add(shortStr);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (resolved.isEmpty()) {
            resolved.add("com.android.settings/.Settings");
        }
        return resolved;
    }

    private static String shortenComponent(String comp) {
        if (comp == null) return "";
        int slash = comp.indexOf('/');
        String pkg = (slash > 0) ? comp.substring(0, slash) : comp;
        int dot = pkg.lastIndexOf('.');
        return (dot >= 0 && dot + 1 < pkg.length()) ? pkg.substring(dot + 1) : pkg;
    }

    public static double parseAmStartWaitTimeMs(String amOutput) {
        if (amOutput == null || amOutput.isEmpty()) {
            return -1.0;
        }
        if (amOutput.contains("Status: timeout") || amOutput.contains("Error:")) {
            return -1.0;
        }
        String[] lines = amOutput.split("\n");
        double waitVal = -1.0;
        double totalFallback = -1.0;
        double thisFallback = -1.0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("Activity:")) {
                if (line.toLowerCase(Locale.US).contains("launcher")) {
                    return -1.0;
                }
            } else if (line.startsWith("WaitTime:")) {
                waitVal = KernelCollectors.parseDoubleSafe(line.substring("WaitTime:".length()), -1.0);
            } else if (line.startsWith("TotalTime:")) {
                totalFallback = KernelCollectors.parseDoubleSafe(line.substring("TotalTime:".length()), -1.0);
            } else if (line.startsWith("ThisTime:")) {
                thisFallback = KernelCollectors.parseDoubleSafe(line.substring("ThisTime:".length()), -1.0);
            }
        }
        if (waitVal > 0.0) {
            return waitVal;
        }
        return totalFallback > 0.0 ? totalFallback : thisFallback;
    }

    public static String parseFocusedWindowName(String dumpsysWindows) {
        if (dumpsysWindows == null || dumpsysWindows.isEmpty()) {
            return "";
        }
        String[] lines = dumpsysWindows.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("mCurrentFocus=")) {
                int open = line.indexOf('{');
                int close = line.lastIndexOf('}');
                if (open >= 0 && close > open) {
                    String inner = line.substring(open + 1, close).trim();
                    String[] parts = inner.split("\\s+");
                    if (parts.length > 0) {
                        String cand = parts[parts.length - 1];
                        if (!"null".equalsIgnoreCase(cand)) {
                            return cand;
                        }
                    }
                }
            }
        }
        return "";
    }

    public static SfLatencyStats parseSurfaceFlingerLatency(String raw) {
        List<Double> deltas = new ArrayList<Double>();
        List<Double> renderList = new ArrayList<Double>();
        double[] refreshHolder = new double[]{16.6667};
        collectRawLatencySamples(raw, deltas, renderList, refreshHolder);
        return buildSfLatencyStats(deltas, renderList, refreshHolder[0]);
    }

    private static void collectRawLatencySamples(
            String raw,
            List<Double> outDeltas,
            List<Double> outRenderReady,
            double[] ioRefreshMs) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String[] lines = raw.split("\n");
        long refreshNs = 16666666L;
        boolean gotRefresh = false;
        List<Long> actualList = new ArrayList<Long>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split("\\s+");
            if (!gotRefresh && cols.length == 1) {
                long r = KernelCollectors.parseLongSafe(cols[0], 0L);
                if (r > 1000000L && r < 100000000L) {
                    refreshNs = r;
                    gotRefresh = true;
                }
                continue;
            }
            if (cols.length >= 3) {
                long desired = KernelCollectors.parseLongSafe(cols[0], 0L);
                long actual = KernelCollectors.parseLongSafe(cols[1], 0L);
                long ready = KernelCollectors.parseLongSafe(cols[2], 0L);
                if (desired <= 0L || actual <= 0L || actual == Long.MAX_VALUE) {
                    continue;
                }
                actualList.add(Long.valueOf(actual));
                if (ready > desired && ready != Long.MAX_VALUE) {
                    double rMs = (ready - desired) / 1000000.0;
                    if (rMs >= 0.0 && rMs < 350.0) {
                        outRenderReady.add(Double.valueOf(rMs));
                    }
                }
            }
        }

        if (gotRefresh && ioRefreshMs != null && ioRefreshMs.length > 0) {
            ioRefreshMs[0] = refreshNs / 1000000.0;
        }
        for (int i = 1; i < actualList.size(); i++) {
            long prev = actualList.get(i - 1).longValue();
            long cur = actualList.get(i).longValue();
            if (cur <= prev) {
                continue;
            }
            double deltaMs = (cur - prev) / 1000000.0;
            // Safety guard against idle gaps (>350ms) inside raw buffer
            if (deltaMs > 350.0) {
                continue;
            }
            outDeltas.add(Double.valueOf(deltaMs));
        }
    }

    private static SfLatencyStats buildSfLatencyStats(
            List<Double> deltas,
            List<Double> renderList,
            double refreshMs) {
        SfLatencyStats stats = new SfLatencyStats();
        stats.refreshMs = refreshMs > 0.0 ? refreshMs : 16.6667;
        if (deltas != null && !deltas.isEmpty()) {
            double jankThreshMs = Math.max(22.0, stats.refreshMs * 1.48);
            double severeThreshMs = Math.max(38.0, stats.refreshMs * 2.48);
            double[] arr = new double[deltas.size()];
            double maxD = 0.0;
            for (int i = 0; i < deltas.size(); i++) {
                double d = deltas.get(i).doubleValue();
                arr[i] = d;
                if (d > maxD) maxD = d;
                if (d > jankThreshMs) stats.jankCount++;
                if (d > severeThreshMs) stats.severeJankCount++;
            }
            Arrays.sort(arr);
            stats.frameCount = arr.length;
            stats.jankPct = (100.0 * stats.jankCount) / stats.frameCount;
            stats.p50FrameMs = percentile(arr, 0.50);
            stats.p95FrameMs = percentile(arr, 0.95);
            stats.p99FrameMs = percentile(arr, 0.99);
            stats.maxFrameMs = maxD;
        }
        if (renderList != null && !renderList.isEmpty()) {
            double[] rArr = new double[renderList.size()];
            for (int i = 0; i < renderList.size(); i++) {
                rArr[i] = renderList.get(i).doubleValue();
            }
            Arrays.sort(rArr);
            stats.renderReadyP95Ms = percentile(rArr, 0.95);
        }
        return stats;
    }

    public static double percentile(double[] sorted, double p) {
        if (sorted == null || sorted.length == 0) {
            return 0.0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    public static String runShellCmd(String[] cmd, long timeoutMs) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            final InputStream is = proc.getInputStream();
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[1024];
                    try {
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "CmdOutReader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue();
                    break;
                } catch (IllegalThreadStateException notDone) {
                    try {
                        Thread.sleep(40L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            try {
                proc.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                proc.destroy();
            }
            try {
                reader.join(300L);
            } catch (InterruptedException ignored) {
            }
            return new String(bos.toByteArray(), UTF8);
        } catch (Throwable t) {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            return "";
        }
    }
}
