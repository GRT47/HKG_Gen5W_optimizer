package com.hkg.sysbench;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Data models, JSON persistence, Before/After comparison logic,
 * and self-contained Korean HTML/text report generator for HKG 5W SysBench.
 */
public final class BenchReport {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private BenchReport() {
    }

    public static final class BenchResult {
        public String version = "1.2.3";
        public String slot = "quick";
        public long timestampMs;
        public String dateStr = "";
        public String platform = "";
        public String product2 = "";
        public double uptimeSec;
        public boolean quickMode;
        public String abortedReason = "";

        // Animation scales observed during run
        public double animWindowScale = -1.0;
        public double animTransitionScale = -1.0;
        public double animDurationScale = -1.0;

        // Phase 1: Idle baseline
        public double idleDurationSec;
        public double idleCpuBusyPct;
        public double idleCpu0BusyPct;
        public double idleIowaitPct;
        public double idleIowaitMsPerMin;
        public double idleIrqPct;
        public double idleCpu0PeriphIrqPct;
        public long idlePeriphIrqDelta;
        public long idleEmmcIrqDelta;
        public double idleDiskWriteKbPerMin;
        public double idleDiskWriteOpsPerMin;
        public double idleDiskIoBusyMsPerMin;
        public double idleUiRunDelayMsPerSec;
        public double idleUiRunDelayMs;
        public double idleKsmdCpuMsPerMin;
        public double idleLoggerCpuMsPerMin;

        // Phase 2: Binder IPC & App Launch
        public double binderAvgUs;
        public double binderP99Us;
        public double binderMaxUs;
        public int launchSamplesCount;
        public double launchAvgWaitMs;
        public double launchMaxWaitMs;
        public String launchDetail = "";

        // Phase 3: Touch Scroll Jank & Active Load
        public String focusedWindow = "";
        public int sfFrameCount;
        public int sfJankCount;
        public int sfSevereJankCount;
        public double sfJankPct;
        public double sfRefreshMs = 16.67;
        public double sfP50FrameMs;
        public double sfP95FrameMs;
        public double sfP99FrameMs;
        public double sfMaxFrameMs;
        public double sfRenderReadyP95Ms;
        public double activeDurationSec;
        public double activeUiRunDelayMsPerSec;
        public double activeUiRunDelayMs;
        public double activeSfDelayMs;
        public double activeSystemServerDelayMs;
        public double activeCpu0PeriphIrqPct;
        public long activeTouchIrqDelta;
        public long activeDisplayIrqDelta;
        public long activeGpuIrqDelta;
        public long activeEmmcIrqDelta;
        public double activeIowaitMsPerMin;
        public double activeDiskIoBusyMsPerMin;

        // System snapshot & tunables
        public long memTotalKb;
        public long memAvailKb;
        public int processCount;
        public long pgmajfaultDelta;
        public int ksmRun = -1;
        public int ksmSleepMs = -1;
        public int readAheadKb = -1;
        public int swappiness = -1;
        public int dirtyRatio = -1;
        public int dirtyBgRatio = -1;
        public double thermalStartC = -1.0;
        public double thermalEndC = -1.0;
        public String cpu0Governor = "";
        public long cpu0CurKhz = -1L;
        public boolean eventlogRunning;
        public boolean tccLogdRunning;
        public boolean kloggerRunning;
        public String smpAffinityWritable = "unknown";
        public String activeTweaks = "none";
        public String idleTopCpuProcs = "";
        public String activeTopCpuProcs = "";

