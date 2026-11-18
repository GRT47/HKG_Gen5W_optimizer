package com.hkg.optimize;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java 7 data models, JSON/config persistence, and zero-fork /proc & /sys helpers
 * for HKG 5W Optimize (Android 4.4.2 / API 19 / DEX 035 compatible).
 */
public final class OptModel {

    public static final String TMP_DIR = "/dev/hkg_tmp";
    public static final String BASELINE_FILE = TMP_DIR + "/hkg_optimize_baseline.json";
    public static final String ACTIVE_CONF_FILE = TMP_DIR + "/hkg_optimize_active.conf";
    public static final String PID_FILE = TMP_DIR + "/hkg_optimize_java.pid";
    public static final String CMD_FILE = TMP_DIR + "/hkg_optimize_cmd";
    public static final String DEFAULT_INSTALL_DIR = "/data/nemesis/apps/hkg.optimize";

    public static final String KEY_LOGGERS = "loggers";
    public static final String KEY_KSM = "ksm";
    public static final String KEY_VM_MMC = "vm_mmc";
    public static final String KEY_SCHED = "sched";
    public static final String KEY_IRQ = "irq";
    public static final String KEY_ANIM = "anim";

    public static final String[] ALL_TWEAK_KEYS = new String[] {
        KEY_LOGGERS, KEY_KSM, KEY_VM_MMC, KEY_SCHED, KEY_IRQ, KEY_ANIM
    };

    private OptModel() {
    }

    public static final class TweakDef {
        public final String key;
        public final String title;
        public final String description;

        public TweakDef(String key, String title, String description) {
            this.key = key;
            this.title = title;
            this.description = description;
        }
    }

    public static List<TweakDef> getTweakDefs() {
        List<TweakDef> defs = new ArrayList<TweakDef>();
        defs.add(new TweakDef(
                KEY_LOGGERS,
                "백그라운드 디스크 로거 중지",
                "eventlogdump · tcc_logd · klogger · log_summary · mtlc_logging 중지"));
        defs.add(new TweakDef(
                KEY_KSM,
                "KSM 메모리 스캔 중지",
                "ksm/run=0, sleep_millisecs=1000 (불필요한 페이지 스캔 CPU 제거)"));
        defs.add(new TweakDef(
                KEY_VM_MMC,
                "VM 캐시압력 & eMMC 큐 최적화",
                "vfs_cache_pressure 10000->60 · swappiness 10 · RA 512KB · rq_affinity"));
        defs.add(new TweakDef(
                KEY_SCHED,
                "네비·UI 멀티스레드 & 데몬 우선순위",
                "네비(com.mnsoft.navi)·SF·UI 스레드 -6~-8 / 외부 데몬 양보 +4 / VRM·DXB +6~+10"));
        defs.add(new TweakDef(
                KEY_IRQ,
                "인터럽트 분산 (CPU0 병목 해소)",
                "스토리지·통신 등 장치 IRQ를 CPU1~3으로 분산 (타이머/WDT/UART 보호)"));
        defs.add(new TweakDef(
                KEY_ANIM,
                "화면 전환 애니메이션 가속",
                "창·전환·애니메이터 배율 프리셋 즉시 적용 (부팅 복원 가드 포함)"));
        return defs;
    }

    public static final class OptConfig {
        public int x = 318;
        public int y = 18;
        public boolean autoBoot = true;
        public String animPreset = "0.25";
        public final Map<String, Boolean> enabled = new LinkedHashMap<String, Boolean>();

        public OptConfig() {
            for (int i = 0; i < ALL_TWEAK_KEYS.length; i++) {
                enabled.put(ALL_TWEAK_KEYS[i], Boolean.TRUE);
            }
        }

        public boolean isEnabled(String key) {
            Boolean v = enabled.get(key);
            return v != null && v.booleanValue();
        }

        public void setEnabled(String key, boolean val) {
            enabled.put(key, Boolean.valueOf(val));
        }

