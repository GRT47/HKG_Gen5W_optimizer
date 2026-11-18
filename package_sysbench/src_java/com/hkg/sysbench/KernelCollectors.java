package com.hkg.sysbench;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zero-fork kernel and system state collectors for Gen5W (Linux 3.18 / Android 4.4.2).
 * All /proc and /sys reads happen in-process via FileInputStream without spawning child processes.
 */
public final class KernelCollectors {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private KernelCollectors() {
    }

    public static String readFileUtf8(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) {
            return "";
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), UTF8);
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static String readFirstLine(String path) {
        String s = readFileUtf8(path);
        if (s.isEmpty()) {
            return "";
        }
        int idx = s.indexOf('\n');
        return (idx >= 0 ? s.substring(0, idx) : s).trim();
    }

    public static long parseLongSafe(String s, long defVal) {
        if (s == null) {
            return defVal;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return defVal;
        }
        try {
            return Long.parseLong(t);
        } catch (Throwable ignored) {
            return defVal;
        }
    }

    public static double parseDoubleSafe(String s, double defVal) {
        if (s == null) {
            return defVal;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return defVal;
        }
        try {
            return Double.parseDouble(t);
        } catch (Throwable ignored) {
            return defVal;
        }
    }

    public static String getSystemProperty(String key, String defVal) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, defVal);
            return v != null ? String.valueOf(v) : defVal;
        } catch (Throwable ignored) {
            return defVal;
        }
    }

    public static double readUptimeSeconds() {
        String line = readFirstLine("/proc/uptime");
        if (line.isEmpty()) {
            return 0.0;
        }
        int sp = line.indexOf(' ');
        String first = (sp > 0) ? line.substring(0, sp) : line;
        return parseDoubleSafe(first, 0.0);
    }

    public static String readBoardPlatform() {
        String plat = getSystemProperty("ro.board.platform", "");
        if (!plat.isEmpty()) {
            return plat;
        }
        String hw = getSystemProperty("ro.hardware", "");
        if (!hw.isEmpty()) {
            return hw;
        }
        String cpuinfo = readFileUtf8("/proc/cpuinfo").toLowerCase(Locale.US);
        if (cpuinfo.contains("tcc803")) {
            return "tcc803x";
        }
        if (cpuinfo.contains("tcc897")) {
            return "tcc897x";
        }
        return "unknown";
    }

    public static String findBlockQueueDir() {
        String[] cands = new String[]{"/sys/block/mmcblk0/queue", "/sys/block/vda/queue"};
        for (int i = 0; i < cands.length; i++) {
            if (new File(cands[i]).isDirectory()) {
                return cands[i];
            }
        }
        return "";
    }

    /**
     * Resolves an actually mounted USB storage path from /proc/mounts.
     * Returns null if no real USB drive is mounted (avoids false positives on rootfs /storage/usb0 dir).
     */
    public static File findMountedUsbBenchDir() {
        String mounts = readFileUtf8("/proc/mounts");
        if (mounts.isEmpty()) {
            return null;
        }
        String[] candidates = new String[]{
                "/storage/usb0",
                "/mnt/media_rw/usb0",
                "/storage/usb1",
                "/mnt/media_rw/usb1"
        };
        String[] lines = mounts.split("\n");
        for (int i = 0; i < candidates.length; i++) {
            String target = candidates[i];
            boolean mounted = false;
            for (int j = 0; j < lines.length; j++) {
                String[] cols = lines[j].trim().split("\\s+");
                if (cols.length >= 3 && target.equals(cols[1])) {
                    String fs = cols[2].toLowerCase(Locale.US);
                    if (!"rootfs".equals(fs) && !"tmpfs".equals(fs)) {
                        mounted = true;
                        break;
                    }
                }
            }
            if (mounted) {
                File dir = new File(target, "HKG_SysBench");
                if (dir.exists() || dir.mkdirs()) {
                    if (dir.canWrite()) {
                        return dir;
                    }
                }
            }
        }
        return null;
    }

    // =========================================================================
    // 1. /proc/stat (CPU Busy %, IOwait %, IRQ+SoftIRQ %)
    // =========================================================================

    public static final class CpuCounters {
        public long user;
        public long nice;
        public long system;
        public long idle;
        public long iowait;
        public long irq;
        public long softirq;
        public long steal;

        public long total() {
            return user + nice + system + idle + iowait + irq + softirq + steal;
        }

        public long active() {
            return user + nice + system + irq + softirq + steal;
        }
    }

    public static final class CpuSnapshot {
        public CpuCounters total = new CpuCounters();
        public List<CpuCounters> cores = new ArrayList<CpuCounters>();
        public long ctxtSwitches;
        public long intrTotal;

        public static CpuSnapshot capture() {
            return parse(readFileUtf8("/proc/stat"));
        }

        public static CpuSnapshot parse(String raw) {
            CpuSnapshot snap = new CpuSnapshot();
            if (raw == null || raw.isEmpty()) {
                return snap;
            }
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols = line.split("\\s+");
                if (cols.length < 2) {
                    continue;
                }
                String tag = cols[0];
                if ("cpu".equals(tag)) {
                    snap.total = parseCpuLine(cols);
                } else if (tag.startsWith("cpu") && tag.length() > 3 && Character.isDigit(tag.charAt(3))) {
                    snap.cores.add(parseCpuLine(cols));
                } else if ("ctxt".equals(tag)) {
                    snap.ctxtSwitches = parseLongSafe(cols[1], 0L);
                } else if ("intr".equals(tag)) {
                    snap.intrTotal = parseLongSafe(cols[1], 0L);
                }
            }
            return snap;
        }

        private static CpuCounters parseCpuLine(String[] cols) {
            CpuCounters c = new CpuCounters();
            if (cols.length > 1) c.user = parseLongSafe(cols[1], 0L);
            if (cols.length > 2) c.nice = parseLongSafe(cols[2], 0L);
            if (cols.length > 3) c.system = parseLongSafe(cols[3], 0L);
            if (cols.length > 4) c.idle = parseLongSafe(cols[4], 0L);
            if (cols.length > 5) c.iowait = parseLongSafe(cols[5], 0L);
            if (cols.length > 6) c.irq = parseLongSafe(cols[6], 0L);
            if (cols.length > 7) c.softirq = parseLongSafe(cols[7], 0L);
            if (cols.length > 8) c.steal = parseLongSafe(cols[8], 0L);
            return c;
        }
    }

    public static final class CpuDelta {
        public double totalBusyPct;
        public double iowaitPct;
        public double irqPct;
        public double iowaitMsPerMin;
        public double ctxtPerSec;
        public double[] coreBusyPct = new double[0];

        public static CpuDelta compute(CpuSnapshot t0, CpuSnapshot t1, double elapsedSec) {
            CpuDelta d = new CpuDelta();
            if (t0 == null || t1 == null || elapsedSec <= 0.0) {
                return d;
            }
            long totDiff = t1.total.total() - t0.total.total();
            if (totDiff > 0L) {
                long actDiff = Math.max(0L, t1.total.active() - t0.total.active());
                long ioDiff = Math.max(0L, t1.total.iowait - t0.total.iowait);
                long irqDiff = Math.max(0L, (t1.total.irq + t1.total.softirq) - (t0.total.irq + t0.total.softirq));
                d.totalBusyPct = (100.0 * actDiff) / totDiff;
                d.iowaitPct = (100.0 * ioDiff) / totDiff;
                d.irqPct = (100.0 * irqDiff) / totDiff;
                // Gen5W CONFIG_HZ=100 -> 1 jiffy = 10 ms
                d.iowaitMsPerMin = (ioDiff * 10.0 * 60.0) / elapsedSec;
            }
            if (t1.ctxtSwitches >= t0.ctxtSwitches) {
                d.ctxtPerSec = (t1.ctxtSwitches - t0.ctxtSwitches) / elapsedSec;
            }
            int nCores = Math.min(t0.cores.size(), t1.cores.size());
            d.coreBusyPct = new double[nCores];
            for (int i = 0; i < nCores; i++) {
                CpuCounters c0 = t0.cores.get(i);
                CpuCounters c1 = t1.cores.get(i);
                long cdTot = c1.total() - c0.total();
                if (cdTot > 0L) {
                    long cdAct = Math.max(0L, c1.active() - c0.active());
                    d.coreBusyPct[i] = (100.0 * cdAct) / cdTot;
                }
            }
            return d;
        }
    }

    // =========================================================================
    // 2. /proc/interrupts (Peripheral Hardware IRQ CPU0 Skew)
    // =========================================================================

    public static final class IrqRow {
        public String label = "";
        public String device = "";
        public long[] counts = new long[0];
        public boolean isPeripheral;
    }

    public static final class IrqSnapshot {
        public int cpuCount;
        public List<IrqRow> rows = new ArrayList<IrqRow>();

        public static IrqSnapshot capture() {
            return parse(readFileUtf8("/proc/interrupts"));
        }

        public static IrqSnapshot parse(String raw) {
            IrqSnapshot snap = new IrqSnapshot();
            if (raw == null || raw.isEmpty()) {
                return snap;
            }
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (snap.cpuCount == 0 && line.startsWith("CPU0")) {
                    String[] hdr = line.split("\\s+");
                    int cnt = 0;
                    for (int h = 0; h < hdr.length; h++) {
                        if (hdr[h].startsWith("CPU")) {
                            cnt++;
                        }
                    }
                    snap.cpuCount = cnt > 0 ? cnt : 4;
                    continue;
                }
                if (snap.cpuCount == 0) {
                    snap.cpuCount = 4;
                }
                int colonIdx = line.indexOf(':');
                if (colonIdx <= 0) {
                    continue;
                }
                String label = line.substring(0, colonIdx).trim();
                if ("Err".equalsIgnoreCase(label) || "MIS".equalsIgnoreCase(label)) {
                    continue;
                }
                String rest = line.substring(colonIdx + 1).trim();
                if (rest.isEmpty()) {
                    continue;
                }
                String[] cols = rest.split("\\s+");
                if (cols.length < snap.cpuCount) {
                    continue;
                }
                IrqRow row = new IrqRow();
                row.label = label;
                row.counts = new long[snap.cpuCount];
                boolean ok = true;
                for (int c = 0; c < snap.cpuCount; c++) {
                    long val = parseLongSafe(cols[c], -1L);
                    if (val < 0L) {
                        ok = false;
                        break;
                    }
                    row.counts[c] = val;
                }
                if (!ok) {
                    continue;
                }
                StringBuilder devSb = new StringBuilder();
                for (int k = snap.cpuCount; k < cols.length; k++) {
                    if (devSb.length() > 0) {
                        devSb.append(' ');
                    }
                    devSb.append(cols[k]);
                }
                row.device = devSb.toString();
                row.isPeripheral = isNumeric(label) && !row.device.contains("arch_timer");
                snap.rows.add(row);
            }
            return snap;
        }

        private static boolean isNumeric(String s) {
            if (s == null || s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class IrqDelta {
        public long peripheralTotalDelta;
        public long[] periphPerCpu = new long[4];
        public double cpu0PeripheralSharePct;
        public long touchDelta;
        public long displayDelta;
        public long gpuDelta;
        public long emmcDelta;
        public long wifiDelta;
        public long usbDelta;
        public long uartDelta;
        public long audioDelta;

        public static IrqDelta compute(IrqSnapshot t0, IrqSnapshot t1) {
            IrqDelta d = new IrqDelta();
            if (t0 == null || t1 == null) {
                return d;
            }
            int cpus = Math.max(1, t1.cpuCount);
            d.periphPerCpu = new long[cpus];
            Map<String, IrqRow> prevMap = new HashMap<String, IrqRow>();
            for (int i = 0; i < t0.rows.size(); i++) {
                IrqRow r = t0.rows.get(i);
                prevMap.put(r.label, r);
            }
            for (int i = 0; i < t1.rows.size(); i++) {
                IrqRow r1 = t1.rows.get(i);
                if (!r1.isPeripheral) {
                    continue;
                }
                IrqRow r0 = prevMap.get(r1.label);
                long rowSum = 0L;
                for (int c = 0; c < cpus; c++) {
                    long c1 = c < r1.counts.length ? r1.counts[c] : 0L;
                    long c0 = (r0 != null && c < r0.counts.length) ? r0.counts[c] : 0L;
                    long diff = (c1 >= c0) ? (c1 - c0) : 0L;
                    d.periphPerCpu[c] += diff;
                    rowSum += diff;
                }
                d.peripheralTotalDelta += rowSum;
                String dev = r1.device.toLowerCase(Locale.US);
                if (dev.contains("mip4_ts") || dev.contains("fts")) {
                    d.touchDelta += rowSum;
                } else if (dev.contains("tcc_main_d") || dev.contains("wmixer")) {
                    d.displayDelta += rowSum;
                } else if (dev.contains("mali") || dev.contains("10100000.gpu")) {
                    d.gpuDelta += rowSum;
                } else if (dev.contains("tcc-sdhc0") || dev.contains("mmc0")) {
                    d.emmcDelta += rowSum;
                } else if (dev.contains("tcc-sdhc2") || dev.contains("mmc1")) {
                    d.wifiDelta += rowSum;
                } else if (dev.contains("dwc_otg") || dev.contains("ehci_hcd") || dev.contains("ohci_hcd")) {
                    d.usbDelta += rowSum;
                } else if (dev.contains("uart")) {
                    d.uartDelta += rowSum;
                } else if (dev.contains("alsa") || dev.contains("adma-pcm")) {
                    d.audioDelta += rowSum;
                }
            }
            if (d.peripheralTotalDelta > 0L && d.periphPerCpu.length > 0) {
                d.cpu0PeripheralSharePct = (100.0 * d.periphPerCpu[0]) / d.peripheralTotalDelta;
            }
            return d;
        }
    }

    // =========================================================================
    // 3. /proc/diskstats (mmcblk0 eMMC Read/Write Volume & IO Ticks)
    // =========================================================================

    public static final class DiskSnapshot {
        public long readIos;
        public long readSectors;
        public long readTicksMs;
        public long writeIos;
        public long writeSectors;
        public long writeTicksMs;
        public long ioTicksMs;

        public static DiskSnapshot capture() {
            return parse(readFileUtf8("/proc/diskstats"), "mmcblk0");
        }

        public static DiskSnapshot parse(String raw, String devName) {
            DiskSnapshot snap = new DiskSnapshot();
            if (raw == null || raw.isEmpty()) {
                return snap;
            }
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String[] cols = lines[i].trim().split("\\s+");
                if (cols.length >= 14 && devName.equals(cols[2])) {
                    snap.readIos = parseLongSafe(cols[3], 0L);
                    snap.readSectors = parseLongSafe(cols[5], 0L);
                    snap.readTicksMs = parseLongSafe(cols[6], 0L);
                    snap.writeIos = parseLongSafe(cols[7], 0L);
                    snap.writeSectors = parseLongSafe(cols[9], 0L);
                    snap.writeTicksMs = parseLongSafe(cols[10], 0L);
                    snap.ioTicksMs = parseLongSafe(cols[12], 0L);
                    break;
                }
            }
            return snap;
        }
    }

    public static final class DiskDelta {
        public double writeKbPerMin;
        public double readKbPerMin;
        public double writeOpsPerMin;
        public double ioBusyMsPerMin;
        public long writeSectorsDelta;

        public static DiskDelta compute(DiskSnapshot t0, DiskSnapshot t1, double elapsedSec) {
            DiskDelta d = new DiskDelta();
            if (t0 == null || t1 == null || elapsedSec <= 0.0) {
                return d;
            }
            long rSec = Math.max(0L, t1.readSectors - t0.readSectors);
            long wSec = Math.max(0L, t1.writeSectors - t0.writeSectors);
            long wOps = Math.max(0L, t1.writeIos - t0.writeIos);
            long ioMs = Math.max(0L, t1.ioTicksMs - t0.ioTicksMs);

            d.writeSectorsDelta = wSec;
            // 1 sector = 512 bytes = 0.5 KB
            d.readKbPerMin = (rSec * 0.5 * 60.0) / elapsedSec;
            d.writeKbPerMin = (wSec * 0.5 * 60.0) / elapsedSec;
            d.writeOpsPerMin = (wOps * 60.0) / elapsedSec;
            d.ioBusyMsPerMin = (ioMs * 60.0) / elapsedSec;
            return d;
        }
    }

    // =========================================================================
    // 4. /proc/<pid>/schedstat & /proc/<pid>/stat (Runqueue Delay, KSM, Loggers)
    // =========================================================================

    public static final class ProcEntry {
        public int pid;
        public String name = "";
        public String bucket = "";
        public long execNs;
        public long runDelayNs;
        public long timeslices;
        public long utimeJiffies;
        public long stimeJiffies;
        public long majflt;
    }

    public static final class ProcSchedSnapshot {
        public int totalProcessCount;
        public int userProcessCount;
        public Map<Integer, ProcEntry> entries = new HashMap<Integer, ProcEntry>();

        public static ProcSchedSnapshot capture() {
            ProcSchedSnapshot snap = new ProcSchedSnapshot();
            File procDir = new File("/proc");
            String[] names = procDir.list();
            if (names == null) {
                return snap;
            }
            for (int i = 0; i < names.length; i++) {
                String pidStr = names[i];
                if (!isAllDigits(pidStr)) {
                    continue;
                }
                snap.totalProcessCount++;
                int pid = (int) parseLongSafe(pidStr, -1L);
                if (pid <= 0) {
                    continue;
                }
                String statRaw = readFirstLine("/proc/" + pidStr + "/stat");
                if (statRaw.isEmpty()) {
                    continue;
                }
                String cmdline = readCmdline("/proc/" + pidStr + "/cmdline");
                if (cmdline != null && !cmdline.isEmpty()) {
                    snap.userProcessCount++;
                }
                ProcEntry entry = parseProcStatLine(pid, cmdline, statRaw);
                if (entry == null) {
                    continue;
                }
                String b = classifyBucket(entry.name);
                entry.bucket = (b != null) ? b : "";
                if (!entry.bucket.isEmpty()) {
                    String schedRaw = readFirstLine("/proc/" + pidStr + "/schedstat");
                    parseSchedstatLine(schedRaw, entry);
                }
                snap.entries.put(Integer.valueOf(pid), entry);
            }
            return snap;
        }

        private static boolean isAllDigits(String s) {
            if (s == null || s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private static String readCmdline(String path) {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) {
                return "";
            }
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(f);
                byte[] buf = new byte[256];
                int n = fis.read(buf);
                if (n <= 0) {
                    return "";
                }
                int end = 0;
                while (end < n && buf[end] != 0) {
                    end++;
                }
                return new String(buf, 0, end, UTF8).trim();
            } catch (Throwable ignored) {
                return "";
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        public static ProcEntry parseProcStatLine(int pid, String cmdline, String statLine) {
            if (statLine == null || statLine.isEmpty()) {
                return null;
            }
            int lparen = statLine.indexOf('(');
            int rparen = statLine.lastIndexOf(')');
            if (lparen < 0 || rparen <= lparen || rparen + 2 >= statLine.length()) {
                return null;
            }
            String comm = statLine.substring(lparen + 1, rparen);
            String procName = (cmdline != null && !cmdline.isEmpty()) ? cmdline : comm;
            int slash = procName.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < procName.length() && !procName.contains(".")) {
                procName = procName.substring(slash + 1);
            }

            String after = statLine.substring(rparen + 2).trim();
            String[] cols = after.split("\\s+");
            if (cols.length < 13) {
                return null;
            }
            ProcEntry e = new ProcEntry();
            e.pid = pid;
            e.name = procName;
            e.majflt = parseLongSafe(cols[9], 0L);
            e.utimeJiffies = parseLongSafe(cols[11], 0L);
            e.stimeJiffies = parseLongSafe(cols[12], 0L);
            return e;
        }

        public static void parseSchedstatLine(String schedLine, ProcEntry target) {
            if (schedLine == null || schedLine.isEmpty() || target == null) {
                return;
            }
            String[] cols = schedLine.trim().split("\\s+");
            if (cols.length >= 3) {
                target.execNs = parseLongSafe(cols[0], 0L);
                target.runDelayNs = parseLongSafe(cols[1], 0L);
                target.timeslices = parseLongSafe(cols[2], 0L);
            }
        }

        public static String classifyBucket(String procName) {
            if (procName == null || procName.isEmpty()) {
                return null;
            }
            if (procName.contains("surfaceflinger")) return "surfaceflinger";
            if ("system_server".equals(procName)) return "system_server";
            if (procName.startsWith("com.android.launcher3")) return "launcher3";
            if (procName.startsWith("com.android.systemui")) return "systemui";
            if ("com.mnsoft.navi".equals(procName)) return "navi";
            if (procName.startsWith("com.mobis.splitscreen")) return "splitscreen";
            if ("com.android.settings".equals(procName)) return "settings";
            if ("ksmd".equals(procName)) return "ksmd";
            if (procName.endsWith("micomd")) return "micomd";
            if (procName.endsWith("tcc_logd") || procName.endsWith("klogd")
                    || procName.endsWith("log_summary") || procName.endsWith("logcat")) {
                return "loggers";
            }
            return null;
        }
    }

    public static final class ProcSchedDelta {
        public double uiRunDelayMs;
        public double uiRunDelayMsPerSec;
        public double surfaceFlingerDelayMs;
        public double systemServerDelayMs;
        public double launcherDelayMs;
        public double systemUiDelayMs;
        public double naviDelayMs;
        public double settingsDelayMs;
        public double ksmdCpuMsPerMin;
        public double loggerCpuMsPerMin;
        public long trackedMajfltDelta;
        public int processCountEnd;
        public String topCpuProcs = "";

        private static final class TopProcItem {
            final int pid;
            final String name;
            final long jiffies;

            TopProcItem(int pid, String name, long jiffies) {
                this.pid = pid;
                this.name = name;
                this.jiffies = jiffies;
            }
        }

        private static String trimProcName(String name) {
            if (name == null) return "";
            String t = name.trim();
            if (t.length() > 38) {
                return ".." + t.substring(t.length() - 38);
            }
            return t;
        }

        public static ProcSchedDelta compute(ProcSchedSnapshot t0, ProcSchedSnapshot t1, double elapsedSec) {
            return compute(t0, t1, elapsedSec, 4);
        }

        public static ProcSchedDelta compute(ProcSchedSnapshot t0, ProcSchedSnapshot t1, double elapsedSec, int cpuCount) {
            ProcSchedDelta d = new ProcSchedDelta();
            if (t0 == null || t1 == null || elapsedSec <= 0.0) {
                return d;
            }
            int cpus = Math.max(1, cpuCount);
            d.processCountEnd = (t1.userProcessCount > 0) ? t1.userProcessCount : t1.totalProcessCount;
            long ksmdJiffies = 0L;
            long loggerJiffies = 0L;
            List<TopProcItem> cpuItems = new ArrayList<TopProcItem>();

            for (Map.Entry<Integer, ProcEntry> it : t1.entries.entrySet()) {
                ProcEntry e1 = it.getValue();
                ProcEntry e0 = t0.entries.get(it.getKey());
                if (e0 == null || !e1.name.equals(e0.name)) {
                    continue;
                }
                long cpuJiffies = Math.max(0L, (e1.utimeJiffies + e1.stimeJiffies) - (e0.utimeJiffies + e0.stimeJiffies));
                if (cpuJiffies > 0L) {
                    cpuItems.add(new TopProcItem(e1.pid, e1.name, cpuJiffies));
                }
                if (e1.bucket.isEmpty() || !e1.bucket.equals(e0.bucket)) {
                    continue;
                }
                double delayMs = (e1.runDelayNs >= e0.runDelayNs)
                        ? (e1.runDelayNs - e0.runDelayNs) / 1000000.0
                        : 0.0;
                long majflt = Math.max(0L, e1.majflt - e0.majflt);
                d.trackedMajfltDelta += majflt;

                if ("surfaceflinger".equals(e1.bucket)) {
                    d.surfaceFlingerDelayMs += delayMs;
                } else if ("system_server".equals(e1.bucket)) {
                    d.systemServerDelayMs += delayMs;
                } else if ("launcher3".equals(e1.bucket)) {
                    d.launcherDelayMs += delayMs;
                } else if ("systemui".equals(e1.bucket)) {
                    d.systemUiDelayMs += delayMs;
                } else if ("navi".equals(e1.bucket)) {
                    d.naviDelayMs += delayMs;
                } else if ("settings".equals(e1.bucket)) {
                    d.settingsDelayMs += delayMs;
                } else if ("ksmd".equals(e1.bucket)) {
                    ksmdJiffies += cpuJiffies;
                } else if ("loggers".equals(e1.bucket)) {
                    loggerJiffies += cpuJiffies;
                }
            }
            if (!cpuItems.isEmpty()) {
                Collections.sort(cpuItems, new Comparator<TopProcItem>() {
                    @Override
                    public int compare(TopProcItem a, TopProcItem b) {
                        if (b.jiffies > a.jiffies) return 1;
                        if (b.jiffies < a.jiffies) return -1;
                        return a.pid - b.pid;
                    }
                });
                StringBuilder topSb = new StringBuilder();
                int limit = Math.min(10, cpuItems.size());
                double denom = elapsedSec * cpus;
                for (int i = 0; i < limit; i++) {
                    TopProcItem item = cpuItems.get(i);
                    double pctTotal = (100.0 * item.jiffies * 0.01) / denom;
                    if (i > 0) topSb.append(", ");
                    topSb.append(String.format(Locale.US, "%s(%d)=%.1f%%",
                            trimProcName(item.name), Integer.valueOf(item.pid), Double.valueOf(pctTotal)));
                }
                d.topCpuProcs = topSb.toString();
            }
            d.uiRunDelayMs = d.surfaceFlingerDelayMs + d.systemServerDelayMs
                    + d.launcherDelayMs + d.systemUiDelayMs + d.naviDelayMs;
            d.uiRunDelayMsPerSec = d.uiRunDelayMs / elapsedSec;
            d.ksmdCpuMsPerMin = (ksmdJiffies * 10.0 * 60.0) / elapsedSec;
            d.loggerCpuMsPerMin = (loggerJiffies * 10.0 * 60.0) / elapsedSec;
            return d;
        }
    }

    // =========================================================================
    // 5. /proc/meminfo, /proc/vmstat, Tunables, Thermal
    // =========================================================================

    public static final class MemAndTunableSnapshot {
        public long memTotalKb;
        public long memFreeKb;
        public long buffersKb;
        public long cachedKb;
        public long availApproxKb;
        public long pgmajfault;
        public long allocstall;
        public int ksmRun = -1;
        public int ksmSleepMs = -1;
        public long ksmFullScans = -1;
        public int readAheadKb = -1;
        public int swappiness = -1;
        public int dirtyRatio = -1;
        public int dirtyBgRatio = -1;
        public String lmkMinfree = "";
        public double thermalMaxC = -1.0;
        public String cpu0Governor = "";
        public long cpu0CurKhz = -1L;
        public boolean eventlogRunning;
        public boolean tccLogdRunning;
        public boolean kloggerRunning;

        public static MemAndTunableSnapshot capture() {
            MemAndTunableSnapshot s = new MemAndTunableSnapshot();
            parseMeminfo(readFileUtf8("/proc/meminfo"), s);
            parseVmstat(readFileUtf8("/proc/vmstat"), s);
            s.ksmRun = (int) parseLongSafe(readFirstLine("/sys/kernel/mm/ksm/run"), -1L);
            s.ksmSleepMs = (int) parseLongSafe(readFirstLine("/sys/kernel/mm/ksm/sleep_millisecs"), -1L);
            s.ksmFullScans = parseLongSafe(readFirstLine("/sys/kernel/mm/ksm/full_scans"), -1L);
            String qDir = findBlockQueueDir();
            s.readAheadKb = qDir.isEmpty() ? -1 : (int) parseLongSafe(readFirstLine(qDir + "/read_ahead_kb"), -1L);
            s.swappiness = (int) parseLongSafe(readFirstLine("/proc/sys/vm/swappiness"), -1L);
            s.dirtyRatio = (int) parseLongSafe(readFirstLine("/proc/sys/vm/dirty_ratio"), -1L);
            s.dirtyBgRatio = (int) parseLongSafe(readFirstLine("/proc/sys/vm/dirty_background_ratio"), -1L);
            s.lmkMinfree = readFirstLine("/sys/module/lowmemorykiller/parameters/minfree");
            s.cpu0Governor = readFirstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor");
            s.cpu0CurKhz = parseLongSafe(readFirstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"), -1L);
            s.thermalMaxC = readThermalMaxCelsius();
            s.eventlogRunning = "running".equals(getSystemProperty("init.svc.eventlogdump", ""));
            s.tccLogdRunning = "running".equals(getSystemProperty("init.svc.tcc_logd", ""));
            s.kloggerRunning = "running".equals(getSystemProperty("init.svc.klogger", ""));
            return s;
        }

        private static void parseMeminfo(String raw, MemAndTunableSnapshot s) {
            if (raw == null || raw.isEmpty()) {
                return;
            }
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String k = line.substring(0, colon).trim();
                String rest = line.substring(colon + 1).trim();
                int sp = rest.indexOf(' ');
                long kb = parseLongSafe(sp > 0 ? rest.substring(0, sp) : rest, 0L);
                if ("MemTotal".equals(k)) s.memTotalKb = kb;
                else if ("MemFree".equals(k)) s.memFreeKb = kb;
                else if ("Buffers".equals(k)) s.buffersKb = kb;
                else if ("Cached".equals(k)) s.cachedKb = kb;
            }
            s.availApproxKb = s.memFreeKb + s.buffersKb + s.cachedKb;
        }

        private static void parseVmstat(String raw, MemAndTunableSnapshot s) {
            if (raw == null || raw.isEmpty()) {
                return;
            }
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String[] cols = lines[i].trim().split("\\s+");
                if (cols.length < 2) {
                    continue;
                }
                if ("pgmajfault".equals(cols[0])) {
                    s.pgmajfault = parseLongSafe(cols[1], 0L);
                } else if ("allocstall".equals(cols[0])) {
                    s.allocstall = parseLongSafe(cols[1], 0L);
                }
            }
        }

        private static double readThermalMaxCelsius() {
            double maxC = -1.0;
            File dir = new File("/sys/class/thermal");
            String[] zones = dir.list();
            if (zones == null) {
                return maxC;
            }
            for (int i = 0; i < zones.length; i++) {
                if (!zones[i].startsWith("thermal_zone")) {
                    continue;
                }
                String raw = readFirstLine("/sys/class/thermal/" + zones[i] + "/temp");
                long v = parseLongSafe(raw, -999999L);
                if (v <= -999999L) {
                    continue;
                }
                double c = (Math.abs(v) >= 1000L) ? (v / 1000.0) : (double) v;
                if (c > -40.0 && c < 150.0 && c > maxC) {
                    maxC = c;
                }
            }
            return maxC;
        }
    }
}