        public String toJson() {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("{\n");
            appendStr(sb, "version", version, true);
            appendStr(sb, "slot", slot, true);
            appendLong(sb, "timestampMs", timestampMs, true);
            appendStr(sb, "dateStr", dateStr, true);
            appendStr(sb, "platform", platform, true);
            appendStr(sb, "product2", product2, true);
            appendDbl(sb, "uptimeSec", uptimeSec, true);
            appendBool(sb, "quickMode", quickMode, true);
            appendStr(sb, "abortedReason", abortedReason, true);

            appendDbl(sb, "animWindowScale", animWindowScale, true);
            appendDbl(sb, "animTransitionScale", animTransitionScale, true);
            appendDbl(sb, "animDurationScale", animDurationScale, true);

            appendDbl(sb, "idleDurationSec", idleDurationSec, true);
            appendDbl(sb, "idleCpuBusyPct", idleCpuBusyPct, true);
            appendDbl(sb, "idleCpu0BusyPct", idleCpu0BusyPct, true);
            appendDbl(sb, "idleIowaitPct", idleIowaitPct, true);
            appendDbl(sb, "idleIowaitMsPerMin", idleIowaitMsPerMin, true);
            appendDbl(sb, "idleIrqPct", idleIrqPct, true);
            appendDbl(sb, "idleCpu0PeriphIrqPct", idleCpu0PeriphIrqPct, true);
            appendLong(sb, "idlePeriphIrqDelta", idlePeriphIrqDelta, true);
            appendLong(sb, "idleEmmcIrqDelta", idleEmmcIrqDelta, true);
            appendDbl(sb, "idleDiskWriteKbPerMin", idleDiskWriteKbPerMin, true);
            appendDbl(sb, "idleDiskWriteOpsPerMin", idleDiskWriteOpsPerMin, true);
            appendDbl(sb, "idleDiskIoBusyMsPerMin", idleDiskIoBusyMsPerMin, true);
            appendDbl(sb, "idleUiRunDelayMsPerSec", idleUiRunDelayMsPerSec, true);
            appendDbl(sb, "idleUiRunDelayMs", idleUiRunDelayMs, true);
            appendDbl(sb, "idleKsmdCpuMsPerMin", idleKsmdCpuMsPerMin, true);
            appendDbl(sb, "idleLoggerCpuMsPerMin", idleLoggerCpuMsPerMin, true);

            appendDbl(sb, "binderAvgUs", binderAvgUs, true);
            appendDbl(sb, "binderP99Us", binderP99Us, true);
            appendDbl(sb, "binderMaxUs", binderMaxUs, true);
            appendLong(sb, "launchSamplesCount", launchSamplesCount, true);
            appendDbl(sb, "launchAvgWaitMs", launchAvgWaitMs, true);
            appendDbl(sb, "launchMaxWaitMs", launchMaxWaitMs, true);
            appendStr(sb, "launchDetail", launchDetail, true);

            appendStr(sb, "focusedWindow", focusedWindow, true);
            appendLong(sb, "sfFrameCount", sfFrameCount, true);
            appendLong(sb, "sfJankCount", sfJankCount, true);
            appendLong(sb, "sfSevereJankCount", sfSevereJankCount, true);
            appendDbl(sb, "sfJankPct", sfJankPct, true);
            appendDbl(sb, "sfRefreshMs", sfRefreshMs, true);
            appendDbl(sb, "sfP50FrameMs", sfP50FrameMs, true);
            appendDbl(sb, "sfP95FrameMs", sfP95FrameMs, true);
            appendDbl(sb, "sfP99FrameMs", sfP99FrameMs, true);
            appendDbl(sb, "sfMaxFrameMs", sfMaxFrameMs, true);
            appendDbl(sb, "sfRenderReadyP95Ms", sfRenderReadyP95Ms, true);
            appendDbl(sb, "activeDurationSec", activeDurationSec, true);
            appendDbl(sb, "activeUiRunDelayMsPerSec", activeUiRunDelayMsPerSec, true);
            appendDbl(sb, "activeUiRunDelayMs", activeUiRunDelayMs, true);
            appendDbl(sb, "activeSfDelayMs", activeSfDelayMs, true);
            appendDbl(sb, "activeSystemServerDelayMs", activeSystemServerDelayMs, true);
            appendDbl(sb, "activeCpu0PeriphIrqPct", activeCpu0PeriphIrqPct, true);
            appendLong(sb, "activeTouchIrqDelta", activeTouchIrqDelta, true);
            appendLong(sb, "activeDisplayIrqDelta", activeDisplayIrqDelta, true);
            appendLong(sb, "activeGpuIrqDelta", activeGpuIrqDelta, true);
            appendLong(sb, "activeEmmcIrqDelta", activeEmmcIrqDelta, true);
            appendDbl(sb, "activeIowaitMsPerMin", activeIowaitMsPerMin, true);
            appendDbl(sb, "activeDiskIoBusyMsPerMin", activeDiskIoBusyMsPerMin, true);

            appendLong(sb, "memTotalKb", memTotalKb, true);
            appendLong(sb, "memAvailKb", memAvailKb, true);
            appendLong(sb, "processCount", processCount, true);
            appendLong(sb, "pgmajfaultDelta", pgmajfaultDelta, true);
            appendLong(sb, "ksmRun", ksmRun, true);
            appendLong(sb, "ksmSleepMs", ksmSleepMs, true);
            appendLong(sb, "readAheadKb", readAheadKb, true);
            appendLong(sb, "swappiness", swappiness, true);
            appendLong(sb, "dirtyRatio", dirtyRatio, true);
            appendLong(sb, "dirtyBgRatio", dirtyBgRatio, true);
            appendDbl(sb, "thermalStartC", thermalStartC, true);
            appendDbl(sb, "thermalEndC", thermalEndC, true);
            appendStr(sb, "cpu0Governor", cpu0Governor, true);
            appendLong(sb, "cpu0CurKhz", cpu0CurKhz, true);
            appendBool(sb, "eventlogRunning", eventlogRunning, true);
            appendBool(sb, "tccLogdRunning", tccLogdRunning, true);
            appendBool(sb, "kloggerRunning", kloggerRunning, true);
            appendStr(sb, "smpAffinityWritable", smpAffinityWritable, true);
            appendStr(sb, "activeTweaks", activeTweaks, true);
            appendStr(sb, "idleTopCpuProcs", idleTopCpuProcs, true);
            appendStr(sb, "activeTopCpuProcs", activeTopCpuProcs, false);
            sb.append("}\n");
            return sb.toString();
        }

