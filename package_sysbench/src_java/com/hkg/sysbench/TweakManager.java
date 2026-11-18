package com.hkg.sysbench;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime-only Gen5W optimization tweak controller (v1.2.0).
 *
 * Lifecycle policy (Session-only):
 * - Checking a tweak applies it immediately without rebooting.
 * - Closing the overlay keeps checked tweaks active for the current boot/ignition session.
 * - Unchecking reverts the exact original value captured before modification.
 * - Rebooting (/proc/sys/kernel/random/boot_id mismatch) always resets 100% to stock.
 */
public final class TweakManager {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String SESSION_FILE_PATH = "/dev/hkg_tmp/hkg_sysbench_session.json";
    public static final String EXT_OPT_ACTIVE_PATH = "/dev/hkg_tmp/hkg_optimize_active.conf";
    public static final String GUARD_FILE_NAME = "boot_restore_guard.conf";

    public static String readExternalOptActiveCsv() {
        File f = new File(EXT_OPT_ACTIVE_PATH);
        if (!f.isFile() || !f.canRead()) {
            return "";
        }
        String bootId = "";
        String active = "";
        String tweaks = "";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("boot_id".equals(k)) {
                    bootId = v;
                } else if ("active".equals(k)) {
                    active = v;
                } else if ("tweaks".equals(k)) {
                    tweaks = v;
                }
            }
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (!"1".equals(active)) {
            return "";
        }
        String curBoot = readBootId();
        if (!curBoot.isEmpty() && !bootId.isEmpty() && !curBoot.equals(bootId)) {
            return "";
        }
        return tweaks.isEmpty() ? "active" : tweaks;
    }

    public static final class TweakDef {
        public final String id;
        public final String title;
        public final String desc;

        public TweakDef(String id, String title, String desc) {
            this.id = id;
            this.title = title;
            this.desc = desc;
        }
    }

    public static final TweakDef[] TWEAK_DEFS = new TweakDef[]{
            new TweakDef("loggers", "상시 디스크 로거 중단", "eventlog·tcc_logd·klogger 저널 쓰기 차단"),
            new TweakDef("ksm", "KSM 메모리 스캔 억제", "ksmd 80ms 주기 백그라운드 CPU 경합 제거"),
            new TweakDef("vm_io", "스토리지·VM 버퍼 최적화", "read_ahead 512KB · swappiness/dirty 튜닝"),
            new TweakDef("sched", "UI 우선순위 & 백그라운드 격리", "UI·내비 부스트 + 비핵심 앱 cpuctl 제한"),
            new TweakDef("irq", "주변장치 IRQ 멀티코어 분산", "터치·GPU·eMMC 인터럽트 CPU1~3 분산"),
            new TweakDef("anim", "화면 전환 애니메이션 가속", "창/전환 0.25x · 애니메이터 0.5x 즉시 반영")
    };

    public static final class SessionState {
        public String bootId = "";
        public final Map<String, Boolean> enabled = new LinkedHashMap<String, Boolean>();
        public final Map<String, String> statusNote = new LinkedHashMap<String, String>();
        public final Map<String, String> origSysfs = new LinkedHashMap<String, String>();
        public final Map<String, String> origLoggers = new LinkedHashMap<String, String>();
        public final Map<String, String> origIrq = new LinkedHashMap<String, String>();
        public final Map<String, String> origProc = new LinkedHashMap<String, String>();
        public boolean hasOrigAnim = false;
        public float origAnimWin = 1.0f;
        public float origAnimTrans = 1.0f;
        public float origAnimDur = 1.0f;

        public SessionState(String bootId) {
            this.bootId = bootId != null ? bootId : "";
            for (int i = 0; i < TWEAK_DEFS.length; i++) {
                String id = TWEAK_DEFS[i].id;
                enabled.put(id, Boolean.FALSE);
                statusNote.put(id, "순정");
            }
        }

        public boolean isEnabled(String id) {
            Boolean v = enabled.get(id);
            return v != null && v.booleanValue();
        }

        public String getNote(String id) {
            String n = statusNote.get(id);
            return (n != null && !n.isEmpty()) ? n : (isEnabled(id) ? "적용됨" : "순정");
        }

        public String toJson() {
            Map<String, String> kv = new LinkedHashMap<String, String>();
            kv.put("bootId", bootId);
            kv.put("hasOrigAnim", hasOrigAnim ? "true" : "false");
            kv.put("origAnimWin", String.format(Locale.US, "%.4f", origAnimWin));
            kv.put("origAnimTrans", String.format(Locale.US, "%.4f", origAnimTrans));
            kv.put("origAnimDur", String.format(Locale.US, "%.4f", origAnimDur));

            for (int i = 0; i < TWEAK_DEFS.length; i++) {
                String id = TWEAK_DEFS[i].id;
                kv.put("en." + id, isEnabled(id) ? "true" : "false");
                kv.put("note." + id, getNote(id));
            }
            for (Map.Entry<String, String> e : origSysfs.entrySet()) {
                kv.put("sysfs." + e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String> e : origLoggers.entrySet()) {
                kv.put("svc." + e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String> e : origIrq.entrySet()) {
                kv.put("irq." + e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String> e : origProc.entrySet()) {
                kv.put("proc." + e.getKey(), e.getValue());
            }
            return BenchReport.buildFlatJson(kv);
        }

        public static SessionState fromJson(String json, String currentBootId) {
            SessionState fresh = new SessionState(currentBootId);
            if (json == null || json.trim().isEmpty()) {
                return fresh;
            }
            Map<String, String> kv = BenchReport.parseFlatJsonObject(json);
            if (kv.isEmpty()) {
                return fresh;
            }
            String savedBoot = kv.get("bootId");
            if (savedBoot == null || savedBoot.isEmpty() || !savedBoot.equals(currentBootId)) {
                return fresh;
            }
            fresh.hasOrigAnim = "true".equalsIgnoreCase(kv.get("hasOrigAnim"));
            fresh.origAnimWin = (float) KernelCollectors.parseDoubleSafe(kv.get("origAnimWin"), 1.0);
            fresh.origAnimTrans = (float) KernelCollectors.parseDoubleSafe(kv.get("origAnimTrans"), 1.0);
            fresh.origAnimDur = (float) KernelCollectors.parseDoubleSafe(kv.get("origAnimDur"), 1.0);

            for (Map.Entry<String, String> e : kv.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k.startsWith("en.")) {
                    String id = k.substring(3);
                    fresh.enabled.put(id, Boolean.valueOf("true".equalsIgnoreCase(v)));
                } else if (k.startsWith("note.")) {
                    String id = k.substring(5);
                    fresh.statusNote.put(id, v);
                } else if (k.startsWith("sysfs.")) {
                    fresh.origSysfs.put(k.substring(6), v);
                } else if (k.startsWith("svc.")) {
                    fresh.origLoggers.put(k.substring(4), v);
                } else if (k.startsWith("irq.")) {
                    fresh.origIrq.put(k.substring(4), v);
                } else if (k.startsWith("proc.")) {
                    fresh.origProc.put(k.substring(5), v);
                }
            }
            return fresh;
        }
    }

    private TweakManager() {
    }

    public static String readBootId() {
        String id = KernelCollectors.readFirstLine("/proc/sys/kernel/random/boot_id");
        return id != null ? id.trim() : "";
    }

    public static synchronized SessionState loadSession(File resultsDir) {
        String curBoot = readBootId();
        if (resultsDir != null) {
            File guardFile = new File(resultsDir, GUARD_FILE_NAME);
            if (guardFile.exists() && guardFile.canRead()) {
                String rawGuard = KernelCollectors.readFileUtf8(guardFile.getAbsolutePath());
                String guardBoot = "";
                float oWin = 1.0f;
                float oTrans = 1.0f;
                float oDur = 1.0f;
                String[] lines = rawGuard.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String ln = lines[i].trim();
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String gk = ln.substring(0, eq).trim();
                    String gv = ln.substring(eq + 1).trim();
                    if ("boot_id".equals(gk)) guardBoot = gv;
                    else if ("anim_win".equals(gk)) oWin = (float) KernelCollectors.parseDoubleSafe(gv, 1.0);
                    else if ("anim_trans".equals(gk)) oTrans = (float) KernelCollectors.parseDoubleSafe(gv, 1.0);
                    else if ("anim_dur".equals(gk)) oDur = (float) KernelCollectors.parseDoubleSafe(gv, 1.0);
                }
                if (!guardBoot.isEmpty() && !curBoot.isEmpty() && !guardBoot.equals(curBoot)) {
                    applyAnimationScales(oWin, oTrans, oDur);
                    guardFile.delete();
                    new File(SESSION_FILE_PATH).delete();
                    return new SessionState(curBoot);
                }
            }
        }

        File sf = new File(SESSION_FILE_PATH);
        if (sf.exists() && sf.canRead()) {
            String raw = KernelCollectors.readFileUtf8(SESSION_FILE_PATH);
            SessionState loaded = SessionState.fromJson(raw, curBoot);
            if (!loaded.bootId.isEmpty() && loaded.bootId.equals(curBoot)) {
                Map<String, String> checkKv = BenchReport.parseFlatJsonObject(raw);
                String savedBoot = checkKv.get("bootId");
                if (savedBoot != null && savedBoot.equals(curBoot)) {
                    return loaded;
                }
            }
            sf.delete();
        }
        return new SessionState(curBoot);
    }

    public static synchronized void saveSession(SessionState state) {
        if (state == null) return;
        BenchReport.writeUtf8(new File(SESSION_FILE_PATH), state.toJson());
    }

    public static int countActive(SessionState state) {
        if (state == null) return 0;
        int c = 0;
        for (int i = 0; i < TWEAK_DEFS.length; i++) {
            if (state.isEnabled(TWEAK_DEFS[i].id)) {
                c++;
            }
        }
        return c;
    }

    public static String getActiveSummaryCsv(SessionState state) {
        if (state == null) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TWEAK_DEFS.length; i++) {
            String id = TWEAK_DEFS[i].id;
            if (state.isEnabled(id)) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    public static synchronized String toggleTweak(File resultsDir, SessionState state, String id, boolean enable) {
        if (state == null || id == null) {
            return "세션 상태 없음";
        }
        if (state.bootId.isEmpty()) {
            state.bootId = readBootId();
        }
        String msg;
        if ("loggers".equals(id)) {
            msg = enable ? applyLoggers(state) : revertLoggers(state);
        } else if ("ksm".equals(id)) {
            msg = enable ? applyKsm(state) : revertKsm(state);
        } else if ("vm_io".equals(id)) {
            msg = enable ? applyVmIo(state) : revertVmIo(state);
        } else if ("sched".equals(id)) {
            msg = enable ? applySched(state) : revertSched(state);
        } else if ("irq".equals(id)) {
            msg = enable ? applyIrq(state) : revertIrq(state);
        } else if ("anim".equals(id)) {
            msg = enable ? applyAnim(resultsDir, state) : revertAnim(resultsDir, state);
        } else {
            return "알 수 없는 트윅: " + id;
        }
        saveSession(state);
        return msg;
    }

    public static synchronized String applyRecommendedAll(File resultsDir, SessionState state) {
        for (int i = 0; i < TWEAK_DEFS.length; i++) {
            toggleTweak(resultsDir, state, TWEAK_DEFS[i].id, true);
        }
        saveSession(state);
        return "추천 최적화 적용 완료 (" + countActive(state) + "/" + TWEAK_DEFS.length + " 활성, 재부팅 시 순정 복귀)";
    }

    public static synchronized String revertAllToStock(File resultsDir, SessionState state) {
        for (int i = TWEAK_DEFS.length - 1; i >= 0; i--) {
            String id = TWEAK_DEFS[i].id;
            if (state.isEnabled(id) || hasSavedOriginalsFor(state, id)) {
                toggleTweak(resultsDir, state, id, false);
            } else {
                state.enabled.put(id, Boolean.FALSE);
                state.statusNote.put(id, "순정");
            }
        }
        if (resultsDir != null) {
            new File(resultsDir, GUARD_FILE_NAME).delete();
        }
        saveSession(state);
        return "모든 트윅을 순정 상태로 원복했습니다.";
    }

    private static boolean hasSavedOriginalsFor(SessionState st, String id) {
        if ("anim".equals(id)) return st.hasOrigAnim;
        if ("loggers".equals(id)) return !st.origLoggers.isEmpty();
        if ("irq".equals(id)) return !st.origIrq.isEmpty();
        if ("sched".equals(id)) return !st.origProc.isEmpty();
        return !st.origSysfs.isEmpty();
    }

    // -------------------------------------------------------------------------
    // 1. Disk Loggers (stop services without persistent setprop)
    // -------------------------------------------------------------------------

    private static final String[] LOGGER_SERVICES = new String[]{
            "eventlogdump", "tcc_logd", "klogger", "log_summary", "logcatdump"
    };
    private static final String MTLC_LOG_PATH = "/sys/kernel/debug/mmc0/mmc0:0001/mtlc_logging";

    private static String applyLoggers(SessionState st) {
        int stoppedCnt = 0;
        for (int i = 0; i < LOGGER_SERVICES.length; i++) {
            String svc = LOGGER_SERVICES[i];
            if (!st.origLoggers.containsKey(svc)) {
                String status = KernelCollectors.getSystemProperty("init.svc." + svc, "");
                st.origLoggers.put(svc, status != null ? status.trim() : "");
            }
            ScenarioRunner.runShellCmd(new String[]{"/system/bin/stop", svc}, 2000L);
            String after = KernelCollectors.getSystemProperty("init.svc." + svc, "");
            if ("stopped".equals(after) || "running".equals(st.origLoggers.get(svc))) {
                stoppedCnt++;
            }
        }
        if (new File(MTLC_LOG_PATH).exists()) {
            saveOrigSysfsOnce(st, MTLC_LOG_PATH);
            writeSysfs(MTLC_LOG_PATH, "0");
        }
        st.enabled.put("loggers", Boolean.TRUE);
        String note = stoppedCnt > 0 ? "로거 중단됨 (" + stoppedCnt + ")" : "로거 중단 요청됨";
        st.statusNote.put("loggers", note);
        return "상시 디스크 로거 중단 적용 (" + note + ")";
    }

    private static String revertLoggers(SessionState st) {
        int startedCnt = 0;
        for (int i = 0; i < LOGGER_SERVICES.length; i++) {
            String svc = LOGGER_SERVICES[i];
            String orig = st.origLoggers.get(svc);
            if ("running".equals(orig)) {
                ScenarioRunner.runShellCmd(new String[]{"/system/bin/start", svc}, 2000L);
                startedCnt++;
            }
        }
        restoreOrigSysfsIfPresent(st, MTLC_LOG_PATH);
        st.enabled.put("loggers", Boolean.FALSE);
        st.statusNote.put("loggers", "순정 복구됨");
        return "디스크 로거 순정 복구 완료 (재시작 " + startedCnt + "개)";
    }

    // -------------------------------------------------------------------------
    // 2. KSM Suppression (/sys/kernel/mm/ksm)
    // -------------------------------------------------------------------------

    private static final String KSM_RUN_PATH = "/sys/kernel/mm/ksm/run";
    private static final String KSM_SLEEP_PATH = "/sys/kernel/mm/ksm/sleep_millisecs";

    private static String applyKsm(SessionState st) {
        if (!new File(KSM_RUN_PATH).exists()) {
            st.enabled.put("ksm", Boolean.FALSE);
            st.statusNote.put("ksm", "미지원 커널");
            return "이 커널은 KSM을 사용하지 않습니다.";
        }
        saveOrigSysfsOnce(st, KSM_RUN_PATH);
        saveOrigSysfsOnce(st, KSM_SLEEP_PATH);
        boolean okRun = writeSysfs(KSM_RUN_PATH, "0");
        boolean okSleep = !new File(KSM_SLEEP_PATH).exists() || writeSysfs(KSM_SLEEP_PATH, "1000");
        if (!okRun) {
            st.enabled.put("ksm", Boolean.FALSE);
            st.statusNote.put("ksm", "쓰기 거부됨");
            return "KSM 쓰기 권한 없음";
        }
        st.enabled.put("ksm", Boolean.TRUE);
        st.statusNote.put("ksm", okSleep ? "ksmd 정지 (1000ms)" : "ksmd 정지됨");
        return "KSM 메모리 스캔 억제 적용 완료 (run=0, sleep=1000ms)";
    }

    private static String revertKsm(SessionState st) {
        restoreOrigSysfsIfPresent(st, KSM_RUN_PATH);
        restoreOrigSysfsIfPresent(st, KSM_SLEEP_PATH);
        st.enabled.put("ksm", Boolean.FALSE);
        st.statusNote.put("ksm", "순정 복구됨");
        return "KSM 설정 순정 복구 완료";
    }

    // -------------------------------------------------------------------------
    // 3. Storage Read-Ahead & VM Writeback Tuning
    // -------------------------------------------------------------------------

    private static final String[][] VM_TARGETS = new String[][]{
            {"/proc/sys/vm/swappiness", "10"},
            {"/proc/sys/vm/dirty_background_ratio", "10"},
            {"/proc/sys/vm/dirty_ratio", "30"},
            {"/proc/sys/vm/vfs_cache_pressure", "70"}
    };

    private static List<String[]> resolveVmIoTargets() {
        List<String[]> list = new ArrayList<String[]>();
        String qDir = KernelCollectors.findBlockQueueDir();
        if (!qDir.isEmpty()) {
            list.add(new String[]{qDir + "/read_ahead_kb", "512"});
            list.add(new String[]{qDir + "/add_random", "0"});
        }
        for (int i = 0; i < VM_TARGETS.length; i++) {
            list.add(VM_TARGETS[i]);
        }
        return list;
    }

    private static String applyVmIo(SessionState st) {
        List<String[]> targets = resolveVmIoTargets();
        int okCount = 0;
        for (int i = 0; i < targets.size(); i++) {
            String path = targets.get(i)[0];
            String val = targets.get(i)[1];
            if (!new File(path).exists()) {
                continue;
            }
            saveOrigSysfsOnce(st, path);
            if (writeSysfs(path, val)) {
                okCount++;
            }
        }
        st.enabled.put("vm_io", Boolean.valueOf(okCount > 0));
        String note = okCount > 0 ? "512KB / VM (" + okCount + "항목)" : "적용 실패";
        st.statusNote.put("vm_io", note);
        return "스토리지·VM 버퍼 최적화 완료 (" + note + ")";
    }

    private static String revertVmIo(SessionState st) {
        List<String[]> targets = resolveVmIoTargets();
        int restored = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (restoreOrigSysfsIfPresent(st, targets.get(i)[0])) {
                restored++;
            }
        }
        // Also restore any previously recorded block/vm keys cleanly
        List<String> keys = new ArrayList<String>(st.origSysfs.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String p = keys.get(i);
            if (p.startsWith("/sys/block/") || p.startsWith("/proc/sys/vm/")) {
                if (restoreOrigSysfsIfPresent(st, p)) {
                    restored++;
                }
            }
        }
        st.enabled.put("vm_io", Boolean.FALSE);
        st.statusNote.put("vm_io", "순정 복구됨");
        return "스토리지·VM 버퍼 순정 복구 완료 (" + restored + "항목)";
    }

    // -------------------------------------------------------------------------
    // 4. Process Priority Boost & cpuctl bg_non_interactive Isolation
    // -------------------------------------------------------------------------

    private static final String CPUCTL_APPS_PROCS = "/dev/cpuctl/apps/cgroup.procs";
    private static final String CPUCTL_APPS_TASKS = "/dev/cpuctl/apps/tasks";
    private static final String CPUCTL_BG_PROCS = "/dev/cpuctl/apps/bg_non_interactive/cgroup.procs";
    private static final String CPUCTL_BG_TASKS = "/dev/cpuctl/apps/bg_non_interactive/tasks";

    private static boolean isProtectedProcess(String name) {
        if (name == null || name.isEmpty()) return true;
        String s = name.toLowerCase(Locale.US);
        String[] guard = new String[]{
                "micomd", "hscand", "rearcamera", ".ev3", "servicemanager",
                "zygote", "vold", "netd", "mediaserver", "rild", "bluetooth",
                "com.android.phone", "nemesis", "usb-handler", "hkg.sysbench"
        };
        for (int i = 0; i < guard.length; i++) {
            if (s.contains(guard[i])) {
                return true;
            }
        }
        return false;
    }

    private static int getUiBoostNice(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return Integer.MAX_VALUE;
        String s = cmdline.toLowerCase(Locale.US);
        if (s.contains("surfaceflinger") || "system_server".equals(s) || s.endsWith("/system_server")) {
            return -8;
        }
        String[] uiPkgs = new String[]{
                "com.android.systemui",
                "com.android.launcher3",
                "com.mnsoft.navi",
                "com.mobis.splitscreen",
                "com.google.android.projection.sink",
                "com.daudio.app.dipo",
                "com.daudio.app.phoneprojection"
        };
        for (int i = 0; i < uiPkgs.length; i++) {
            if (s.contains(uiPkgs[i])) {
                return -4;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isBgIsolateTarget(String cmdline) {
        if (cmdline == null || cmdline.isEmpty() || isProtectedProcess(cmdline)) {
            return false;
        }
        String s = cmdline.toLowerCase(Locale.US);
        String[] bgPkgs = new String[]{
                "com.hkmc.telematics.apps.vrm",
                "weather",
                "sports",
                "voicememo",
                "naturesound",
                "valetmode",
                "dmclient",
                "otaclient",
                "ccsimagedownloadservice",
                "themesettings"
        };
        for (int i = 0; i < bgPkgs.length; i++) {
            if (s.contains(bgPkgs[i])) {
                return true;
            }
        }
        return false;
    }

    private static String applySched(SessionState st) {
        double tempC = KernelCollectors.MemAndTunableSnapshot.capture().thermalMaxC;
        boolean hot = (tempC >= 80.0);

        File procRoot = new File("/proc");
        String[] entries = procRoot.list();
        int boostCnt = 0;
        int bgCnt = 0;
        if (entries != null) {
            for (int i = 0; i < entries.length; i++) {
                String pidStr = entries[i];
                if (!isAllDigits(pidStr)) continue;
                int pid = (int) KernelCollectors.parseLongSafe(pidStr, -1L);
                if (pid <= 1) continue;

                String cmd = KernelCollectors.readFileUtf8("/proc/" + pidStr + "/cmdline").replace('\0', ' ').trim();
                if (cmd.isEmpty()) continue;

                int targetUiNice = hot ? Integer.MAX_VALUE : getUiBoostNice(cmd);
                if (targetUiNice < Integer.MAX_VALUE && !isProtectedProcess(cmd)) {
                    int origNice = readMainNice(pidStr);
                    boolean wasBg = isPidInBgCgroup(pidStr);
                    recordProcOriginOnce(st, pidStr, origNice, wasBg, cmd);
                    movePidToCgroup(pidStr, CPUCTL_APPS_PROCS, CPUCTL_APPS_TASKS);
                    if (origNice > targetUiNice) {
                        setProcessNice(pid, targetUiNice);
                    }
                    ScenarioRunner.runShellCmd(new String[]{"/system/bin/ionice", pidStr, "be", "1"}, 1500L);
                    boostCnt++;
                } else if (isBgIsolateTarget(cmd)) {
                    int origNice = readMainNice(pidStr);
                    boolean wasBg = isPidInBgCgroup(pidStr);
                    recordProcOriginOnce(st, pidStr, origNice, wasBg, cmd);
                    movePidToCgroup(pidStr, CPUCTL_BG_PROCS, CPUCTL_BG_TASKS);
                    setProcessNice(pid, 10);
                    ScenarioRunner.runShellCmd(new String[]{"/system/bin/ionice", pidStr, "idle", "7"}, 1500L);
                    bgCnt++;
                }
            }
        }

        st.enabled.put("sched", Boolean.TRUE);
        String note = "부스트 " + boostCnt + " · 격리 " + bgCnt + (hot ? " (고온보호)" : "");
        st.statusNote.put("sched", note);
        return "프로세스 스케줄링 최적화 (" + note + ")";
    }

    private static String revertSched(SessionState st) {
        int restored = 0;
        List<String> pids = new ArrayList<String>(st.origProc.keySet());
        for (int i = 0; i < pids.size(); i++) {
            String pidStr = pids.get(i);
            String packed = st.origProc.get(pidStr);
            if (packed == null) continue;
            int p1 = packed.indexOf('|');
            int p2 = (p1 >= 0) ? packed.indexOf('|', p1 + 1) : -1;
            if (p1 < 0 || p2 < 0) continue;

            int origNice = (int) KernelCollectors.parseLongSafe(packed.substring(0, p1), 0L);
            boolean wasBg = "1".equals(packed.substring(p1 + 1, p2));
            String origCmd = packed.substring(p2 + 1);

            String curCmd = KernelCollectors.readFileUtf8("/proc/" + pidStr + "/cmdline").replace('\0', ' ').trim().replace('|', '_');
            if (curCmd.isEmpty() || !curCmd.equals(origCmd)) {
                continue;
            }
            if (wasBg) {
                movePidToCgroup(pidStr, CPUCTL_BG_PROCS, CPUCTL_BG_TASKS);
            } else {
                movePidToCgroup(pidStr, CPUCTL_APPS_PROCS, CPUCTL_APPS_TASKS);
            }
            int pid = (int) KernelCollectors.parseLongSafe(pidStr, -1L);
            if (pid > 1) {
                setProcessNice(pid, origNice);
                ScenarioRunner.runShellCmd(new String[]{"/system/bin/ionice", pidStr, "be", "4"}, 1500L);
            }
            restored++;
        }
        st.origProc.clear();
        st.enabled.put("sched", Boolean.FALSE);
        st.statusNote.put("sched", "순정 복구됨");
        return "프로세스 우선순위/cgroup 순정 복구 (" + restored + "개)";
    }

    private static void recordProcOriginOnce(SessionState st, String pidStr, int origNice, boolean wasBg, String cmd) {
        if (!st.origProc.containsKey(pidStr)) {
            st.origProc.put(pidStr, origNice + "|" + (wasBg ? "1" : "0") + "|" + cmd.replace('|', '_'));
        }
    }

    private static boolean isPidInBgCgroup(String pidStr) {
        String cg = KernelCollectors.readFileUtf8("/proc/" + pidStr + "/cgroup");
        return cg.contains("bg_non_interactive");
    }

    private static void movePidToCgroup(String pidStr, String procsPath, String tasksPath) {
        if (new File(procsPath).exists()) {
            writeSysfs(procsPath, pidStr);
        } else if (new File(tasksPath).exists()) {
            writeSysfs(tasksPath, pidStr);
        }
    }

    private static int readMainNice(String pidStr) {
        String stat = KernelCollectors.readFirstLine("/proc/" + pidStr + "/stat");
        if (stat.isEmpty()) return 0;
        int rp = stat.lastIndexOf(')');
        if (rp < 0 || rp + 2 >= stat.length()) return 0;
        String[] cols = stat.substring(rp + 2).trim().split("\\s+");
        if (cols.length > 16) {
            return (int) KernelCollectors.parseLongSafe(cols[16], 0L);
        }
        return 0;
    }

    private static void setProcessNice(int pid, int prio) {
        try {
            Class<?> pCls = Class.forName("android.os.Process");
            pCls.getMethod("setThreadPriority", int.class, int.class).invoke(null, Integer.valueOf(pid), Integer.valueOf(prio));
            if (readMainNice(String.valueOf(pid)) == prio) {
                return;
            }
        } catch (Throwable ignored) {
        }
        ScenarioRunner.runShellCmd(new String[]{"/system/bin/renice", String.valueOf(prio), String.valueOf(pid)}, 1500L);
    }

    // -------------------------------------------------------------------------
    // 5. Hardware Peripheral IRQ Affinity Dispersion (CPU0 -> CPU1..3)
    // -------------------------------------------------------------------------

    public static int classifyIrqTargetCpu(String deviceName, int cpuCount) {
        if (cpuCount < 2 || deviceName == null || deviceName.isEmpty()) {
            return -1;
        }
        String d = deviceName.toLowerCase(Locale.US);
        // Never move critical timers, watchdogs, IPIs, or MICOM/HSCAN UARTs
        String[] protectedIrq = new String[]{
                "timer", "wdt", "watchdog", "uart", "serial", "hscan", "can", "ipi"
        };
        for (int i = 0; i < protectedIrq.length; i++) {
            if (d.contains(protectedIrq[i])) {
                return -1;
            }
        }
        if (d.contains("mip4_ts") || d.contains("fts") || d.contains("touch")) {
            return 1;
        }
        if (d.contains("tcc_main_d") || d.contains("wmixer") || d.contains("mali") || d.contains(".gpu")) {
            return Math.min(2, cpuCount - 1);
        }
        if (d.contains("tcc-sdhc") || d.contains("mmc0") || d.contains("mmc1")
                || d.contains("dwc_otg") || d.contains("ehci") || d.contains("ohci") || d.contains("xhci")) {
            return Math.min(3, cpuCount - 1);
        }
        return -1;
    }

    private static String applyIrq(SessionState st) {
        KernelCollectors.IrqSnapshot snap = KernelCollectors.IrqSnapshot.capture();
        int okCnt = 0;
        int candCnt = 0;
        for (int i = 0; i < snap.rows.size(); i++) {
            KernelCollectors.IrqRow row = snap.rows.get(i);
            if (!isAllDigits(row.label)) continue;
            int targetCpu = classifyIrqTargetCpu(row.device, snap.cpuCount);
            if (targetCpu <= 0) continue;

            candCnt++;
            String affPath = "/proc/irq/" + row.label + "/smp_affinity";
            if (!new File(affPath).exists()) continue;
            if (!st.origIrq.containsKey(row.label)) {
                String origVal = KernelCollectors.readFirstLine(affPath);
                if (!origVal.isEmpty()) {
                    st.origIrq.put(row.label, origVal);
                }
            }
            String maskHex = Integer.toHexString(1 << targetCpu);
            if (writeSysfs(affPath, maskHex)) {
                okCnt++;
            }
        }
        if (okCnt > 0) {
            st.enabled.put("irq", Boolean.TRUE);
            String note = okCnt + "개 IRQ 분산됨";
            st.statusNote.put("irq", note);
            return "주변장치 IRQ 멀티코어 분산 (" + note + ")";
        }
        st.enabled.put("irq", Boolean.FALSE);
        String note = candCnt > 0 ? "커널 읽기전용 (미지원)" : "대상 IRQ 없음";
        st.statusNote.put("irq", note);
        return "IRQ 분산 불가: " + note;
    }

    private static String revertIrq(SessionState st) {
        int restored = 0;
        for (Map.Entry<String, String> e : st.origIrq.entrySet()) {
            String affPath = "/proc/irq/" + e.getKey() + "/smp_affinity";
            if (writeSysfs(affPath, e.getValue())) {
                restored++;
            }
        }
        st.enabled.put("irq", Boolean.FALSE);
        st.statusNote.put("irq", "순정 복구됨");
        return "IRQ smp_affinity 순정 복구 (" + restored + "개)";
    }

    // -------------------------------------------------------------------------
    // 6. Window / Transition Animation Scales (Live Binder + Boot Guard)
    // -------------------------------------------------------------------------

    private static String applyAnim(File resultsDir, SessionState st) {
        if (!st.hasOrigAnim) {
            float[] cur = readCurrentAnimScales();
            st.origAnimWin = cur[0];
            st.origAnimTrans = cur[1];
            st.origAnimDur = cur[2];
            st.hasOrigAnim = true;
        }
        if (resultsDir != null) {
            String guardBody = "boot_id=" + st.bootId + "\n"
                    + "anim_win=" + String.format(Locale.US, "%.4f", st.origAnimWin) + "\n"
                    + "anim_trans=" + String.format(Locale.US, "%.4f", st.origAnimTrans) + "\n"
                    + "anim_dur=" + String.format(Locale.US, "%.4f", st.origAnimDur) + "\n";
            BenchReport.writeUtf8(new File(resultsDir, GUARD_FILE_NAME), guardBody);
        }
        boolean ok = applyAnimationScales(0.25f, 0.25f, 0.5f);
        st.enabled.put("anim", Boolean.valueOf(ok));
        st.statusNote.put("anim", ok ? "0.25x / 0.5x 적용됨" : "적용 실패");
        return ok ? "애니메이션 가속 적용 (0.25x / 0.25x / 0.5x)" : "애니메이션 배율 변경 실패";
    }

    private static String revertAnim(File resultsDir, SessionState st) {
        float w = st.hasOrigAnim ? st.origAnimWin : 1.0f;
        float t = st.hasOrigAnim ? st.origAnimTrans : 1.0f;
        float d = st.hasOrigAnim ? st.origAnimDur : 1.0f;
        boolean ok = applyAnimationScales(w, t, d);
        if (resultsDir != null) {
            new File(resultsDir, GUARD_FILE_NAME).delete();
        }
        st.enabled.put("anim", Boolean.FALSE);
        st.statusNote.put("anim", String.format(Locale.US, "%.1fx 순정 복구됨", t));
        return String.format(Locale.US, "애니메이션 배율 순정 복구 (%s, %.1fx / %.1fx / %.1fx)", ok ? "OK" : "부분복구", w, t, d);
    }

    public static float[] readCurrentAnimScales() {
        float[] scales = new float[]{1.0f, 1.0f, 1.0f};
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Object winBinder = smCls.getMethod("getService", String.class).invoke(null, "window");
            if (winBinder != null) {
                Class<?> stubCls = Class.forName("android.view.IWindowManager$Stub");
                Object wm = stubCls.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, winBinder);
                Method getScale = wm.getClass().getMethod("getAnimationScale", int.class);
                for (int i = 0; i < 3; i++) {
                    Object res = getScale.invoke(wm, Integer.valueOf(i));
                    if (res instanceof Number) {
                        float f = ((Number) res).floatValue();
                        if (f >= 0.0f && f <= 10.0f) {
                            scales[i] = f;
                        }
                    }
                }
                return scales;
            }
        } catch (Throwable ignored) {
        }
        return scales;
    }

    public static boolean applyAnimationScales(float win, float trans, float dur) {
        boolean applied = false;
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Object winBinder = smCls.getMethod("getService", String.class).invoke(null, "window");
            if (winBinder != null) {
                Class<?> stubCls = Class.forName("android.view.IWindowManager$Stub");
                Object wm = stubCls.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, winBinder);
                try {
                    Method setScale = wm.getClass().getMethod("setAnimationScale", int.class, float.class);
                    setScale.invoke(wm, Integer.valueOf(0), Float.valueOf(win));
                    setScale.invoke(wm, Integer.valueOf(1), Float.valueOf(trans));
                    setScale.invoke(wm, Integer.valueOf(2), Float.valueOf(dur));
                    applied = true;
                } catch (Throwable ignored) {
                    Method setScales = wm.getClass().getMethod("setAnimationScales", float[].class);
                    setScales.invoke(wm, new Object[]{new float[]{win, trans, dur}});
                    applied = true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!applied) {
            ScenarioRunner.runShellCmd(new String[]{"/system/bin/settings", "put", "global", "window_animation_scale", String.valueOf(win)}, 2000L);
            ScenarioRunner.runShellCmd(new String[]{"/system/bin/settings", "put", "global", "transition_animation_scale", String.valueOf(trans)}, 2000L);
            ScenarioRunner.runShellCmd(new String[]{"/system/bin/settings", "put", "global", "animator_duration_scale", String.valueOf(dur)}, 2000L);
            applied = true;
        }
        return applied;
    }

    // -------------------------------------------------------------------------
    // Generic Helpers
    // -------------------------------------------------------------------------

    private static void saveOrigSysfsOnce(SessionState st, String path) {
        if (st.origSysfs.containsKey(path)) {
            return;
        }
        File f = new File(path);
        if (f.exists() && f.canRead()) {
            String cur = KernelCollectors.readFirstLine(path);
            if (!cur.isEmpty()) {
                st.origSysfs.put(path, cur);
            }
        }
    }

    private static boolean restoreOrigSysfsIfPresent(SessionState st, String path) {
        String orig = st.origSysfs.get(path);
        if (orig != null && !orig.isEmpty() && new File(path).exists()) {
            return writeSysfs(path, orig);
        }
        return false;
    }

    private static boolean writeSysfs(String path, String val) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(path);
            fos.write((val + "\n").getBytes(UTF8));
            fos.flush();
            return true;
        } catch (Throwable ignored) {
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

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