        public static OptConfig parse(String raw) {
            OptConfig cfg = new OptConfig();
            if (raw == null) {
                return cfg;
            }
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("x".equals(k)) {
                    cfg.x = parseIntSafe(v, cfg.x);
                } else if ("y".equals(k)) {
                    cfg.y = parseIntSafe(v, cfg.y);
                } else if ("auto_boot".equals(k)) {
                    cfg.autoBoot = "1".equals(v) || "true".equalsIgnoreCase(v);
                } else if ("anim_preset".equals(k)) {
                    cfg.animPreset = normalizeAnimPreset(v);
                } else if (k.startsWith("tweak_")) {
                    String tk = k.substring("tweak_".length());
                    if (cfg.enabled.containsKey(tk)) {
                        cfg.enabled.put(tk, Boolean.valueOf("1".equals(v) || "true".equalsIgnoreCase(v)));
                    }
                }
            }
            return cfg;
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append("# HKG 5W Optimize configuration\n");
            sb.append("x=").append(x).append('\n');
            sb.append("y=").append(y).append('\n');
            sb.append("auto_boot=").append(autoBoot ? "1" : "0").append('\n');
            sb.append("anim_preset=").append(normalizeAnimPreset(animPreset)).append('\n');
            for (int i = 0; i < ALL_TWEAK_KEYS.length; i++) {
                String key = ALL_TWEAK_KEYS[i];
                sb.append("tweak_").append(key).append('=')
                        .append(isEnabled(key) ? "1" : "0").append('\n');
            }
            return sb.toString();
        }
    }

    public static final class BaselineState {
        public String bootId = "";
        public String ksmRun = "";
        public String ksmSleepMs = "";
        public String vfsCachePressure = "";
        public String swappiness = "";
        public String dirtyBgRatio = "";
        public String dirtyRatio = "";
        public String minFreeKbytes = "";
        public String blockDev = "";
        public String readAheadKb = "";
        public String nrRequests = "";
        public String rqAffinity = "";
        public float animWin = 1.0f;
        public float animTrans = 1.0f;
        public float animDur = 1.0f;
        public final Set<String> activeTweaks = new LinkedHashSet<String>();
        public final Map<String, String> irqMasks = new LinkedHashMap<String, String>();

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            appendJsonKv(sb, "bootId", bootId, true);
            appendJsonKv(sb, "ksmRun", ksmRun, true);
            appendJsonKv(sb, "ksmSleepMs", ksmSleepMs, true);
            appendJsonKv(sb, "vfsCachePressure", vfsCachePressure, true);
            appendJsonKv(sb, "swappiness", swappiness, true);
            appendJsonKv(sb, "dirtyBgRatio", dirtyBgRatio, true);
            appendJsonKv(sb, "dirtyRatio", dirtyRatio, true);
            appendJsonKv(sb, "minFreeKbytes", minFreeKbytes, true);
            appendJsonKv(sb, "blockDev", blockDev, true);
            appendJsonKv(sb, "readAheadKb", readAheadKb, true);
            appendJsonKv(sb, "nrRequests", nrRequests, true);
            appendJsonKv(sb, "rqAffinity", rqAffinity, true);
            appendJsonFloatKv(sb, "animWin", animWin, true);
            appendJsonFloatKv(sb, "animTrans", animTrans, true);
            appendJsonFloatKv(sb, "animDur", animDur, true);
            appendJsonKv(sb, "activeTweaksCsv", joinSetCsv(activeTweaks), true);
            sb.append("  \"irqMasks\": {\n");
            int idx = 0;
            int total = irqMasks.size();
            for (Map.Entry<String, String> entry : irqMasks.entrySet()) {
                idx++;
                sb.append("    \"").append(escapeJson(entry.getKey())).append("\": \"")
                        .append(escapeJson(entry.getValue())).append("\"");
                if (idx < total) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("  }\n");
            sb.append("}\n");
            return sb.toString();
        }

        public static BaselineState fromJson(String json) {
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            BaselineState b = new BaselineState();
            b.bootId = extractJsonString(json, "bootId", "");
            b.ksmRun = extractJsonString(json, "ksmRun", "");
            b.ksmSleepMs = extractJsonString(json, "ksmSleepMs", "");
            b.vfsCachePressure = extractJsonString(json, "vfsCachePressure", "");
            b.swappiness = extractJsonString(json, "swappiness", "");
            b.dirtyBgRatio = extractJsonString(json, "dirtyBgRatio", "");
            b.dirtyRatio = extractJsonString(json, "dirtyRatio", "");
            b.minFreeKbytes = extractJsonString(json, "minFreeKbytes", "");
            b.blockDev = extractJsonString(json, "blockDev", "");
            b.readAheadKb = extractJsonString(json, "readAheadKb", "");
            b.nrRequests = extractJsonString(json, "nrRequests", "");
            b.rqAffinity = extractJsonString(json, "rqAffinity", "");
            b.animWin = parseFloatSafe(extractJsonNumber(json, "animWin", "1.0"), 1.0f);
            b.animTrans = parseFloatSafe(extractJsonNumber(json, "animTrans", "1.0"), 1.0f);
            b.animDur = parseFloatSafe(extractJsonNumber(json, "animDur", "1.0"), 1.0f);
            String csv = extractJsonString(json, "activeTweaksCsv", "");
            if (!csv.isEmpty()) {
                String[] parts = csv.split(",");
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (!p.isEmpty()) {
                        b.activeTweaks.add(p);
                    }
                }
            }
            parseJsonStringMap(json, "irqMasks", b.irqMasks);
            return b;
        }
    }

    public static String normalizeAnimPreset(String raw) {
        if (raw == null) {
            return "0.25";
        }
        String t = raw.trim().toLowerCase(Locale.US);
        if (t.endsWith("x")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        if ("1.0".equals(t) || "1".equals(t) || "1.00".equals(t)) {
            return "1.0";
        }
        if ("0.5".equals(t) || "0.50".equals(t)) {
            return "0.5";
        }
        if ("0.0".equals(t) || "0".equals(t) || "0.00".equals(t) || "off".equals(t)) {
            return "0.0";
        }
        return "0.25";
    }

    public static float[] getAnimScalesForPreset(String preset) {
        String p = normalizeAnimPreset(preset);
        if ("1.0".equals(p)) {
            return new float[] { 1.0f, 1.0f, 1.0f };
        }
        if ("0.5".equals(p)) {
            return new float[] { 0.5f, 0.5f, 0.5f };
        }
        if ("0.0".equals(p)) {
            return new float[] { 0.0f, 0.0f, 0.0f };
        }
        return new float[] { 0.25f, 0.25f, 0.50f };
    }

    public static String formatActiveConf(String bootId, Set<String> activeTweaks, String animPreset, String uptimeStr) {
        String csv = joinSetCsv(activeTweaks);
        boolean active = !activeTweaks.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("boot_id=").append(bootId == null ? "" : bootId).append('\n');
        sb.append("active=").append(active ? "1" : "0").append('\n');
        sb.append("tweaks=").append(csv).append('\n');
        sb.append("anim_preset=").append(normalizeAnimPreset(animPreset)).append('\n');
        sb.append("updated_uptime=").append(uptimeStr == null ? "" : uptimeStr).append('\n');
        return sb.toString();
    }

    public static String formatAnimGuardConf(String bootId, float win, float trans, float dur) {
        StringBuilder sb = new StringBuilder();
        sb.append("boot_id=").append(bootId == null ? "" : bootId).append('\n');
        sb.append("anim_win=").append(String.format(Locale.US, "%.2f", Float.valueOf(win))).append('\n');
        sb.append("anim_trans=").append(String.format(Locale.US, "%.2f", Float.valueOf(trans))).append('\n');
        sb.append("anim_dur=").append(String.format(Locale.US, "%.2f", Float.valueOf(dur))).append('\n');
        return sb.toString();
    }

    public static String joinSetCsv(Set<String> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ALL_TWEAK_KEYS.length; i++) {
            String k = ALL_TWEAK_KEYS[i];
            if (set.contains(k)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(k);
            }
        }
        for (String extra : set) {
            boolean known = false;
            for (int i = 0; i < ALL_TWEAK_KEYS.length; i++) {
                if (ALL_TWEAK_KEYS[i].equals(extra)) {
                    known = true;
                    break;
                }
            }
            if (!known && extra != null && !extra.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(extra);
            }
        }
        return sb.toString();
    }

    public static String readFileUtf8(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            return null;
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static String readTrimmed(String path) {
        String s = readFileUtf8(path);
        return s == null ? "" : s.trim();
    }

    public static boolean writeFileUtf8(String path, String content) {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            fos.write((content == null ? "" : content).getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static boolean writeProcSys(String path, String value) {
        File f = new File(path);
        if (!f.exists()) {
            return false;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            fos.write((value + "\n").getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static String readBootId() {
        String id = readTrimmed("/proc/sys/kernel/random/boot_id");
        if (!id.isEmpty()) {
            return id;
        }
        return "";
    }

    public static String readUptimeToken() {
        String up = readTrimmed("/proc/uptime");
        if (up.isEmpty()) {
            return "";
        }
        int sp = up.indexOf(' ');
        return sp > 0 ? up.substring(0, sp) : up;
    }

    public static void ensureTmpDir() {
        File dir = new File(TMP_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
    }

    public static String resolveInstallDir(String explicitDir) {
        if (explicitDir != null && !explicitDir.trim().isEmpty()) {
            File f = new File(explicitDir.trim());
            if (f.isDirectory()) {
                return f.getAbsolutePath();
            }
        }
        File apps = new File(DEFAULT_INSTALL_DIR);
        if (apps.isDirectory()) {
            return apps.getAbsolutePath();
        }
        File pkgs = new File("/data/nemesis/packages/hkg.optimize");
        if (pkgs.isDirectory()) {
            return pkgs.getAbsolutePath();
        }
        return DEFAULT_INSTALL_DIR;
    }

    public static OptConfig loadConfig(String installDir) {
        String confPath = installDir + "/config/optimize.conf";
        String raw = readFileUtf8(confPath);
        return OptConfig.parse(raw);
    }

    public static boolean saveConfig(String installDir, OptConfig cfg) {
        if (cfg == null) {
            return false;
        }
        String confPath = installDir + "/config/optimize.conf";
        return writeFileUtf8(confPath, cfg.serialize());
    }

    public static BaselineState loadBaseline() {
        String raw = readFileUtf8(BASELINE_FILE);
        BaselineState st = BaselineState.fromJson(raw);
        if (st == null) {
            return null;
        }
        String curBoot = readBootId();
        if (!curBoot.isEmpty() && !st.bootId.isEmpty() && !curBoot.equals(st.bootId)) {
            return null;
        }
        return st;
    }

    public static boolean saveBaseline(BaselineState st) {
        if (st == null) {
            return false;
        }
        ensureTmpDir();
        return writeFileUtf8(BASELINE_FILE, st.toJson());
    }

    public static void syncActiveConf(BaselineState st, OptConfig cfg) {
        ensureTmpDir();
        String bootId = readBootId();
        Set<String> active = st != null ? st.activeTweaks : Collections.<String>emptySet();
        String preset = cfg != null ? cfg.animPreset : "0.25";
        String content = formatActiveConf(bootId, active, preset, readUptimeToken());
        writeFileUtf8(ACTIVE_CONF_FILE, content);
        File af = new File(ACTIVE_CONF_FILE);
        af.setReadable(true, false);
    }

    public static int parseIntSafe(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static float parseFloatSafe(String s, float def) {
        if (s == null) {
            return def;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static void appendJsonKv(StringBuilder sb, String k, String v, boolean comma) {
        sb.append("  \"").append(escapeJson(k)).append("\": \"")
                .append(escapeJson(v == null ? "" : v)).append("\"");
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendJsonFloatKv(StringBuilder sb, String k, float v, boolean comma) {
        sb.append("  \"").append(escapeJson(k)).append("\": ")
                .append(String.format(Locale.US, "%.2f", Float.valueOf(v)));
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '"') {
                out.append("\\\"");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String extractJsonString(String json, String key, String def) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return def;
        }
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) {
            return def;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return def;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 'r') {
                    sb.append('\r');
                } else if (next == 't') {
                    sb.append('\t');
                } else {
                    sb.append(next);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return def;
    }

    private static String extractJsonNumber(String json, String key, String def) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return def;
        }
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) {
            return def;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < json.length()) {
            char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        if (i <= start) {
            return def;
        }
        return json.substring(start, i);
    }

    private static void parseJsonStringMap(String json, String key, Map<String, String> out) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return;
        }
        int open = json.indexOf('{', idx + pattern.length());
        if (open < 0) {
            return;
        }
        int close = json.indexOf('}', open + 1);
        if (close < 0) {
            return;
        }
        String block = json.substring(open + 1, close);
        String[] lines = block.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("\"")) {
                continue;
            }
            int kEnd = line.indexOf('"', 1);
            if (kEnd <= 1) {
                continue;
            }
            String mk = line.substring(1, kEnd);
            int colon = line.indexOf(':', kEnd + 1);
            if (colon < 0) {
                continue;
            }
            int vStart = line.indexOf('"', colon + 1);
            if (vStart < 0) {
                continue;
            }
            int vEnd = line.indexOf('"', vStart + 1);
            if (vEnd < 0) {
                continue;
            }
            String mv = line.substring(vStart + 1, vEnd);
            out.put(mk, mv);
        }
    }

    public static List<String> readLines(String path) {
        List<String> lines = new ArrayList<String>();
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            return lines;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return lines;
    }
}