        public static BenchResult fromJson(String json) {
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            Map<String, String> kv = parseFlatJsonObject(json);
            if (kv.isEmpty()) {
                return null;
            }
            BenchResult r = new BenchResult();
            r.version = strVal(kv, "version", "1.0.0");
            r.slot = strVal(kv, "slot", "unknown");
            r.timestampMs = longVal(kv, "timestampMs", 0L);
            r.dateStr = strVal(kv, "dateStr", "");
            r.platform = strVal(kv, "platform", "");
            r.product2 = strVal(kv, "product2", "");
            r.uptimeSec = dblVal(kv, "uptimeSec", 0.0);
            r.quickMode = boolVal(kv, "quickMode", false);
            r.abortedReason = strVal(kv, "abortedReason", "");

            r.animWindowScale = dblVal(kv, "animWindowScale", -1.0);
            r.animTransitionScale = dblVal(kv, "animTransitionScale", -1.0);
            r.animDurationScale = dblVal(kv, "animDurationScale", -1.0);

            r.idleDurationSec = dblVal(kv, "idleDurationSec", 0.0);
            r.idleCpuBusyPct = dblVal(kv, "idleCpuBusyPct", 0.0);
            r.idleCpu0BusyPct = dblVal(kv, "idleCpu0BusyPct", 0.0);
            r.idleIowaitPct = dblVal(kv, "idleIowaitPct", 0.0);
            r.idleIowaitMsPerMin = dblVal(kv, "idleIowaitMsPerMin", 0.0);
            r.idleIrqPct = dblVal(kv, "idleIrqPct", 0.0);
            r.idleCpu0PeriphIrqPct = dblVal(kv, "idleCpu0PeriphIrqPct", 0.0);
            r.idlePeriphIrqDelta = longVal(kv, "idlePeriphIrqDelta", 0L);
            r.idleEmmcIrqDelta = longVal(kv, "idleEmmcIrqDelta", 0L);
            r.idleDiskWriteKbPerMin = dblVal(kv, "idleDiskWriteKbPerMin", 0.0);
            r.idleDiskWriteOpsPerMin = dblVal(kv, "idleDiskWriteOpsPerMin", 0.0);
            r.idleDiskIoBusyMsPerMin = dblVal(kv, "idleDiskIoBusyMsPerMin", 0.0);
            r.idleUiRunDelayMsPerSec = dblVal(kv, "idleUiRunDelayMsPerSec", 0.0);
            r.idleUiRunDelayMs = dblVal(kv, "idleUiRunDelayMs", 0.0);
            r.idleKsmdCpuMsPerMin = dblVal(kv, "idleKsmdCpuMsPerMin", 0.0);
            r.idleLoggerCpuMsPerMin = dblVal(kv, "idleLoggerCpuMsPerMin", 0.0);

            r.binderAvgUs = dblVal(kv, "binderAvgUs", 0.0);
            r.binderP99Us = dblVal(kv, "binderP99Us", 0.0);
            r.binderMaxUs = dblVal(kv, "binderMaxUs", 0.0);
            r.launchSamplesCount = (int) longVal(kv, "launchSamplesCount", 0L);
            r.launchAvgWaitMs = dblVal(kv, "launchAvgWaitMs", 0.0);
            r.launchMaxWaitMs = dblVal(kv, "launchMaxWaitMs", 0.0);
            r.launchDetail = strVal(kv, "launchDetail", "");

            r.focusedWindow = strVal(kv, "focusedWindow", "");
            r.sfFrameCount = (int) longVal(kv, "sfFrameCount", 0L);
            r.sfJankCount = (int) longVal(kv, "sfJankCount", 0L);
            r.sfSevereJankCount = (int) longVal(kv, "sfSevereJankCount", 0L);
            r.sfJankPct = dblVal(kv, "sfJankPct", 0.0);
            r.sfRefreshMs = dblVal(kv, "sfRefreshMs", 16.67);
            r.sfP50FrameMs = dblVal(kv, "sfP50FrameMs", 0.0);
            r.sfP95FrameMs = dblVal(kv, "sfP95FrameMs", 0.0);
            r.sfP99FrameMs = dblVal(kv, "sfP99FrameMs", 0.0);
            r.sfMaxFrameMs = dblVal(kv, "sfMaxFrameMs", 0.0);
            r.sfRenderReadyP95Ms = dblVal(kv, "sfRenderReadyP95Ms", 0.0);
            r.activeDurationSec = dblVal(kv, "activeDurationSec", 0.0);
            r.activeUiRunDelayMsPerSec = dblVal(kv, "activeUiRunDelayMsPerSec", 0.0);
            r.activeUiRunDelayMs = dblVal(kv, "activeUiRunDelayMs", 0.0);
            r.activeSfDelayMs = dblVal(kv, "activeSfDelayMs", 0.0);
            r.activeSystemServerDelayMs = dblVal(kv, "activeSystemServerDelayMs", 0.0);
            r.activeCpu0PeriphIrqPct = dblVal(kv, "activeCpu0PeriphIrqPct", 0.0);
            r.activeTouchIrqDelta = longVal(kv, "activeTouchIrqDelta", 0L);
            r.activeDisplayIrqDelta = longVal(kv, "activeDisplayIrqDelta", 0L);
            r.activeGpuIrqDelta = longVal(kv, "activeGpuIrqDelta", 0L);
            r.activeEmmcIrqDelta = longVal(kv, "activeEmmcIrqDelta", 0L);
            r.activeIowaitMsPerMin = dblVal(kv, "activeIowaitMsPerMin", 0.0);
            r.activeDiskIoBusyMsPerMin = dblVal(kv, "activeDiskIoBusyMsPerMin", 0.0);

            r.memTotalKb = longVal(kv, "memTotalKb", 0L);
            r.memAvailKb = longVal(kv, "memAvailKb", 0L);
            r.processCount = (int) longVal(kv, "processCount", 0L);
            r.pgmajfaultDelta = longVal(kv, "pgmajfaultDelta", 0L);
            r.ksmRun = (int) longVal(kv, "ksmRun", -1L);
            r.ksmSleepMs = (int) longVal(kv, "ksmSleepMs", -1L);
            r.readAheadKb = (int) longVal(kv, "readAheadKb", -1L);
            r.swappiness = (int) longVal(kv, "swappiness", -1L);
            r.dirtyRatio = (int) longVal(kv, "dirtyRatio", -1L);
            r.dirtyBgRatio = (int) longVal(kv, "dirtyBgRatio", -1L);
            r.thermalStartC = dblVal(kv, "thermalStartC", -1.0);
            r.thermalEndC = dblVal(kv, "thermalEndC", -1.0);
            r.cpu0Governor = strVal(kv, "cpu0Governor", "");
            r.cpu0CurKhz = longVal(kv, "cpu0CurKhz", -1L);
            r.eventlogRunning = boolVal(kv, "eventlogRunning", false);
            r.tccLogdRunning = boolVal(kv, "tccLogdRunning", false);
            r.kloggerRunning = boolVal(kv, "kloggerRunning", false);
            r.smpAffinityWritable = strVal(kv, "smpAffinityWritable", "unknown");
            r.activeTweaks = strVal(kv, "activeTweaks", "none");
            r.idleTopCpuProcs = strVal(kv, "idleTopCpuProcs", "");
            r.activeTopCpuProcs = strVal(kv, "activeTopCpuProcs", "");
            return r;
        }
    }

    public static String formatTimestamp(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    public static String computeDeltaText(double before, double after, boolean lowerIsBetter) {
        if (before <= 0.0001 && after <= 0.0001) {
            return "변화 없음";
        }
        if (before <= 0.0001) {
            return String.format(Locale.US, "+%.2f", after);
        }
        double pct = ((after - before) / Math.abs(before)) * 100.0;
        boolean improved = lowerIsBetter ? (pct < -1.0) : (pct > 1.0);
        boolean worse = lowerIsBetter ? (pct > 1.0) : (pct < -1.0);
        String sign = pct > 0.0 ? "+" : "";
        String tag = improved ? " 개선" : (worse ? " 저하" : "");
        return String.format(Locale.US, "%s%.1f%%%s", sign, pct, tag);
    }

    public static boolean isImproved(double before, double after, boolean lowerIsBetter) {
        if (before <= 0.0001) {
            return false;
        }
        double pct = ((after - before) / Math.abs(before)) * 100.0;
        return lowerIsBetter ? (pct < -1.0) : (pct > 1.0);
    }

    public static boolean isWorse(double before, double after, boolean lowerIsBetter) {
        if (before <= 0.0001) {
            return false;
        }
        double pct = ((after - before) / Math.abs(before)) * 100.0;
        return lowerIsBetter ? (pct > 1.0) : (pct < -1.0);
    }

    public static String buildCaveatNotice(BenchResult before, BenchResult after) {
        StringBuilder sb = new StringBuilder();
        if (before != null && before.uptimeSec > 0.0 && before.uptimeSec < 90.0) {
            sb.append("• [주의] BEFORE 측정 시 부팅 직후(uptime ").append((int) before.uptimeSec)
              .append("초) 상태였습니다. 부팅 90초 이후 안정 상태에서 재측정을 권장합니다.\n");
        }
        if (after != null && after.uptimeSec > 0.0 && after.uptimeSec < 90.0) {
            sb.append("• [주의] AFTER 측정 시 부팅 직후(uptime ").append((int) after.uptimeSec)
              .append("초) 상태였습니다. 부팅 90초 이후 안정 상태에서 재측정을 권장합니다.\n");
        }
        if (before != null && after != null) {
            if (before.animTransitionScale >= 0.0 && after.animTransitionScale >= 0.0
                    && Math.abs(before.animTransitionScale - after.animTransitionScale) > 0.05) {
                sb.append(String.format(Locale.US,
                        "• [분리 해석] 애니메이션 배율이 %.1fx → %.1fx로 변경되었습니다. 앱 전환 WaitTime 단축에는 애니메이션 시간이 포함되며, 순수 커널/스케줄러 개선 여부는 '스크롤 프레임 Jank%%', 'UI Runqueue 대기지연', 'Binder P99', 'eMMC 쓰기량'으로 판단하세요.\n",
                        before.animTransitionScale, after.animTransitionScale));
            }
            if (!before.platform.isEmpty() && !after.platform.isEmpty()
                    && !before.platform.equals(after.platform)) {
                sb.append("• [경고] 서로 다른 하드웨어 보드(").append(before.platform)
                  .append(" vs ").append(after.platform).append(") 간 비교입니다.\n");
            }
        }
        return sb.toString().trim();
    }

    public static String buildTextSummary(BenchResult before, BenchResult after, BenchResult latest) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("====================================================================\n");
        sb.append(" HKG Gen5W SysBench 측정 리포트\n");
        sb.append("====================================================================\n");
        BenchResult ref = (after != null) ? after : (before != null ? before : latest);
        if (ref != null) {
            sb.append("플랫폼 / 차량 : ").append(ref.platform).append(" / ").append(ref.product2).append("\n");
            sb.append("최신 실행     : ").append(ref.slot.toUpperCase(Locale.US))
              .append(" (").append(ref.dateStr).append(", uptime ").append((int) ref.uptimeSec).append("s)\n");
            sb.append("IRQ Affinity  : ").append(ref.smpAffinityWritable)
              .append(" | 활성 트윅: ").append(ref.activeTweaks).append("\n");
        }
        String caveat = buildCaveatNotice(before, after);
        if (!caveat.isEmpty()) {
            sb.append("\n[실험 통제 / 해석 참고]\n").append(caveat).append("\n");
        }
        sb.append("\n");
        if (before != null && after != null) {
            sb.append(String.format(Locale.US, "%-30s | %12s | %12s | %s\n", "지표", "BEFORE(순정)", "AFTER(적용)", "변화"));
            sb.append("--------------------------------------------------------------------\n");
            appendRowTxt(sb, "스크롤 UI 대기지연 (ms/s)", before.activeUiRunDelayMsPerSec, after.activeUiRunDelayMsPerSec, true);
            appendRowTxt(sb, "아이들 UI 대기지연 (ms/s)", before.idleUiRunDelayMsPerSec, after.idleUiRunDelayMsPerSec, true);
            appendRowTxt(sb, "스크롤 프레임 Jank (%)", before.sfJankPct, after.sfJankPct, true);
            appendRowTxt(sb, "스크롤 P99 프레임 (ms)", before.sfP99FrameMs, after.sfP99FrameMs, true);
            appendRowTxt(sb, "스크롤 최악 프레임 (ms)", before.sfMaxFrameMs, after.sfMaxFrameMs, true);
            appendRowTxt(sb, "앱 진입 평균 WaitTime (ms)", before.launchAvgWaitMs, after.launchAvgWaitMs, true);
            appendRowTxt(sb, "Binder IPC P99 (us)", before.binderP99Us, after.binderP99Us, true);
            appendRowTxt(sb, "아이들 eMMC 쓰기 (KB/min)", before.idleDiskWriteKbPerMin, after.idleDiskWriteKbPerMin, true);
            appendRowTxt(sb, "아이들 IOwait (ms/min)", before.idleIowaitMsPerMin, after.idleIowaitMsPerMin, true);
            appendRowTxt(sb, "CPU0 주변장치 IRQ 비중 (%)", before.activeCpu0PeriphIrqPct, after.activeCpu0PeriphIrqPct, true);
            appendRowTxt(sb, "KSM(ksmd) CPU (ms/min)", before.idleKsmdCpuMsPerMin, after.idleKsmdCpuMsPerMin, true);
            appendRowTxt(sb, "로거 데몬 CPU (ms/min)", before.idleLoggerCpuMsPerMin, after.idleLoggerCpuMsPerMin, true);
            appendRowTxt(sb, "가용 메모리 근사 (MB)", before.memAvailKb / 1024.0, after.memAvailKb / 1024.0, false);
            appendRowTxt(sb, "상주 유저 프로세스 수 (개)", before.processCount, after.processCount, true);
            sb.append("\n[상위 CPU 점유 프로세스 Top 10]\n");
            sb.append("BEFORE 활성 트윅 : ").append(before.activeTweaks).append("\n");
            sb.append("AFTER  활성 트윅 : ").append(after.activeTweaks).append("\n");
            sb.append("BEFORE 아이들 Top: ").append(before.idleTopCpuProcs).append("\n");
            sb.append("AFTER  아이들 Top: ").append(after.idleTopCpuProcs).append("\n");
            sb.append("BEFORE 스크롤 Top: ").append(before.activeTopCpuProcs).append("\n");
            sb.append("AFTER  스크롤 Top: ").append(after.activeTopCpuProcs).append("\n");
        } else if (ref != null) {
            sb.append("[단일 측정 결과: ").append(ref.slot.toUpperCase(Locale.US)).append("]\n");
            sb.append(String.format(Locale.US, "• 스크롤 UI 대기지연 : %.2f ms/s (총 %.1f ms, SF %.1f ms, SS %.1f ms)\n",
                    ref.activeUiRunDelayMsPerSec, ref.activeUiRunDelayMs, ref.activeSfDelayMs, ref.activeSystemServerDelayMs));
            sb.append(String.format(Locale.US, "• 아이들 UI 대기지연 : %.2f ms/s (총 %.1f ms)\n",
                    ref.idleUiRunDelayMsPerSec, ref.idleUiRunDelayMs));
            sb.append(String.format(Locale.US, "• 스크롤 프레임 Jank : %.1f%% (%d/%d frames, P95 %.2fms, P99 %.2fms, Max %.2fms)\n",
                    ref.sfJankPct, ref.sfJankCount, ref.sfFrameCount, ref.sfP95FrameMs, ref.sfP99FrameMs, ref.sfMaxFrameMs));
            sb.append(String.format(Locale.US, "• 앱 진입 WaitTime   : avg %.1f ms, max %.1f ms (%s)\n",
                    ref.launchAvgWaitMs, ref.launchMaxWaitMs, ref.launchDetail));
            sb.append(String.format(Locale.US, "• Binder IPC 지연    : avg %.1f us, P99 %.1f us, max %.1f us\n",
                    ref.binderAvgUs, ref.binderP99Us, ref.binderMaxUs));
            sb.append(String.format(Locale.US, "• 아이들 eMMC 쓰기   : %.1f KB/min (%.1f ops/min, IO busy %.1f ms/min)\n",
                    ref.idleDiskWriteKbPerMin, ref.idleDiskWriteOpsPerMin, ref.idleDiskIoBusyMsPerMin));
            sb.append(String.format(Locale.US, "• 아이들 CPU / IOwait: busy %.1f%% (cpu0 %.1f%%), iowait %.2f%% (%.1f ms/min)\n",
                    ref.idleCpuBusyPct, ref.idleCpu0BusyPct, ref.idleIowaitPct, ref.idleIowaitMsPerMin));
            sb.append(String.format(Locale.US, "• CPU0 IRQ 편중률    : 아이들 %.1f%% / 스크롤 %.1f%% (eMMC Δ%d, Touch Δ%d, GPU Δ%d)\n",
                    ref.idleCpu0PeriphIrqPct, ref.activeCpu0PeriphIrqPct, ref.activeEmmcIrqDelta, ref.activeTouchIrqDelta, ref.activeGpuIrqDelta));
            sb.append(String.format(Locale.US, "• KSM / 로거 CPU     : ksmd %.1f ms/min, loggers %.1f ms/min\n",
                    ref.idleKsmdCpuMsPerMin, ref.idleLoggerCpuMsPerMin));
            sb.append(String.format(Locale.US, "• 메모리 / 프로세스  : 가용 %.1f / %.1f MB, 프로세스 %d개, MajorFault Δ%d\n",
                    ref.memAvailKb / 1024.0, ref.memTotalKb / 1024.0, ref.processCount, ref.pgmajfaultDelta));
            sb.append(String.format(Locale.US, "• 현재 튜닝 상태     : ksm.run=%d, ra=%dKB, swap=%d, dirty=%d/%d%%, anim=%.1fx, eventlog=%s, tcc_logd=%s\n",
                    ref.ksmRun, ref.readAheadKb, ref.swappiness, ref.dirtyRatio, ref.dirtyBgRatio,
                    ref.animTransitionScale, String.valueOf(ref.eventlogRunning), String.valueOf(ref.tccLogdRunning)));
            sb.append("• 아이들 CPU Top 10 : ").append(ref.idleTopCpuProcs).append("\n");
            sb.append("• 스크롤 CPU Top 10 : ").append(ref.activeTopCpuProcs).append("\n");
        }
        return sb.toString();
    }

    private static void appendRowTxt(StringBuilder sb, String label, double b, double a, boolean lowerBetter) {
        sb.append(String.format(Locale.US, "%-30s | %12.2f | %12.2f | %s\n",
                label, b, a, computeDeltaText(b, a, lowerBetter)));
    }

    public static String buildHtmlReport(BenchResult before, BenchResult after, BenchResult latest) {
        BenchResult ref = (after != null) ? after : (before != null ? before : latest);
        String titlePlat = (ref != null) ? (escapeHtml(ref.platform) + " (" + escapeHtml(ref.product2) + ")") : "Gen5W";
        String caveat = buildCaveatNotice(before, after);

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        sb.append("<title>HKG Gen5W SysBench Report</title>\n");
        sb.append("<style>\n");
        sb.append("body{margin:0;padding:24px;background:#0b111e;color:#e2e8f0;font-family:-apple-system,BlinkMacSystemFont,'Malgun Gothic',sans-serif;line-height:1.5;}\n");
        sb.append(".wrap{max-width:1080px;margin:0 auto;}\n");
        sb.append("h1{margin:0 0 6px;font-size:24px;color:#38bdf8;}\n");
        sb.append(".sub{color:#94a3b8;font-size:13px;margin-bottom:20px;}\n");
        sb.append(".warn{background:#451a03;border:1px solid #d97706;color:#fde68a;padding:12px 16px;border-radius:10px;margin-bottom:18px;font-size:13px;white-space:pre-line;}\n");
        sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-bottom:20px;}\n");
        sb.append(".card{background:#111a2e;border:1px solid #1e293b;border-radius:10px;padding:14px;}\n");
        sb.append(".card .k{font-size:12px;color:#94a3b8;margin-bottom:4px;}\n");
        sb.append(".card .v{font-size:20px;font-weight:700;color:#f8fafc;}\n");
        sb.append(".card .d{font-size:12px;margin-top:4px;font-weight:600;}\n");
        sb.append("table{width:100%;border-collapse:collapse;background:#111a2e;border:1px solid #1e293b;border-radius:10px;overflow:hidden;margin-bottom:22px;}\n");
        sb.append("th,td{padding:10px 12px;border-bottom:1px solid #1e293b;text-align:left;font-size:13px;}\n");
        sb.append("th{background:#0f172a;color:#94a3b8;font-weight:600;}\n");
        sb.append("td.num{text-align:right;font-variant-numeric:tabular-nums;}\n");
        sb.append(".good{color:#34d399;font-weight:700;}.bad{color:#f87171;font-weight:700;}.flat{color:#94a3b8;}\n");
        sb.append("</style></head><body><div class=\"wrap\">\n");
        sb.append("<h1>HKG Gen5W SysBench 성능 비교 리포트</h1>\n");
        sb.append("<div class=\"sub\">플랫폼: ").append(titlePlat);
        if (ref != null) {
            sb.append(" &nbsp;|&nbsp; 최종 측정: ").append(escapeHtml(ref.dateStr))
              .append(" &nbsp;|&nbsp; SMP Affinity 쓰기 가능: <b>").append(escapeHtml(ref.smpAffinityWritable)).append("</b>")
              .append(" &nbsp;|&nbsp; 활성 트윅: <b>").append(escapeHtml(ref.activeTweaks)).append("</b>");
        }
        sb.append("</div>\n");

        if (!caveat.isEmpty()) {
            sb.append("<div class=\"warn\">").append(escapeHtml(caveat)).append("</div>\n");
        }

        if (before != null && after != null) {
            sb.append("<div class=\"grid\">\n");
            appendCardHtml(sb, "스크롤 UI Runqueue 대기지연", String.format(Locale.US, "%.2f → %.2f ms/s", before.activeUiRunDelayMsPerSec, after.activeUiRunDelayMsPerSec), before.activeUiRunDelayMsPerSec, after.activeUiRunDelayMsPerSec, true);
            appendCardHtml(sb, "프레임 드랍 (Jank %)", String.format(Locale.US, "%.1f%% → %.1f%%", before.sfJankPct, after.sfJankPct), before.sfJankPct, after.sfJankPct, true);
            appendCardHtml(sb, "아이들 eMMC 쓰기량", String.format(Locale.US, "%.0f → %.0f KB/m", before.idleDiskWriteKbPerMin, after.idleDiskWriteKbPerMin), before.idleDiskWriteKbPerMin, after.idleDiskWriteKbPerMin, true);
            appendCardHtml(sb, "CPU0 주변장치 IRQ 편중", String.format(Locale.US, "%.1f%% → %.1f%%", before.activeCpu0PeriphIrqPct, after.activeCpu0PeriphIrqPct), before.activeCpu0PeriphIrqPct, after.activeCpu0PeriphIrqPct, true);
            sb.append("</div>\n");

            sb.append("<table><thead><tr><th>측정 항목</th><th style=\"text-align:right\">BEFORE (순정)</th><th style=\"text-align:right\">AFTER (최적화)</th><th style=\"text-align:right\">변화율</th></tr></thead><tbody>\n");
            appendRowHtml(sb, "스크롤 UI 스케줄러 대기지연 (ms/s)", before.activeUiRunDelayMsPerSec, after.activeUiRunDelayMsPerSec, true, "%.2f");
            appendRowHtml(sb, "아이들 UI 스케줄러 대기지연 (ms/s)", before.idleUiRunDelayMsPerSec, after.idleUiRunDelayMsPerSec, true, "%.2f");
            appendRowHtml(sb, "스크롤 프레임 Jank 비율 (%)", before.sfJankPct, after.sfJankPct, true, "%.1f%%");
            appendRowHtml(sb, "스크롤 P95 / P99 / Max (P99 ms)", before.sfP99FrameMs, after.sfP99FrameMs, true, "%.2f");
            appendRowHtml(sb, "스크롤 최악 프레임 Max (ms)", before.sfMaxFrameMs, after.sfMaxFrameMs, true, "%.2f");
            appendRowHtml(sb, "앱 진입 평균 WaitTime (ms)", before.launchAvgWaitMs, after.launchAvgWaitMs, true, "%.1f");
            appendRowHtml(sb, "Binder IPC P99 지연 (us)", before.binderP99Us, after.binderP99Us, true, "%.1f");
            appendRowHtml(sb, "아이들 eMMC 쓰기량 (KB/min)", before.idleDiskWriteKbPerMin, after.idleDiskWriteKbPerMin, true, "%.1f");
            appendRowHtml(sb, "아이들 IOwait 시간 (ms/min)", before.idleIowaitMsPerMin, after.idleIowaitMsPerMin, true, "%.1f");
            appendRowHtml(sb, "스크롤 중 CPU0 IRQ 편중률 (%)", before.activeCpu0PeriphIrqPct, after.activeCpu0PeriphIrqPct, true, "%.1f%%");
            appendRowHtml(sb, "KSM(ksmd) CPU 소모 (ms/min)", before.idleKsmdCpuMsPerMin, after.idleKsmdCpuMsPerMin, true, "%.1f");
            appendRowHtml(sb, "상시 로거 CPU 소모 (ms/min)", before.idleLoggerCpuMsPerMin, after.idleLoggerCpuMsPerMin, true, "%.1f");
            appendRowHtml(sb, "가용 메모리 근사치 (MB)", before.memAvailKb / 1024.0, after.memAvailKb / 1024.0, false, "%.1f");
            appendRowHtml(sb, "상주 유저 프로세스 수 (개)", before.processCount, after.processCount, true, "%.0f");
            appendRowHtml(sb, "최고 온도 (C)", before.thermalEndC, after.thermalEndC, true, "%.1f");
            sb.append("</tbody></table>\n");

            sb.append("<table><thead><tr><th>커널/시스템 설정</th><th>BEFORE</th><th>AFTER</th></tr></thead><tbody>\n");
            appendStrRowHtml(sb, "애니메이션 배율 (Window/Trans/Anim)", fmtAnim(before), fmtAnim(after));
            appendStrRowHtml(sb, "eMMC read_ahead_kb", String.valueOf(before.readAheadKb), String.valueOf(after.readAheadKb));
            appendStrRowHtml(sb, "KSM (run / sleep_ms)", before.ksmRun + " / " + before.ksmSleepMs, after.ksmRun + " / " + after.ksmSleepMs);
            appendStrRowHtml(sb, "VM (swappiness / dirty / dirty_bg)", before.swappiness + " / " + before.dirtyRatio + " / " + before.dirtyBgRatio,
                    after.swappiness + " / " + after.dirtyRatio + " / " + after.dirtyBgRatio);
            appendStrRowHtml(sb, "상시 로거 (eventlog / tcc_logd / klogger)", fmtLoggers(before), fmtLoggers(after));
            appendStrRowHtml(sb, "CPU0 Governor / Freq (kHz)", before.cpu0Governor + " / " + before.cpu0CurKhz, after.cpu0Governor + " / " + after.cpu0CurKhz);
            appendStrRowHtml(sb, "활성 트윅 (activeTweaks)", before.activeTweaks, after.activeTweaks);
            appendStrRowHtml(sb, "아이들 상위 CPU Top 10", before.idleTopCpuProcs, after.idleTopCpuProcs);
            appendStrRowHtml(sb, "스크롤 상위 CPU Top 10", before.activeTopCpuProcs, after.activeTopCpuProcs);
            sb.append("</tbody></table>\n");
        } else if (ref != null) {
            sb.append("<table><thead><tr><th>측정 항목 (").append(escapeHtml(ref.slot.toUpperCase(Locale.US))).append(")</th><th style=\"text-align:right\">측정값</th></tr></thead><tbody>\n");
            appendSingleHtml(sb, "스크롤 UI 스케줄러 대기지연", String.format(Locale.US, "%.2f ms/s (총 %.1f ms)", ref.activeUiRunDelayMsPerSec, ref.activeUiRunDelayMs));
            appendSingleHtml(sb, "아이들 UI 스케줄러 대기지연", String.format(Locale.US, "%.2f ms/s (총 %.1f ms)", ref.idleUiRunDelayMsPerSec, ref.idleUiRunDelayMs));
            appendSingleHtml(sb, "스크롤 프레임 Jank", String.format(Locale.US, "%.1f%% (%d / %d frames, P95 %.2fms, P99 %.2fms, Max %.2fms)", ref.sfJankPct, ref.sfJankCount, ref.sfFrameCount, ref.sfP95FrameMs, ref.sfP99FrameMs, ref.sfMaxFrameMs));
            appendSingleHtml(sb, "앱 진입 WaitTime", String.format(Locale.US, "평균 %.1f ms / 최대 %.1f ms (%s)", ref.launchAvgWaitMs, ref.launchMaxWaitMs, ref.launchDetail));
            appendSingleHtml(sb, "Binder IPC 지연", String.format(Locale.US, "평균 %.1f us / P99 %.1f us / 최대 %.1f us", ref.binderAvgUs, ref.binderP99Us, ref.binderMaxUs));
            appendSingleHtml(sb, "아이들 eMMC 쓰기량", String.format(Locale.US, "%.1f KB/min (%.1f ops/min, IO busy %.1f ms/min)", ref.idleDiskWriteKbPerMin, ref.idleDiskWriteOpsPerMin, ref.idleDiskIoBusyMsPerMin));
            appendSingleHtml(sb, "아이들 CPU / IOwait", String.format(Locale.US, "전체 %.1f%% (CPU0 %.1f%%), IOwait %.2f%% (%.1f ms/min)", ref.idleCpuBusyPct, ref.idleCpu0BusyPct, ref.idleIowaitPct, ref.idleIowaitMsPerMin));
            appendSingleHtml(sb, "CPU0 주변장치 IRQ 편중", String.format(Locale.US, "아이들 %.1f%% / 스크롤 %.1f%% (eMMC Δ%d, Touch Δ%d, GPU Δ%d)", ref.idleCpu0PeriphIrqPct, ref.activeCpu0PeriphIrqPct, ref.activeEmmcIrqDelta, ref.activeTouchIrqDelta, ref.activeGpuIrqDelta));
            appendSingleHtml(sb, "KSM / 로거 데몬 CPU", String.format(Locale.US, "ksmd %.1f ms/min | loggers %.1f ms/min", ref.idleKsmdCpuMsPerMin, ref.idleLoggerCpuMsPerMin));
            appendSingleHtml(sb, "가용 메모리 / 유저 프로세스", String.format(Locale.US, "%.1f / %.1f MB (%d 프로세스)", ref.memAvailKb / 1024.0, ref.memTotalKb / 1024.0, ref.processCount));
            appendSingleHtml(sb, "런타임 튜닝 파라미터", String.format(Locale.US, "ksm=%d, ra=%dKB, swap=%d, dirty=%d/%d, anim=%s, log=%s", ref.ksmRun, ref.readAheadKb, ref.swappiness, ref.dirtyRatio, ref.dirtyBgRatio, fmtAnim(ref), fmtLoggers(ref)));
            appendSingleHtml(sb, "활성 트윅", ref.activeTweaks);
            appendSingleHtml(sb, "아이들 상위 CPU Top 10", ref.idleTopCpuProcs);
            appendSingleHtml(sb, "스크롤 상위 CPU Top 10", ref.activeTopCpuProcs);
            sb.append("</tbody></table>\n");
        }
        sb.append("</div></body></html>\n");
        return sb.toString();
    }

    private static String fmtAnim(BenchResult r) {
        return String.format(Locale.US, "%.1f / %.1f / %.1f", r.animWindowScale, r.animTransitionScale, r.animDurationScale);
    }

    private static String fmtLoggers(BenchResult r) {
        return "ev=" + r.eventlogRunning + ", tcc=" + r.tccLogdRunning + ", klog=" + r.kloggerRunning;
    }

    private static void appendCardHtml(StringBuilder sb, String title, String val, double b, double a, boolean lowerBetter) {
        String cls = isImproved(b, a, lowerBetter) ? "good" : (isWorse(b, a, lowerBetter) ? "bad" : "flat");
        sb.append("<div class=\"card\"><div class=\"k\">").append(escapeHtml(title))
          .append("</div><div class=\"v\">").append(escapeHtml(val))
          .append("</div><div class=\"d ").append(cls).append("\">")
          .append(escapeHtml(computeDeltaText(b, a, lowerBetter))).append("</div></div>\n");
    }

    private static void appendRowHtml(StringBuilder sb, String label, double b, double a, boolean lowerBetter, String fmt) {
        String cls = isImproved(b, a, lowerBetter) ? "good" : (isWorse(b, a, lowerBetter) ? "bad" : "flat");
        sb.append("<tr><td>").append(escapeHtml(label))
          .append("</td><td class=\"num\">").append(String.format(Locale.US, fmt, b))
          .append("</td><td class=\"num\">").append(String.format(Locale.US, fmt, a))
          .append("</td><td class=\"num ").append(cls).append("\">").append(escapeHtml(computeDeltaText(b, a, lowerBetter)))
          .append("</td></tr>\n");
    }

    private static void appendStrRowHtml(StringBuilder sb, String label, String b, String a) {
        sb.append("<tr><td>").append(escapeHtml(label))
          .append("</td><td>").append(escapeHtml(b))
          .append("</td><td>").append(escapeHtml(a)).append("</td></tr>\n");
    }

    private static void appendSingleHtml(StringBuilder sb, String label, String val) {
        sb.append("<tr><td>").append(escapeHtml(label))
          .append("</td><td class=\"num\">").append(escapeHtml(val)).append("</td></tr>\n");
    }

    public static String saveResults(File resultsDir, BenchResult current) {
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }
        String slotName = ("before".equals(current.slot) || "after".equals(current.slot))
                ? current.slot : "quick";
        writeUtf8(new File(resultsDir, slotName + ".json"), current.toJson());

        BenchResult before = loadSlot(resultsDir, "before");
        BenchResult after = loadSlot(resultsDir, "after");
        if ("before".equals(slotName)) {
            before = current;
        } else if ("after".equals(slotName)) {
            after = current;
        }

        String summaryTxt = buildTextSummary(before, after, current);
        String htmlReport = buildHtmlReport(before, after, current);
        writeUtf8(new File(resultsDir, "summary.txt"), summaryTxt);
        writeUtf8(new File(resultsDir, "Gen5W_Bench_Report.html"), htmlReport);

        File usbDir = KernelCollectors.findMountedUsbBenchDir();
        if (usbDir != null) {
            writeUtf8(new File(usbDir, slotName + ".json"), current.toJson());
            if (before != null && !"before".equals(slotName)) {
                writeUtf8(new File(usbDir, "before.json"), before.toJson());
            }
            if (after != null && !"after".equals(slotName)) {
                writeUtf8(new File(usbDir, "after.json"), after.toJson());
            }
            writeUtf8(new File(usbDir, "summary.txt"), summaryTxt);
            writeUtf8(new File(usbDir, "Gen5W_Bench_Report.html"), htmlReport);
            File bootProbe = new File(resultsDir, "boot_kernel_probe.txt");
            if (bootProbe.exists()) {
                writeUtf8(new File(usbDir, "boot_kernel_probe.txt"), KernelCollectors.readFileUtf8(bootProbe.getAbsolutePath()));
            }
            return usbDir.getAbsolutePath();
        }
        return resultsDir.getAbsolutePath();
    }

    public static String exportExistingToUsb(File resultsDir) {
        BenchResult before = loadSlot(resultsDir, "before");
        BenchResult after = loadSlot(resultsDir, "after");
        BenchResult quick = loadSlot(resultsDir, "quick");
        BenchResult latest = (after != null) ? after : (before != null ? before : quick);
        if (latest == null) {
            return "";
        }
        File usbDir = KernelCollectors.findMountedUsbBenchDir();
        if (usbDir == null) {
            return "";
        }
        if (before != null) writeUtf8(new File(usbDir, "before.json"), before.toJson());
        if (after != null) writeUtf8(new File(usbDir, "after.json"), after.toJson());
        if (quick != null) writeUtf8(new File(usbDir, "quick.json"), quick.toJson());
        writeUtf8(new File(usbDir, "summary.txt"), buildTextSummary(before, after, latest));
        writeUtf8(new File(usbDir, "Gen5W_Bench_Report.html"), buildHtmlReport(before, after, latest));
        File bootProbe = new File(resultsDir, "boot_kernel_probe.txt");
        if (bootProbe.exists()) {
            writeUtf8(new File(usbDir, "boot_kernel_probe.txt"), KernelCollectors.readFileUtf8(bootProbe.getAbsolutePath()));
        }
        return usbDir.getAbsolutePath();
    }

    public static BenchResult loadSlot(File resultsDir, String slot) {
        File f = new File(resultsDir, slot + ".json");
        if (!f.exists() || !f.canRead()) {
            return null;
        }
        return BenchResult.fromJson(KernelCollectors.readFileUtf8(f.getAbsolutePath()));
    }

    public static void writeUtf8(File f, String content) {
        FileOutputStream fos = null;
        try {
            if (f.getParentFile() != null && !f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            fos = new FileOutputStream(f);
            fos.write(content.getBytes(UTF8));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void appendStr(StringBuilder sb, String k, String v, boolean comma) {
        sb.append("  \"").append(escapeJson(k)).append("\": \"").append(escapeJson(v == null ? "" : v)).append("\"");
        sb.append(comma ? ",\n" : "\n");
    }

    private static void appendLong(StringBuilder sb, String k, long v, boolean comma) {
        sb.append("  \"").append(escapeJson(k)).append("\": ").append(v);
        sb.append(comma ? ",\n" : "\n");
    }

    private static void appendDbl(StringBuilder sb, String k, double v, boolean comma) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            v = 0.0;
        }
        sb.append("  \"").append(escapeJson(k)).append("\": ").append(String.format(Locale.US, "%.4f", v));
        sb.append(comma ? ",\n" : "\n");
    }

    private static void appendBool(StringBuilder sb, String k, boolean v, boolean comma) {
        sb.append("  \"").append(escapeJson(k)).append("\": ").append(v ? "true" : "false");
        sb.append(comma ? ",\n" : "\n");
    }

    public static String buildFlatJson(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        if (kv != null && !kv.isEmpty()) {
            int idx = 0;
            int total = kv.size();
            for (Map.Entry<String, String> ent : kv.entrySet()) {
                idx++;
                appendStr(sb, ent.getKey(), ent.getValue(), idx < total);
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '"') out.append("\\\"");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else out.append(c);
        }
        return out.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') out.append("&amp;");
            else if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '"') out.append("&quot;");
            else out.append(c);
        }
        return out.toString();
    }

    public static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        int len = json.length();
        int idx = 0;
        while (idx < len && json.charAt(idx) != '{') {
            idx++;
        }
        if (idx >= len) {
            return map;
        }
        idx++;
        while (idx < len) {
            idx = skipWsAndCommas(json, idx);
            if (idx >= len || json.charAt(idx) == '}') {
                break;
            }
            if (json.charAt(idx) != '"') {
                idx++;
                continue;
            }
            int[] nextIdx = new int[1];
            String key = readJsonString(json, idx, nextIdx);
            idx = skipWs(json, nextIdx[0]);
            if (idx >= len || json.charAt(idx) != ':') {
                break;
            }
            idx = skipWs(json, idx + 1);
            if (idx >= len) {
                break;
            }
            String val;
            if (json.charAt(idx) == '"') {
                val = readJsonString(json, idx, nextIdx);
                idx = nextIdx[0];
            } else {
                int start = idx;
                while (idx < len) {
                    char ch = json.charAt(idx);
                    if (ch == ',' || ch == '}' || ch == '\n' || ch == '\r') {
                        break;
                    }
                    idx++;
                }
                val = json.substring(start, idx).trim();
            }
            map.put(key, val);
        }
        return map;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            i++;
        }
        return i;
    }

    private static int skipWsAndCommas(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != ',') {
                break;
            }
            i++;
        }
        return i;
    }

    private static String readJsonString(String s, int quoteIdx, int[] outNext) {
        StringBuilder sb = new StringBuilder();
        int i = quoteIdx + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                outNext[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'n') sb.append('\n');
                else if (n == 'r') sb.append('\r');
                else if (n == 't') sb.append('\t');
                else sb.append(n);
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        outNext[0] = i;
        return sb.toString();
    }

    private static String strVal(Map<String, String> m, String k, String defVal) {
        String v = m.get(k);
        return v != null ? v : defVal;
    }

    private static long longVal(Map<String, String> m, String k, long defVal) {
        return KernelCollectors.parseLongSafe(m.get(k), defVal);
    }

    private static double dblVal(Map<String, String> m, String k, double defVal) {
        return KernelCollectors.parseDoubleSafe(m.get(k), defVal);
    }

    private static boolean boolVal(Map<String, String> m, String k, boolean defVal) {
        String v = m.get(k);
        if (v == null) return defVal;
        return "true".equalsIgnoreCase(v.trim());
    }
}
