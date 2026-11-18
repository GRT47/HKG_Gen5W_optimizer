package com.hkg.sysbench;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * HKG Gen5W System Performance Benchmark & Baseline Probe (hkg.sysbench).
 *
 * Modes:
 * - test-self                     : Host JDK unit self-tests (zero Android dependencies)
 * - cli <before|after|quick> <dir>: Headless CLI benchmark runner
 * - daemon <installDir>           : Interactive Gen5W overlay UI (uid=root, TYPE_VOLUME_OVERLAY)
 */
public class SysBenchMain {

    public static final String VERSION = "1.2.3";
    private static final String PID_FILE_PATH = "/dev/hkg_tmp/hkg_sysbench_java.pid";
    private static final String CMD_FILE_PATH = "/dev/hkg_tmp/hkg_sysbench_cmd";

    public static final class BenchConfig {
        public int x = 198;
        public int y = 18;
        public int idleSeconds = 20;
        public int launchRounds = 3;
        public int swipeCount = 8;
    }

    public static void main(String[] args) {
        String mode = (args != null && args.length > 0) ? args[0] : "test-self";
        if ("test-self".equals(mode)) {
            runSelfTests();
            return;
        }
        String installDirPath = (args != null && args.length >= 2) ? args[1] : "/data/nemesis/apps/hkg.sysbench";
        if ("cli".equals(mode)) {
            String slot = (args != null && args.length >= 2) ? args[1] : "quick";
            installDirPath = (args != null && args.length >= 3) ? args[2] : "/data/nemesis/apps/hkg.sysbench";
            runCli(slot, new File(installDirPath));
            return;
        }
        if ("cli-tweaks".equals(mode)) {
            String action = (args != null && args.length >= 2) ? args[1] : "status";
            installDirPath = (args != null && args.length >= 3) ? args[2] : "/data/nemesis/apps/hkg.sysbench";
            runCliTweaks(action, new File(installDirPath));
            return;
        }
        if ("restore-anim".equals(mode)) {
            float w = (args != null && args.length >= 2) ? (float) KernelCollectors.parseDoubleSafe(args[1], 1.0) : 1.0f;
            float t = (args != null && args.length >= 3) ? (float) KernelCollectors.parseDoubleSafe(args[2], 1.0) : 1.0f;
            float d = (args != null && args.length >= 4) ? (float) KernelCollectors.parseDoubleSafe(args[3], 1.0) : 1.0f;
            boolean ok = TweakManager.applyAnimationScales(w, t, d);
            System.out.println("[SysBench] restore-anim (" + w + "/" + t + "/" + d + "): " + (ok ? "OK" : "FAILED"));
            return;
        }
        if ("daemon".equals(mode)) {
            runDaemon(new File(installDirPath));
            return;
        }
        System.out.println("Unknown mode: " + mode);
        System.exit(1);
    }

    private static void runCliTweaks(String action, File installDir) {
        File resultsDir = new File(installDir, "results");
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }
        TweakManager.SessionState st = TweakManager.loadSession(resultsDir);
        String msg;
        if ("on".equalsIgnoreCase(action)) {
            msg = TweakManager.applyRecommendedAll(resultsDir, st);
        } else if ("off".equalsIgnoreCase(action)) {
            msg = TweakManager.revertAllToStock(resultsDir, st);
        } else {
            msg = "활성 트윅 상태 조회 (" + TweakManager.countActive(st) + "/" + TweakManager.TWEAK_DEFS.length + ")";
        }
        KernelCollectors.MemAndTunableSnapshot snap = KernelCollectors.MemAndTunableSnapshot.capture();
        float[] scales = TweakManager.readCurrentAnimScales();
        System.out.println("[SysBench] " + msg);
        System.out.println("bootId=" + st.bootId + " activeTweaks=" + TweakManager.getActiveSummaryCsv(st));
        System.out.println("live: ksmRun=" + snap.ksmRun + " ksmSleepMs=" + snap.ksmSleepMs
                + " readAheadKb=" + snap.readAheadKb + " swappiness=" + snap.swappiness
                + " dirty=" + snap.dirtyRatio + "/" + snap.dirtyBgRatio
                + " anim=" + String.format(Locale.US, "%.2f/%.2f/%.2f", scales[0], scales[1], scales[2])
                + " eventlog=" + snap.eventlogRunning + " tcc_logd=" + snap.tccLogdRunning + " klogger=" + snap.kloggerRunning);
        for (int i = 0; i < TweakManager.TWEAK_DEFS.length; i++) {
            TweakManager.TweakDef d = TweakManager.TWEAK_DEFS[i];
            System.out.println(" - [" + (st.isEnabled(d.id) ? "ON " : "OFF") + "] " + d.id + ": " + st.getNote(d.id));
        }
    }

    private static BenchConfig loadConfig(File installDir) {
        BenchConfig cfg = new BenchConfig();
        File f = new File(installDir, "config/sysbench.conf");
        String raw = KernelCollectors.readFileUtf8(f.getAbsolutePath());
        if (raw.isEmpty()) {
            return cfg;
        }
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if ("x".equals(k)) cfg.x = (int) KernelCollectors.parseLongSafe(v, cfg.x);
            else if ("y".equals(k)) cfg.y = (int) KernelCollectors.parseLongSafe(v, cfg.y);
            else if ("idle_seconds".equals(k)) cfg.idleSeconds = Math.max(5, (int) KernelCollectors.parseLongSafe(v, cfg.idleSeconds));
            else if ("launch_rounds".equals(k)) cfg.launchRounds = Math.max(1, (int) KernelCollectors.parseLongSafe(v, cfg.launchRounds));
            else if ("swipe_count".equals(k)) cfg.swipeCount = Math.max(2, (int) KernelCollectors.parseLongSafe(v, cfg.swipeCount));
        }
        return cfg;
    }

    private static void saveCoords(File installDir, BenchConfig cfg) {
        File f = new File(installDir, "config/sysbench.conf");
        String body = "# HKG 5W SysBench configuration\n"
                + "x=" + cfg.x + "\n"
                + "y=" + cfg.y + "\n"
                + "idle_seconds=" + cfg.idleSeconds + "\n"
                + "launch_rounds=" + cfg.launchRounds + "\n"
                + "swipe_count=" + cfg.swipeCount + "\n";
        BenchReport.writeUtf8(f, body);
    }

    private static void runCli(String slotArg, File installDir) {
        BenchConfig cfg = loadConfig(installDir);
        File resultsDir = new File(installDir, "results");
        boolean quick = "quick".equalsIgnoreCase(slotArg);
        String slot = quick ? "quick" : ("after".equalsIgnoreCase(slotArg) ? "after" : "before");
        boolean[] cancel = new boolean[]{false};

        BenchReport.BenchResult res = ScenarioRunner.run(
                slot,
                quick,
                cfg.idleSeconds,
                cfg.launchRounds,
                cfg.swipeCount,
                null,
                resultsDir,
                new ScenarioRunner.ProgressListener() {
                    @Override
                    public void onProgress(String phaseText) {
                        System.out.println("[SysBench] " + phaseText);
                    }
                },
                cancel
        );
        if (!res.abortedReason.isEmpty()) {
            System.out.println("[SysBench] Aborted: " + res.abortedReason);
            System.exit(2);
        }
        String savedPath = BenchReport.saveResults(resultsDir, res);
        BenchReport.BenchResult before = BenchReport.loadSlot(resultsDir, "before");
        BenchReport.BenchResult after = BenchReport.loadSlot(resultsDir, "after");
        System.out.println(BenchReport.buildTextSummary(before, after, res));
        System.out.println("Saved to: " + savedPath);
    }

    private static void runDaemon(File installDir) {
        File tmpDir = new File("/dev/hkg_tmp");
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }
        try {
            Class<?> procCls = Class.forName("android.os.Process");
            procCls.getMethod("setArgV0", String.class).invoke(null, "com.hkg.sysbench.SysBenchMain");
            Object pidObj = procCls.getMethod("myPid").invoke(null);
            if (pidObj instanceof Number) {
                BenchReport.writeUtf8(new File(PID_FILE_PATH), ((Number) pidObj).intValue() + "\n");
            }
        } catch (Throwable ignored) {
        }
        System.out.println("[SysBench] Starting daemon v" + VERSION + " (installDir=" + installDir.getAbsolutePath() + ")");
        System.out.flush();

        BenchConfig cfg = loadConfig(installDir);
        File resultsDir = new File(installDir, "results");
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }
        OverlayController.initAndLoop(installDir, resultsDir, cfg);
    }

    // =========================================================================
    // OVERLAY CONTROLLER (Pure Reflection, API 19 Verified)
    // =========================================================================

    private static final class OverlayController {
        private static File installDir;
        private static File resultsDir;
        private static BenchConfig config;

        private static volatile Object sysContext;
        private static volatile Object windowManager;
        private static volatile Object uiHandler;
        private static volatile Object rootView;
        private static volatile Object wmLp;
        private static volatile Object compactBox;
        private static volatile Object compactTv;
        private static volatile Object expandedBox;

        private static volatile Object headerPlatTv;
        private static volatile Object statusBannerTv;
        private static volatile Object tweakListContainer;
        private static volatile Object tableContainer;
        private static volatile Object footerTv;

        private static volatile TweakManager.SessionState sessionState;
        private static volatile BenchReport.BenchResult beforeRes;
        private static volatile BenchReport.BenchResult afterRes;
        private static volatile BenchReport.BenchResult quickRes;
        private static volatile String lastSaveDir = "";
        private static volatile String currentStatus = "준비 완료 — BEFORE 측정 후 좌측 트윅을 켜고(재부팅 시 순정) AFTER를 비교하세요.";

        private static volatile boolean isVisible = false;
        private static volatile boolean isExpanded = true;
        private static volatile boolean isRunning = false;
        private static volatile boolean isTweaking = false;
        private static volatile boolean hiddenForRearCam = false;
        private static final boolean[] cancelBenchFlag = new boolean[]{false};

        private static final int DASH_WIDTH = 1480;
        private static final int LEFT_WIDTH = 560;
        private static final int GAP_WIDTH = 14;
        private static final int RIGHT_WIDTH = 906;
        private static final CountDownLatch uiLatch = new CountDownLatch(1);
        private static final Runnable RETRY_SHOW_TASK = new Runnable() {
            @Override
            public void run() {
                showOnUiThread();
            }
        };

        public static void initAndLoop(File instDir, File resDir, BenchConfig cfg) {
            installDir = instDir;
            resultsDir = resDir;
            config = cfg;
            sessionState = TweakManager.loadSession(resultsDir);
            beforeRes = BenchReport.loadSlot(resultsDir, "before");
            afterRes = BenchReport.loadSlot(resultsDir, "after");
            quickRes = BenchReport.loadSlot(resultsDir, "quick");

            Thread uiThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> looperCls = Class.forName("android.os.Looper");
                        try {
                            looperCls.getMethod("prepareMainLooper").invoke(null);
                        } catch (Throwable ignored) {
                            try {
                                looperCls.getMethod("prepare").invoke(null);
                            } catch (Throwable ignored2) {
                            }
                        }
                        Object looper = looperCls.getMethod("myLooper").invoke(null);
                        Class<?> handlerCls = Class.forName("android.os.Handler");
                        uiHandler = handlerCls.getConstructor(looperCls).newInstance(looper);
                        ensureUiContext();
                        uiLatch.countDown();

                        postToUi(new Runnable() {
                            @Override
                            public void run() {
                                showOnUiThread();
                            }
                        });

                        Method loopMethod = looperCls.getMethod("loop");
                        while (true) {
                            try {
                                loopMethod.invoke(null);
                                break;
                            } catch (Throwable loopErr) {
                                Throwable cause = (loopErr.getCause() != null) ? loopErr.getCause() : loopErr;
                                System.out.println("[SysBench] UI Looper caught: " + cause);
                            }
                        }
                    } catch (Throwable t) {
                        uiLatch.countDown();
                        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                        System.out.println("[SysBench] UI Looper init error: " + cause);
                    }
                }
            }, "SysBenchUiThread");
            uiThread.setDaemon(true);
            uiThread.start();

            try {
                uiLatch.await(3000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }

            // Main command & rear-camera safety polling loop
            File cmdFile = new File(CMD_FILE_PATH);
            while (true) {
                try {
                    if (cmdFile.exists()) {
                        String cmd = KernelCollectors.readFirstLine(CMD_FILE_PATH).toLowerCase(Locale.US);
                        cmdFile.delete();
                        if ("stop".equals(cmd)) {
                            cancelBenchFlag[0] = true;
                            postToUi(new Runnable() {
                                @Override
                                public void run() {
                                    hideAndExit();
                                }
                            });
                            break;
                        } else if ("show".equals(cmd)) {
                            sessionState = TweakManager.loadSession(resultsDir);
                            postToUi(new Runnable() {
                                @Override
                                public void run() {
                                    isExpanded = true;
                                    showOnUiThread();
                                }
                            });
                        } else if ("toggle".equals(cmd)) {
                            sessionState = TweakManager.loadSession(resultsDir);
                            postToUi(new Runnable() {
                                @Override
                                public void run() {
                                    if (isVisible) {
                                        isExpanded = !isExpanded;
                                        applyExpandStateOnUiThread();
                                        refreshContentOnUiThread();
                                    } else {
                                        isExpanded = true;
                                        showOnUiThread();
                                    }
                                }
                            });
                        } else if ("run_before".equals(cmd)) {
                            startBenchmarkAsync("before", false);
                        } else if ("run_after".equals(cmd)) {
                            startBenchmarkAsync("after", false);
                        } else if ("run_quick".equals(cmd)) {
                            startBenchmarkAsync("quick", true);
                        } else if ("export".equals(cmd)) {
                            handleExport();
                        }
                    }

                    // Rear camera guard when overlay is visible
                    if (isVisible && rootView != null) {
                        final boolean rearOpen = "1".equals(KernelCollectors.getSystemProperty("persist.sys.rearcam.open", "0"));
                        if (rearOpen != hiddenForRearCam) {
                            postToUi(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        hiddenForRearCam = rearOpen;
                                        Class<?> viewCls = Class.forName("android.view.View");
                                        viewCls.getMethod("setVisibility", int.class).invoke(rootView, Integer.valueOf(rearOpen ? 8 : 0));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                        }
                    }
                    Thread.sleep(500L);
                } catch (InterruptedException ignored) {
                    break;
                } catch (Throwable ignored) {
                }
            }
        }

        private static void startBenchmarkAsync(final String slot, final boolean quickMode) {
            if (isRunning || isTweaking) {
                return;
            }
            isRunning = true;
            cancelBenchFlag[0] = false;
            currentStatus = (quickMode ? "⚡ 빠른 진단" : slot.toUpperCase(Locale.US)) + " 준비 중...";
            postToUi(new Runnable() {
                @Override
                public void run() {
                    isExpanded = false;
                    applyExpandStateOnUiThread();
                    // Move compact badge to top-left corner so center swipes hit target window cleanly
                    updateWindowPositionOnUiThread(20, 8, false);
                }
            });

            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        sessionState = TweakManager.loadSession(resultsDir);
                        int curActive = TweakManager.countActive(sessionState);
                        String extActive = TweakManager.readExternalOptActiveCsv();
                        File optLaunch = new File("/data/nemesis/apps/hkg.optimize/bin/launch.sh");
                        if (!optLaunch.isFile()) {
                            optLaunch = new File("/data/nemesis/packages/hkg.optimize/bin/launch.sh");
                        }
                        if ("after".equals(slot) && curActive == 0 && extActive.isEmpty()) {
                            if (optLaunch.isFile()) {
                                currentStatus = "⚡ hkg.optimize 자동 ON 적용 및 안정화(6초)...";
                                postToUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateCompactTextOnUiThread();
                                    }
                                });
                                runExternalOptimizeCmd(optLaunch.getAbsolutePath(), "cli-on");
                            } else {
                                currentStatus = "⚡ 트윅 미선택 → 추천 최적화 자동 ON 적용 및 안정화(6초)...";
                                postToUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateCompactTextOnUiThread();
                                    }
                                });
                                TweakManager.applyRecommendedAll(resultsDir, sessionState);
                            }
                            ScenarioRunner.sleepInterruptible(6000L, cancelBenchFlag);
                        } else if ("before".equals(slot) && (curActive > 0 || !extActive.isEmpty())) {
                            currentStatus = "🔄 순정 기준선 측정을 위해 트윅 원복 및 안정화(4초)...";
                            postToUi(new Runnable() {
                                @Override
                                public void run() {
                                    updateCompactTextOnUiThread();
                                }
                            });
                            if (!extActive.isEmpty() && optLaunch.isFile()) {
                                runExternalOptimizeCmd(optLaunch.getAbsolutePath(), "cli-off");
                            }
                            if (curActive > 0) {
                                TweakManager.revertAllToStock(resultsDir, sessionState);
                            }
                            ScenarioRunner.sleepInterruptible(4000L, cancelBenchFlag);
                        }

                        if (cancelBenchFlag[0]) {
                            currentStatus = "중단됨 (cancelled)";
                            return;
                        }

                        BenchReport.BenchResult res = ScenarioRunner.run(
                                slot,
                                quickMode,
                                config.idleSeconds,
                                config.launchRounds,
                                config.swipeCount,
                                sysContext,
                                resultsDir,
                                new ScenarioRunner.ProgressListener() {
                                    @Override
                                    public void onProgress(String phaseText) {
                                        currentStatus = phaseText;
                                        postToUi(new Runnable() {
                                            @Override
                                            public void run() {
                                                updateCompactTextOnUiThread();
                                            }
                                        });
                                    }
                                },
                                cancelBenchFlag
                        );

                        if (!res.abortedReason.isEmpty()) {
                            currentStatus = "중단됨 (" + res.abortedReason + ")";
                        } else {
                            if ("before".equals(slot)) beforeRes = res;
                            else if ("after".equals(slot)) afterRes = res;
                            else quickRes = res;
                            lastSaveDir = BenchReport.saveResults(resultsDir, res);
                            currentStatus = "✅ " + slot.toUpperCase(Locale.US) + " 측정 완료! 저장: " + lastSaveDir;
                        }
                    } catch (Throwable t) {
                        currentStatus = "❌ 측정 오류: " + t.getMessage();
                    } finally {
                        isRunning = false;
                        postToUi(new Runnable() {
                            @Override
                            public void run() {
                                isExpanded = true;
                                updateWindowPositionOnUiThread(config.x, config.y, false);
                                applyExpandStateOnUiThread();
                                refreshContentOnUiThread();
                            }
                        });
                    }
                }
            }, "SysBenchWorker");
            worker.setDaemon(true);
            worker.start();
        }

        private static void runExternalOptimizeCmd(String scriptPath, String arg) {
            Process p = null;
            try {
                p = new ProcessBuilder("/system/bin/sh", scriptPath, arg)
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().close();
                p.getOutputStream().close();
                p.waitFor();
            } catch (Throwable ignored) {
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private static void handleExport() {
            String usbOut = BenchReport.exportExistingToUsb(resultsDir);
            if (usbOut.isEmpty()) {
                currentStatus = "⚠️ 내보낼 측정 결과가 없거나 USB(/storage/usb0)가 마운트되지 않았습니다.";
            } else {
                lastSaveDir = usbOut;
                currentStatus = "✅ USB 내보내기 완료: " + usbOut;
            }
            postToUi(new Runnable() {
                @Override
                public void run() {
                    refreshContentOnUiThread();
                }
            });
        }

        private static void toggleTweakAsync(final String tweakId, final boolean enable) {
            if (isRunning || isTweaking) {
                return;
            }
            isTweaking = true;
            currentStatus = "⏳ 트윅 " + (enable ? "적용" : "원복") + " 중...";
            postToUi(new Runnable() {
                @Override
                public void run() {
                    refreshContentOnUiThread();
                }
            });
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sessionState == null) {
                            sessionState = TweakManager.loadSession(resultsDir);
                        }
                        currentStatus = TweakManager.toggleTweak(resultsDir, sessionState, tweakId, enable);
                    } catch (Throwable t) {
                        currentStatus = "❌ 트윅 오류: " + t.getMessage();
                    } finally {
                        isTweaking = false;
                        postToUi(new Runnable() {
                            @Override
                            public void run() {
                                refreshContentOnUiThread();
                            }
                        });
                    }
                }
            }, "SysBenchTweakWorker");
            worker.setDaemon(true);
            worker.start();
        }

        private static void runBulkTweaksAsync(final boolean enableAll) {
            if (isRunning || isTweaking) {
                return;
            }
            isTweaking = true;
            currentStatus = enableAll ? "⏳ 추천 최적화 일괄 적용 중..." : "⏳ 모든 트윅 순정 복구 중...";
            postToUi(new Runnable() {
                @Override
                public void run() {
                    refreshContentOnUiThread();
                }
            });
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sessionState == null) {
                            sessionState = TweakManager.loadSession(resultsDir);
                        }
                        currentStatus = enableAll
                                ? TweakManager.applyRecommendedAll(resultsDir, sessionState)
                                : TweakManager.revertAllToStock(resultsDir, sessionState);
                    } catch (Throwable t) {
                        currentStatus = "❌ 트윅 오류: " + t.getMessage();
                    } finally {
                        isTweaking = false;
                        postToUi(new Runnable() {
                            @Override
                            public void run() {
                                refreshContentOnUiThread();
                            }
                        });
                    }
                }
            }, "SysBenchBulkTweakWorker");
            worker.setDaemon(true);
            worker.start();
        }

        private static void hideAndExit() {
            removeOverlayOnUiThread();
            new File(PID_FILE_PATH).delete();
            System.exit(0);
        }

        private static void postToUi(Runnable r) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("post", Runnable.class).invoke(h, r);
            } catch (Throwable ignored) {
            }
        }

        private static void postDelayedToUi(Runnable r, long delayMs) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("postDelayed", Runnable.class, long.class).invoke(h, r, Long.valueOf(delayMs));
            } catch (Throwable ignored) {
            }
        }

        private static void removeCallbacksFromUi(Runnable r) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("removeCallbacks", Runnable.class).invoke(h, r);
            } catch (Throwable ignored) {
            }
        }

        private static boolean ensureUiContext() {
            if (sysContext != null && windowManager != null) {
                return true;
            }
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getSvc = smCls.getMethod("getService", String.class);
                if (getSvc.invoke(null, "window") == null
                        || getSvc.invoke(null, "display") == null
                        || getSvc.invoke(null, "activity") == null) {
                    System.out.println("[SysBench] System services (window/display/activity) not ready yet; deferring...");
                    return false;
                }
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object at = null;
                try {
                    at = atCls.getMethod("currentActivityThread").invoke(null);
                } catch (Throwable ignored) {
                }
                if (at == null) {
                    at = atCls.getMethod("systemMain").invoke(null);
                }
                if (at != null && sysContext == null) {
                    sysContext = atCls.getMethod("getSystemContext").invoke(at);
                }
                if (sysContext != null && windowManager == null) {
                    windowManager = sysContext.getClass().getMethod("getSystemService", String.class).invoke(sysContext, "window");
                }
            } catch (Throwable t) {
                Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                System.out.println("[SysBench] ensureUiContext failed: " + cause);
                sysContext = null;
                windowManager = null;
                try {
                    Class<?> atCls = Class.forName("android.app.ActivityThread");
                    Field fSysCtx = atCls.getDeclaredField("mSystemContext");
                    fSysCtx.setAccessible(true);
                    fSysCtx.set(null, null);
                    Field fCurAt = atCls.getDeclaredField("sCurrentActivityThread");
                    fCurAt.setAccessible(true);
                    fCurAt.set(null, null);
                } catch (Throwable ignored) {
                }
            }
            return sysContext != null && windowManager != null;
        }

        private static void showOnUiThread() {
            try {
                removeCallbacksFromUi(RETRY_SHOW_TASK);
                if (!ensureUiContext()) {
                    postDelayedToUi(RETRY_SHOW_TASK, 1000L);
                    return;
                }
                if (isVisible && rootView != null) {
                    applyExpandStateOnUiThread();
                    refreshContentOnUiThread();
                    return;
                }

                Object[] views = buildViewTree();
                Class<?> viewCls = Class.forName("android.view.View");
                Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
                Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
                Class<?> vmCls = Class.forName("android.view.ViewManager");

                int[] candidateTypes = new int[]{2020, 2008, 2009, 2005};
                int flags = 0x00000008 | 0x00000020; // FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                Constructor<?> wmLpCtor = wmLpCls.getConstructor(int.class, int.class, int.class, int.class, int.class);
                Method addView = vmCls.getMethod("addView", viewCls, vgLpCls);

                for (int i = 0; i < candidateTypes.length; i++) {
                    int wtype = candidateTypes[i];
                    try {
                        Object lp = wmLpCtor.newInstance(-2, -2, wtype, flags, -3);
                        wmLpCls.getField("gravity").setInt(lp, 51);
                        wmLpCls.getField("x").setInt(lp, config.x);
                        wmLpCls.getField("y").setInt(lp, config.y);
                        wmLpCls.getMethod("setTitle", CharSequence.class).invoke(lp, "HKGSysBenchOverlay");

                        addView.invoke(windowManager, views[0], lp);
                        try {
                            Object vri = viewCls.getMethod("getViewRootImpl").invoke(views[0]);
                            if (vri != null) {
                                Field fMedia = vri.getClass().getDeclaredField("mMediaDisabled");
                                fMedia.setAccessible(true);
                                fMedia.setBoolean(vri, true);
                            }
                        } catch (Throwable ignored) {
                        }

                        rootView = views[0];
                        wmLp = lp;
                        compactBox = views[1];
                        compactTv = views[2];
                        expandedBox = views[3];
                        isVisible = true;
                        applyExpandStateOnUiThread();
                        refreshContentOnUiThread();
                        System.out.println("[SysBench] Overlay displayed (windowType=" + wtype + ", x=" + config.x + ", y=" + config.y + ")");
                        System.out.flush();
                        break;
                    } catch (Throwable t) {
                        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                        System.out.println("[SysBench] addView(wtype=" + wtype + ") failed: " + cause);
                    }
                }
            } catch (Throwable t) {
                Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                System.out.println("[SysBench] showOnUiThread failed: " + cause);
            }
        }

        private static void removeOverlayOnUiThread() {
            Object old = rootView;
            rootView = null;
            wmLp = null;
            isVisible = false;
            if (old != null && windowManager != null) {
                try {
                    Class<?> viewCls = Class.forName("android.view.View");
                    Class<?> vmCls = Class.forName("android.view.ViewManager");
                    vmCls.getMethod("removeView", viewCls).invoke(windowManager, old);
                } catch (Throwable ignored) {
                }
            }
        }

        private static Object[] buildViewTree() throws Exception {
            Class<?> ctxCls = Class.forName("android.content.Context");
            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> vgCls = Class.forName("android.view.ViewGroup");
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> llCls = Class.forName("android.widget.LinearLayout");
            Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
            Class<?> flLpCls = Class.forName("android.widget.FrameLayout$LayoutParams");
            Class<?> svCls = Class.forName("android.widget.ScrollView");
            Class<?> tvCls = Class.forName("android.widget.TextView");

            Constructor<?> llCtor = llCls.getConstructor(ctxCls);
            Constructor<?> svCtor = svCls.getConstructor(ctxCls);
            Constructor<?> tvCtor = tvCls.getConstructor(ctxCls);
            Constructor<?> llLp2 = llLpCls.getConstructor(int.class, int.class);
            Constructor<?> llLp3 = llLpCls.getConstructor(int.class, int.class, float.class);
            Constructor<?> flLp2 = flLpCls.getConstructor(int.class, int.class);
            Method setOri = llCls.getMethod("setOrientation", int.class);
            Method addView = vgCls.getMethod("addView", viewCls, vgLpCls);
            Method setPad = viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class);
            Method setGrav = tvCls.getMethod("setGravity", int.class);

            final int MATCH = -1;
            final int WRAP = -2;

            Object root = llCtor.newInstance(sysContext);
            setOri.invoke(root, Integer.valueOf(1));
            setRoundedBg(root, 0xEE0B111E, 0xFF38BDF8, 2, 14f);

            // Compact Pill (shown during measurement or minimized)
            Object cBox = llCtor.newInstance(sysContext);
            setOri.invoke(cBox, Integer.valueOf(0));
            setPad.invoke(cBox, 20, 12, 20, 12);
            Object cTv = tvCtor.newInstance(sysContext);
            setTextStyle(cTv, "📊 HKG 5W SysBench", 14.5f, 0xFFE2E8F0, true);
            addView.invoke(cBox, cTv, llLp2.newInstance(WRAP, WRAP));
            attachButtonTouch(cBox, new Runnable() {
                @Override
                public void run() {
                    if (!isRunning) {
                        isExpanded = true;
                        applyExpandStateOnUiThread();
                        refreshContentOnUiThread();
                    }
                }
            });
            addView.invoke(root, cBox, llLp2.newInstance(WRAP, WRAP));

            // Expanded Dashboard
            Object eBox = llCtor.newInstance(sysContext);
            setOri.invoke(eBox, Integer.valueOf(1));
            setPad.invoke(eBox, 22, 16, 22, 16);

            // 1. Header Row (Draggable)
            Object hdr = llCtor.newInstance(sysContext);
            setOri.invoke(hdr, Integer.valueOf(0));
            Object titleCol = llCtor.newInstance(sysContext);
            setOri.invoke(titleCol, Integer.valueOf(1));
            Object titleTv = tvCtor.newInstance(sysContext);
            setTextStyle(titleTv, "📊 HKG Gen5W SysBench & Tweak v" + VERSION, 18.5f, 0xFF38BDF8, true);
            addView.invoke(titleCol, titleTv, llLp2.newInstance(WRAP, WRAP));

            Object platTv = tvCtor.newInstance(sysContext);
            setTextStyle(platTv, "로딩 중...", 13f, 0xFF94A3B8, false);
            addView.invoke(titleCol, platTv, llLp2.newInstance(WRAP, WRAP));
            headerPlatTv = platTv;
            addView.invoke(hdr, titleCol, llLp3.newInstance(0, WRAP, 1.0f));

            Object minBtn = tvCtor.newInstance(sysContext);
            setTextStyle(minBtn, " ▲ 축소 ", 13.5f, 0xFFE2E8F0, true);
            setGrav.invoke(minBtn, Integer.valueOf(17));
            setRoundedBg(minBtn, 0xFF1E293B, 0xFF475569, 1, 8f);
            setPad.invoke(minBtn, 14, 8, 14, 8);
            attachButtonTouch(minBtn, new Runnable() {
                @Override
                public void run() {
                    isExpanded = false;
                    applyExpandStateOnUiThread();
                }
            });
            Object minLp = llLp2.newInstance(WRAP, WRAP);
            setMargins(minLp, 8, 0, 10, 0);
            addView.invoke(hdr, minBtn, minLp);

            Object closeBtn = tvCtor.newInstance(sysContext);
            setTextStyle(closeBtn, " ✕ 종료 ", 13.5f, 0xFFFFFFFF, true);
            setGrav.invoke(closeBtn, Integer.valueOf(17));
            setRoundedBg(closeBtn, 0xFFDC2626, 0xFFF87171, 1, 8f);
            setPad.invoke(closeBtn, 14, 8, 14, 8);
            attachButtonTouch(closeBtn, new Runnable() {
                @Override
                public void run() {
                    cancelBenchFlag[0] = true;
                    hideAndExit();
                }
            });
            addView.invoke(hdr, closeBtn, llLp2.newInstance(WRAP, WRAP));

            attachButtonTouch(hdr, null); // Header drag
            Object hdrLp = llLp2.newInstance(DASH_WIDTH, WRAP);
            setMargins(hdrLp, 0, 0, 0, 10);
            addView.invoke(eBox, hdr, hdrLp);

            // 2. Action Buttons Row
            Object btnRow = llCtor.newInstance(sysContext);
            setOri.invoke(btnRow, Integer.valueOf(0));

            Object bBefore = makeActionBtn(tvCtor, setGrav, setPad, "① BEFORE (순정)", 0xFF0C2D48, 0xFF38BDF8, 0xFFE0F2FE, new Runnable() {
                @Override
                public void run() {
                    startBenchmarkAsync("before", false);
                }
            });
            Object bAfter = makeActionBtn(tvCtor, setGrav, setPad, "② AFTER (최적화)", 0xFF064E3B, 0xFF10B981, 0xFFD1FAE5, new Runnable() {
                @Override
                public void run() {
                    startBenchmarkAsync("after", false);
                }
            });
            Object bQuick = makeActionBtn(tvCtor, setGrav, setPad, "⚡ 5초 빠른 진단", 0xFF451A03, 0xFFF59E0B, 0xFFFEF3C7, new Runnable() {
                @Override
                public void run() {
                    startBenchmarkAsync("quick", true);
                }
            });
            Object bExport = makeActionBtn(tvCtor, setGrav, setPad, "💾 USB 내보내기", 0xFF1E293B, 0xFF64748B, 0xFFE2E8F0, new Runnable() {
                @Override
                public void run() {
                    handleExport();
                }
            });

            Object bLp1 = llLp3.newInstance(0, WRAP, 1.0f); setMargins(bLp1, 0, 0, 10, 0);
            Object bLp2 = llLp3.newInstance(0, WRAP, 1.0f); setMargins(bLp2, 0, 0, 10, 0);
            Object bLp3 = llLp3.newInstance(0, WRAP, 1.0f); setMargins(bLp3, 0, 0, 10, 0);
            Object bLp4 = llLp3.newInstance(0, WRAP, 1.0f);
            addView.invoke(btnRow, bBefore, bLp1);
            addView.invoke(btnRow, bAfter, bLp2);
            addView.invoke(btnRow, bQuick, bLp3);
            addView.invoke(btnRow, bExport, bLp4);

            Object rowLp = llLp2.newInstance(DASH_WIDTH, WRAP);
            setMargins(rowLp, 0, 0, 0, 10);
            addView.invoke(eBox, btnRow, rowLp);

            // 3. Status Banner
            Object stTv = tvCtor.newInstance(sysContext);
            setTextStyle(stTv, currentStatus, 13f, 0xFFFDE68A, false);
            setRoundedBg(stTv, 0xFF0F172A, 0xFF334155, 1, 8f);
            setPad.invoke(stTv, 14, 9, 14, 9);
            statusBannerTv = stTv;
            Object stLp = llLp2.newInstance(DASH_WIDTH, WRAP);
            setMargins(stLp, 0, 0, 0, 10);
            addView.invoke(eBox, stTv, stLp);

            // 4. Main 2-Column Split Row (Left: Session Tweak Checkboxes, Right: Bench Table)
            Object splitRow = llCtor.newInstance(sysContext);
            setOri.invoke(splitRow, Integer.valueOf(0));

            // Left Card: Fixed height 330 with inner weighted ScrollView so layout never overflows 720p
            Object leftCard = llCtor.newInstance(sysContext);
            setOri.invoke(leftCard, Integer.valueOf(1));
            setRoundedBg(leftCard, 0xFF0F172A, 0xFF1E293B, 1, 8f);

            Object leftTop = llCtor.newInstance(sysContext);
            setOri.invoke(leftTop, Integer.valueOf(0));
            setPad.invoke(leftTop, 12, 8, 12, 6);
            Object leftTitleTv = tvCtor.newInstance(sysContext);
            setTextStyle(leftTitleTv, "⚡ 최적화 트윅 (현 시동 유지)", 13.5f, 0xFF38BDF8, true);
            addView.invoke(leftTop, leftTitleTv, llLp3.newInstance(0, WRAP, 1.0f));

            Object bulkOnBtn = tvCtor.newInstance(sysContext);
            setTextStyle(bulkOnBtn, "추천 일괄 ON", 12f, 0xFFD1FAE5, true);
            setGrav.invoke(bulkOnBtn, Integer.valueOf(17));
            setRoundedBg(bulkOnBtn, 0xFF064E3B, 0xFF10B981, 1, 6f);
            setPad.invoke(bulkOnBtn, 10, 5, 10, 5);
            attachTapClick(bulkOnBtn, new Runnable() {
                @Override
                public void run() {
                    runBulkTweaksAsync(true);
                }
            });
            Object bOnLp = llLp2.newInstance(WRAP, WRAP);
            setMargins(bOnLp, 6, 0, 6, 0);
            addView.invoke(leftTop, bulkOnBtn, bOnLp);

            Object bulkOffBtn = tvCtor.newInstance(sysContext);
            setTextStyle(bulkOffBtn, "전체 해제", 12f, 0xFFE2E8F0, true);
            setGrav.invoke(bulkOffBtn, Integer.valueOf(17));
            setRoundedBg(bulkOffBtn, 0xFF1E293B, 0xFF64748B, 1, 6f);
            setPad.invoke(bulkOffBtn, 10, 5, 10, 5);
            attachTapClick(bulkOffBtn, new Runnable() {
                @Override
                public void run() {
                    runBulkTweaksAsync(false);
                }
            });
            addView.invoke(leftTop, bulkOffBtn, llLp2.newInstance(WRAP, WRAP));
            addView.invoke(leftCard, leftTop, llLp2.newInstance(MATCH, WRAP));

            Object leftSv = svCtor.newInstance(sysContext);
            Object tweakCont = llCtor.newInstance(sysContext);
            setOri.invoke(tweakCont, Integer.valueOf(1));
            setPad.invoke(tweakCont, 10, 4, 10, 8);
            tweakListContainer = tweakCont;
            addView.invoke(leftSv, tweakCont, flLp2.newInstance(MATCH, WRAP));
            addView.invoke(leftCard, leftSv, llLp3.newInstance(MATCH, 0, 1.0f));
            addView.invoke(splitRow, leftCard, llLp2.newInstance(LEFT_WIDTH, 330));

            Object spacerView = viewCls.getConstructor(ctxCls).newInstance(sysContext);
            addView.invoke(splitRow, spacerView, llLp2.newInstance(GAP_WIDTH, 1));

            // Right Card: Comparison Table / Metrics ScrollView
            Object sv = svCtor.newInstance(sysContext);
            setRoundedBg(sv, 0xFF0F172A, 0xFF1E293B, 1, 8f);
            Object tCont = llCtor.newInstance(sysContext);
            setOri.invoke(tCont, Integer.valueOf(1));
            setPad.invoke(tCont, 12, 10, 12, 10);
            tableContainer = tCont;
            addView.invoke(sv, tCont, flLp2.newInstance(MATCH, WRAP));
            addView.invoke(splitRow, sv, llLp2.newInstance(RIGHT_WIDTH, 330));

            Object splitLp = llLp2.newInstance(DASH_WIDTH, 330);
            setMargins(splitLp, 0, 0, 0, 8);
            addView.invoke(eBox, splitRow, splitLp);

            // 5. Footer
            Object fTv = tvCtor.newInstance(sysContext);
            setTextStyle(fTv, "헤더 드래그: 창 이동 | 체크한 트윅은 ✕ 종료 후에도 이번 시동 중 유지되며 재부팅 시 100% 순정 복귀됩니다.", 12f, 0xFF64748B, false);
            footerTv = fTv;
            addView.invoke(eBox, fTv, llLp2.newInstance(DASH_WIDTH, WRAP));

            addView.invoke(root, eBox, llLp2.newInstance(WRAP, WRAP));
            return new Object[]{root, cBox, cTv, eBox};
        }

        private static Object makeActionBtn(
                Constructor<?> tvCtor,
                Method setGrav,
                Method setPad,
                String label,
                int bg,
                int stroke,
                int txtColor,
                Runnable onTap) throws Exception {
            Object btn = tvCtor.newInstance(sysContext);
            setTextStyle(btn, label, 14f, txtColor, true);
            setGrav.invoke(btn, Integer.valueOf(17));
            setRoundedBg(btn, bg, stroke, 1, 8f);
            setPad.invoke(btn, 10, 13, 10, 13);
            attachButtonTouch(btn, onTap);
            return btn;
        }

        private static void applyExpandStateOnUiThread() {
            try {
                Class<?> viewCls = Class.forName("android.view.View");
                Method setVis = viewCls.getMethod("setVisibility", int.class);
                if (compactBox != null) {
                    setVis.invoke(compactBox, Integer.valueOf(isExpanded ? 8 : 0));
                }
                if (expandedBox != null) {
                    setVis.invoke(expandedBox, Integer.valueOf(isExpanded ? 0 : 8));
                }
                updateCompactTextOnUiThread();
            } catch (Throwable ignored) {
            }
        }

        private static void updateCompactTextOnUiThread() {
            try {
                if (compactTv == null) return;
                String txt = isRunning
                        ? ("⏱ " + currentStatus)
                        : "📊 HKG 5W SysBench (터치하여 열기)";
                Class.forName("android.widget.TextView").getMethod("setText", CharSequence.class).invoke(compactTv, txt);
            } catch (Throwable ignored) {
            }
        }

        private static void refreshContentOnUiThread() {
            try {
                String plat = KernelCollectors.readBoardPlatform().toUpperCase(Locale.US);
                int up = (int) KernelCollectors.readUptimeSeconds();
                File usbDir = KernelCollectors.findMountedUsbBenchDir();
                String usbBadge = (usbDir != null) ? "USB 연결됨" : "USB 미연결 (내부 저장)";
                String probeStatus = "unknown";
                String probeRaw = KernelCollectors.readFileUtf8(new File(resultsDir, "boot_kernel_probe.txt").getAbsolutePath());
                if (probeRaw.contains("SMP_AFFINITY_WRITABLE=yes")) probeStatus = "IRQ분산=가능";
                else if (probeRaw.contains("SMP_AFFINITY_WRITABLE=no")) probeStatus = "IRQ분산=불가";

                int activeCnt = TweakManager.countActive(sessionState);
                Class<?> tvCls = Class.forName("android.widget.TextView");
                Method setText = tvCls.getMethod("setText", CharSequence.class);
                if (headerPlatTv != null) {
                    setText.invoke(headerPlatTv, plat + "  ·  uptime " + up + "s  ·  활성 트윅 "
                            + activeCnt + "/" + TweakManager.TWEAK_DEFS.length + " (재부팅 시 순정)  ·  "
                            + usbBadge + "  ·  " + probeStatus);
                }
                if (statusBannerTv != null) {
                    setText.invoke(statusBannerTv, currentStatus);
                }
                if (tweakListContainer != null) {
                    rebuildTweakRowsOnUiThread();
                }
                if (tableContainer != null) {
                    rebuildComparisonRowsOnUiThread();
                }
            } catch (Throwable ignored) {
            }
        }

        private static void rebuildTweakRowsOnUiThread() throws Exception {
            Class<?> ctxCls = Class.forName("android.content.Context");
            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> vgCls = Class.forName("android.view.ViewGroup");
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> llCls = Class.forName("android.widget.LinearLayout");
            Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
            Class<?> tvCls = Class.forName("android.widget.TextView");

            vgCls.getMethod("removeAllViews").invoke(tweakListContainer);

            Constructor<?> llCtor = llCls.getConstructor(ctxCls);
            Constructor<?> tvCtor = tvCls.getConstructor(ctxCls);
            Constructor<?> llLp2 = llLpCls.getConstructor(int.class, int.class);
            Constructor<?> llLp3 = llLpCls.getConstructor(int.class, int.class, float.class);
            Method setOri = llCls.getMethod("setOrientation", int.class);
            Method setLlGrav = llCls.getMethod("setGravity", int.class);
            Method addView = vgCls.getMethod("addView", viewCls, vgLpCls);
            Method setPad = viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class);
            Method setGrav = tvCls.getMethod("setGravity", int.class);

            for (int i = 0; i < TweakManager.TWEAK_DEFS.length; i++) {
                TweakManager.TweakDef def = TweakManager.TWEAK_DEFS[i];
                final String tweakId = def.id;
                final boolean checked = sessionState != null && sessionState.isEnabled(tweakId);
                String note = (sessionState != null) ? sessionState.getNote(tweakId) : "순정";

                Object row = llCtor.newInstance(sysContext);
                setOri.invoke(row, Integer.valueOf(0));
                setLlGrav.invoke(row, Integer.valueOf(16)); // Gravity.CENTER_VERTICAL
                setPad.invoke(row, 10, 7, 10, 7);
                setRoundedBg(row,
                        checked ? 0xFF0C2924 : 0xFF141E33,
                        checked ? 0xFF10B981 : 0xFF28354E,
                        1, 7f);

                Object boxTv = tvCtor.newInstance(sysContext);
                setTextStyle(boxTv, checked ? "✓" : "", 14f, checked ? 0xFFFFFFFF : 0xFF94A3B8, true);
                setGrav.invoke(boxTv, Integer.valueOf(17));
                setRoundedBg(boxTv,
                        checked ? 0xFF059669 : 0xFF1E293B,
                        checked ? 0xFF34D399 : 0xFF64748B,
                        1, 5f);
                Object boxLp = llLp2.newInstance(34, 30);
                setMargins(boxLp, 0, 0, 10, 0);
                addView.invoke(row, boxTv, boxLp);

                Object textCol = llCtor.newInstance(sysContext);
                setOri.invoke(textCol, Integer.valueOf(1));
                Object tTv = tvCtor.newInstance(sysContext);
                setTextStyle(tTv, def.title, 13.5f, checked ? 0xFFA7F3D0 : 0xFFE2E8F0, true);
                addView.invoke(textCol, tTv, llLp2.newInstance(-1, -2));

                Object dTv = tvCtor.newInstance(sysContext);
                setTextStyle(dTv, def.desc + " · [" + note + "]", 11f, checked ? 0xFF6EE7B7 : 0xFF94A3B8, false);
                addView.invoke(textCol, dTv, llLp2.newInstance(-1, -2));

                addView.invoke(row, textCol, llLp3.newInstance(0, -2, 1.0f));
                attachTapClick(row, new Runnable() {
                    @Override
                    public void run() {
                        toggleTweakAsync(tweakId, !checked);
                    }
                });

                Object rowLp = llLp2.newInstance(-1, -2);
                setMargins(rowLp, 0, 0, 0, 5);
                addView.invoke(tweakListContainer, row, rowLp);
            }
        }

        private static void rebuildComparisonRowsOnUiThread() throws Exception {
            Class<?> ctxCls = Class.forName("android.content.Context");
            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> vgCls = Class.forName("android.view.ViewGroup");
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> llCls = Class.forName("android.widget.LinearLayout");
            Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
            Class<?> tvCls = Class.forName("android.widget.TextView");

            vgCls.getMethod("removeAllViews").invoke(tableContainer);

            Constructor<?> llCtor = llCls.getConstructor(ctxCls);
            Constructor<?> tvCtor = tvCls.getConstructor(ctxCls);
            Constructor<?> llLp2 = llLpCls.getConstructor(int.class, int.class);
            Constructor<?> llLp3 = llLpCls.getConstructor(int.class, int.class, float.class);
            Method setOri = llCls.getMethod("setOrientation", int.class);
            Method addView = vgCls.getMethod("addView", viewCls, vgLpCls);
            Method setPad = viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class);
            Method setGrav = tvCls.getMethod("setGravity", int.class);

            BenchReport.BenchResult b = beforeRes;
            BenchReport.BenchResult a = afterRes;
            BenchReport.BenchResult q = quickRes;

            if (b == null && a == null && q == null) {
                Object emptyTv = tvCtor.newInstance(sysContext);
                setTextStyle(emptyTv,
                        "아직 측정 기록이 없습니다.\n\n"
                        + "1) 순정 상태에서 [① BEFORE (순정)] 을 실행하세요.\n"
                        + "   (초기 아이들 I/O, 스케줄러 대기지연, 스크롤 Jank, 커널 튜닝값을 자동 수집)\n"
                        + "2) 추후 최적화 패키지를 설치한 뒤 [② AFTER (최적화)] 를 실행하면\n"
                        + "   같은 조건에서 전후 변화율(%)과 HTML 비교 리포트가 USB에 자동 저장됩니다.",
                        14f, 0xFF94A3B8, false);
                addView.invoke(tableContainer, emptyTv, llLp2.newInstance(-1, -2));
                return;
            }

            if (b != null && a != null) {
                String caveat = BenchReport.buildCaveatNotice(b, a);
                if (!caveat.isEmpty()) {
                    Object cavTv = tvCtor.newInstance(sysContext);
                    setTextStyle(cavTv, caveat, 13f, 0xFFFDE68A, false);
                    setPad.invoke(cavTv, 12, 4, 16, 10);
                    addView.invoke(tableContainer, cavTv, llLp2.newInstance(-1, -2));
                }
                addTableHeader(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav,
                        "측정 항목", "BEFORE (순정)", "AFTER (최적화)", "변화율");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 0,
                        "스크롤 UI 대기지연 (ms/s)", b.activeUiRunDelayMsPerSec, a.activeUiRunDelayMsPerSec, true, "%.2f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 1,
                        "아이들 UI 대기지연 (ms/s)", b.idleUiRunDelayMsPerSec, a.idleUiRunDelayMsPerSec, true, "%.2f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 2,
                        "스크롤 프레임 Jank (%)", b.sfJankPct, a.sfJankPct, true, "%.1f%%");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 3,
                        "스크롤 P99 프레임 (ms)", b.sfP99FrameMs, a.sfP99FrameMs, true, "%.2f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 4,
                        "스크롤 최악 프레임 Max (ms)", b.sfMaxFrameMs, a.sfMaxFrameMs, true, "%.2f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 5,
                        "앱 진입 평균 WaitTime (ms)", b.launchAvgWaitMs, a.launchAvgWaitMs, true, "%.1f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 6,
                        "Binder IPC P99 (us)", b.binderP99Us, a.binderP99Us, true, "%.1f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 7,
                        "아이들 eMMC 쓰기 (KB/min)", b.idleDiskWriteKbPerMin, a.idleDiskWriteKbPerMin, true, "%.1f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 8,
                        "아이들 IOwait (ms/min)", b.idleIowaitMsPerMin, a.idleIowaitMsPerMin, true, "%.1f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 9,
                        "CPU0 주변장치 IRQ 비중 (%)", b.activeCpu0PeriphIrqPct, a.activeCpu0PeriphIrqPct, true, "%.1f%%");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 10,
                        "KSM(ksmd) CPU (ms/min)", b.idleKsmdCpuMsPerMin, a.idleKsmdCpuMsPerMin, true, "%.1f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 11,
                        "로거 데몬 CPU (ms/min)", b.idleLoggerCpuMsPerMin, a.idleLoggerCpuMsPerMin, true, "%.1f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 12,
                        "가용 메모리 (MB)", b.memAvailKb / 1024.0, a.memAvailKb / 1024.0, false, "%.1f");
                addCompareRow(llCtor, tvCtor, llLp2, llLp3, setOri, addView, setPad, setGrav, 13,
                        "상주 유저 프로세스 수", b.processCount, a.processCount, true, "%.0f");

                StringBuilder diagSb = new StringBuilder();
                diagSb.append("• 적용 트윅 — BEFORE: ").append(b.activeTweaks)
                      .append("  |  AFTER: ").append(a.activeTweaks);
                if (!b.idleTopCpuProcs.isEmpty()) {
                    diagSb.append("\n• [BEFORE 아이들 CPU 상위] ").append(b.idleTopCpuProcs);
                }
                if (!a.idleTopCpuProcs.isEmpty()) {
                    diagSb.append("\n• [AFTER 아이들 CPU 상위] ").append(a.idleTopCpuProcs);
                }
                Object diagTv = tvCtor.newInstance(sysContext);
                setTextStyle(diagTv, diagSb.toString(), 12f, 0xFF94A3B8, false);
                setPad.invoke(diagTv, 10, 10, 12, 8);
                addView.invoke(tableContainer, diagTv, llLp2.newInstance(-1, -2));
            } else {
                BenchReport.BenchResult latest = (a != null) ? a : ((b != null) ? b : q);
                String txt = BenchReport.buildTextSummary(b, a, latest);
                Object sumTv = tvCtor.newInstance(sysContext);
                setTextStyle(sumTv, txt, 13.5f, 0xFFE2E8F0, false);
                addView.invoke(tableContainer, sumTv, llLp2.newInstance(-1, -2));
            }
        }

        private static void addTableHeader(
                Constructor<?> llCtor, Constructor<?> tvCtor,
                Constructor<?> llLp2, Constructor<?> llLp3,
                Method setOri, Method addView, Method setPad, Method setGrav,
                String c1, String c2, String c3, String c4) throws Exception {
            Object row = llCtor.newInstance(sysContext);
            setOri.invoke(row, Integer.valueOf(0));
            setPad.invoke(row, 10, 7, 12, 7);
            addCell(tvCtor, llLp3, addView, setGrav, row, c1, 2.5f, 0xFF94A3B8, 3, true);
            addCell(tvCtor, llLp3, addView, setGrav, row, c2, 1.05f, 0xFF94A3B8, 5, true);
            addCell(tvCtor, llLp3, addView, setGrav, row, c3, 1.05f, 0xFF94A3B8, 5, true);
            addCell(tvCtor, llLp3, addView, setGrav, row, c4, 1.25f, 0xFF94A3B8, 5, true);
            addView.invoke(tableContainer, row, llLp2.newInstance(-1, -2));
        }

        private static void addCompareRow(
                Constructor<?> llCtor, Constructor<?> tvCtor,
                Constructor<?> llLp2, Constructor<?> llLp3,
                Method setOri, Method addView, Method setPad, Method setGrav,
                int rowIdx, String label, double beforeVal, double afterVal, boolean lowerBetter, String fmt) throws Exception {
            Object row = llCtor.newInstance(sysContext);
            setOri.invoke(row, Integer.valueOf(0));
            setPad.invoke(row, 10, 7, 12, 7);
            if ((rowIdx % 2) == 0) {
                setRoundedBg(row, 0xFF142038, 0x00000000, 0, 6f);
            }
            String bStr = String.format(Locale.US, fmt, beforeVal);
            String aStr = String.format(Locale.US, fmt, afterVal);
            String dStr = BenchReport.computeDeltaText(beforeVal, afterVal, lowerBetter);
            int dColor = BenchReport.isImproved(beforeVal, afterVal, lowerBetter) ? 0xFF34D399
                    : (BenchReport.isWorse(beforeVal, afterVal, lowerBetter) ? 0xFFF87171 : 0xFF94A3B8);

            addCell(tvCtor, llLp3, addView, setGrav, row, label, 2.5f, 0xFFE2E8F0, 3, false);
            addCell(tvCtor, llLp3, addView, setGrav, row, bStr, 1.05f, 0xFFCBD5E1, 5, false);
            addCell(tvCtor, llLp3, addView, setGrav, row, aStr, 1.05f, 0xFFF8FAFC, 5, true);
            addCell(tvCtor, llLp3, addView, setGrav, row, dStr, 1.25f, dColor, 5, true);
            addView.invoke(tableContainer, row, llLp2.newInstance(-1, -2));
        }

        private static void addCell(
                Constructor<?> tvCtor, Constructor<?> llLp3, Method addView, Method setGrav,
                Object row, String text, float weight, int color, int grav, boolean bold) throws Exception {
            Object tv = tvCtor.newInstance(sysContext);
            setTextStyle(tv, text, 13f, color, bold);
            setGrav.invoke(tv, Integer.valueOf(grav));
            addView.invoke(row, tv, llLp3.newInstance(0, -2, weight));
        }

        private static void updateWindowPositionOnUiThread(int newX, int newY, boolean saveToConfig) {
            if (rootView == null || wmLp == null || windowManager == null) return;
            try {
                Class<?> viewCls = Class.forName("android.view.View");
                int w = ((Integer) viewCls.getMethod("getWidth").invoke(rootView)).intValue();
                int h = ((Integer) viewCls.getMethod("getHeight").invoke(rootView)).intValue();
                int maxX = (w > 0 && w < 1920) ? (1920 - w) : 500;
                int maxY = (h > 0 && h < 720) ? (720 - h) : 60;
                int cx = Math.max(0, Math.min(maxX, newX));
                int cy = Math.max(0, Math.min(maxY, newY));
                if (saveToConfig) {
                    config.x = cx;
                    config.y = cy;
                }
                Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
                wmLpCls.getField("x").setInt(wmLp, cx);
                wmLpCls.getField("y").setInt(wmLp, cy);
                Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
                Class<?> vmCls = Class.forName("android.view.ViewManager");
                vmCls.getMethod("updateViewLayout", viewCls, vgLpCls).invoke(windowManager, rootView, wmLp);
            } catch (Throwable ignored) {
            }
        }

        private static void setAlphaSafe(Class<?> viewCls, Object view, float alpha) {
            try {
                viewCls.getMethod("setAlpha", float.class).invoke(view, Float.valueOf(alpha));
            } catch (Throwable ignored) {
            }
        }

        private static void attachTapClick(final Object view, final Runnable onTap) throws Exception {
            final Class<?> viewCls = Class.forName("android.view.View");
            try {
                viewCls.getMethod("setSoundEffectsEnabled", boolean.class).invoke(view, Boolean.FALSE);
                viewCls.getMethod("setHapticFeedbackEnabled", boolean.class).invoke(view, Boolean.FALSE);
                viewCls.getMethod("setImportantForAccessibility", int.class).invoke(view, Integer.valueOf(4));
            } catch (Throwable ignored) {
            }
            viewCls.getMethod("setClickable", boolean.class).invoke(view, Boolean.TRUE);
            Class<?> tlCls = Class.forName("android.view.View$OnTouchListener");
            final float cancelSlop = 28f;
            Object proxy = Proxy.newProxyInstance(
                    tlCls.getClassLoader(),
                    new Class<?>[]{tlCls},
                    new InvocationHandler() {
                        private float downRawX = 0f;
                        private float downRawY = 0f;
                        private boolean canceled = false;

                        @Override
                        public Object invoke(Object p, Method m, Object[] args) {
                            String name = m.getName();
                            if ("equals".equals(name)) return Boolean.valueOf(args != null && args.length == 1 && p == args[0]);
                            if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(p));
                            if ("toString".equals(name)) return "SysBenchTapTouch";
                            if ("onTouch".equals(name) && args != null && args.length >= 2 && args[1] != null) {
                                try {
                                    Object me = args[1];
                                    Class<?> meCls = me.getClass();
                                    int act = ((Integer) meCls.getMethod("getAction").invoke(me)).intValue() & 0xFF;
                                    float rx = ((Float) meCls.getMethod("getRawX").invoke(me)).floatValue();
                                    float ry = ((Float) meCls.getMethod("getRawY").invoke(me)).floatValue();
                                    if (act == 0) { // ACTION_DOWN
                                        downRawX = rx;
                                        downRawY = ry;
                                        canceled = false;
                                        setAlphaSafe(viewCls, view, 0.7f);
                                    } else if (act == 2) { // ACTION_MOVE
                                        if (!canceled && (Math.abs(rx - downRawX) > cancelSlop || Math.abs(ry - downRawY) > cancelSlop)) {
                                            canceled = true;
                                            setAlphaSafe(viewCls, view, 1.0f);
                                        }
                                    } else if (act == 1) { // ACTION_UP
                                        setAlphaSafe(viewCls, view, 1.0f);
                                        if (!canceled && onTap != null) {
                                            onTap.run();
                                        }
                                    } else if (act == 3) { // ACTION_CANCEL
                                        canceled = true;
                                        setAlphaSafe(viewCls, view, 1.0f);
                                    }
                                } catch (Throwable ignored) {
                                }
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        }
                    }
            );
            viewCls.getMethod("setOnTouchListener", tlCls).invoke(view, proxy);
        }

        private static void attachButtonTouch(Object view, final Runnable onTap) throws Exception {
            Class<?> viewCls = Class.forName("android.view.View");
            try {
                viewCls.getMethod("setSoundEffectsEnabled", boolean.class).invoke(view, Boolean.FALSE);
                viewCls.getMethod("setHapticFeedbackEnabled", boolean.class).invoke(view, Boolean.FALSE);
            } catch (Throwable ignored) {
            }
            viewCls.getMethod("setClickable", boolean.class).invoke(view, Boolean.TRUE);
            Class<?> tlCls = Class.forName("android.view.View$OnTouchListener");
            Object proxy = Proxy.newProxyInstance(
                    tlCls.getClassLoader(),
                    new Class<?>[]{tlCls},
                    new InvocationHandler() {
                        private float downX = 0f;
                        private float downY = 0f;
                        private int startX = 0;
                        private int startY = 0;
                        private boolean dragging = false;

                        @Override
                        public Object invoke(Object p, Method m, Object[] args) {
                            String name = m.getName();
                            if ("equals".equals(name)) return Boolean.valueOf(args != null && args.length == 1 && p == args[0]);
                            if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(p));
                            if ("toString".equals(name)) return "SysBenchTouch";
                            if ("onTouch".equals(name) && args != null && args.length >= 2 && args[1] != null) {
                                try {
                                    Object me = args[1];
                                    Class<?> meCls = me.getClass();
                                    int act = ((Integer) meCls.getMethod("getAction").invoke(me)).intValue() & 0xFF;
                                    float rx = ((Float) meCls.getMethod("getRawX").invoke(me)).floatValue();
                                    float ry = ((Float) meCls.getMethod("getRawY").invoke(me)).floatValue();
                                    if (act == 0) {
                                        downX = rx;
                                        downY = ry;
                                        startX = config.x;
                                        startY = config.y;
                                        dragging = false;
                                    } else if (act == 2) {
                                        int dx = Math.round(rx - downX);
                                        int dy = Math.round(ry - downY);
                                        if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                                            dragging = true;
                                        }
                                        if (dragging) {
                                            updateWindowPositionOnUiThread(startX + dx, startY + dy, true);
                                        }
                                    } else if (act == 1 || act == 3) {
                                        if (dragging) {
                                            dragging = false;
                                            saveCoords(installDir, config);
                                        } else if (act == 1 && onTap != null) {
                                            onTap.run();
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        }
                    }
            );
            viewCls.getMethod("setOnTouchListener", tlCls).invoke(view, proxy);
        }

        private static void setTextStyle(Object tv, String text, float sizeSp, int color, boolean bold) throws Exception {
            Class<?> tvCls = Class.forName("android.widget.TextView");
            tvCls.getMethod("setText", CharSequence.class).invoke(tv, text);
            tvCls.getMethod("setTextSize", int.class, float.class).invoke(tv, Integer.valueOf(2), Float.valueOf(sizeSp));
            tvCls.getMethod("setTextColor", int.class).invoke(tv, Integer.valueOf(color));
            if (bold) {
                Class<?> tfCls = Class.forName("android.graphics.Typeface");
                Object defBold = tfCls.getField("DEFAULT_BOLD").get(null);
                tvCls.getMethod("setTypeface", tfCls, int.class).invoke(tv, defBold, Integer.valueOf(1));
            }
        }

        private static void setRoundedBg(Object view, int fill, int stroke, int strokeW, float rad) throws Exception {
            Class<?> gdCls = Class.forName("android.graphics.drawable.GradientDrawable");
            Class<?> drCls = Class.forName("android.graphics.drawable.Drawable");
            Class<?> viewCls = Class.forName("android.view.View");
            Object gd = gdCls.getConstructor().newInstance();
            gdCls.getMethod("setColor", int.class).invoke(gd, Integer.valueOf(fill));
            gdCls.getMethod("setCornerRadius", float.class).invoke(gd, Float.valueOf(rad));
            gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, Integer.valueOf(strokeW), Integer.valueOf(stroke));
            viewCls.getMethod("setBackgroundDrawable", drCls).invoke(view, gd);
        }

        private static void setMargins(Object lp, int l, int t, int r, int b) throws Exception {
            Class<?> mlpCls = Class.forName("android.view.ViewGroup$MarginLayoutParams");
            mlpCls.getMethod("setMargins", int.class, int.class, int.class, int.class)
                  .invoke(lp, Integer.valueOf(l), Integer.valueOf(t), Integer.valueOf(r), Integer.valueOf(b));
        }
    }

    // =========================================================================
    // HOST SELF-TEST SUITE (Runs on PC JDK during build_package.py)
    // =========================================================================

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Self-test failed: " + msg);
        }
    }

    private static void assertClose(double actual, double expected, double tol, String msg) {
        if (Math.abs(actual - expected) > tol) {
            throw new AssertionError("Self-test failed (" + msg + "): expected=" + expected + ", actual=" + actual);
        }
    }

    private static void runSelfTests() {
        // 1. /proc/stat parser & delta test
        String stat0 = "cpu  1000 100 500 8000 200 50 150 0 0 0\n"
                + "cpu0 300 20 200 1800 100 40 140 0 0 0\n"
                + "cpu1 250 20 100 2100 30 5 5 0 0 0\n"
                + "ctxt 10000\n";
        String stat1 = "cpu  1200 100 600 8500 250 70 180 0 0 0\n"
                + "cpu0 380 20 240 1900 120 55 165 0 0 0\n"
                + "cpu1 290 20 120 2200 40 7 8 0 0 0\n"
                + "ctxt 15000\n";
        KernelCollectors.CpuSnapshot cs0 = KernelCollectors.CpuSnapshot.parse(stat0);
        KernelCollectors.CpuSnapshot cs1 = KernelCollectors.CpuSnapshot.parse(stat1);
        KernelCollectors.CpuDelta cd = KernelCollectors.CpuDelta.compute(cs0, cs1, 10.0);
        // totDiff = 200+100+500+50+20+30 = 900; actDiff = 200+100+20+30 = 350 -> 38.88%
        assertClose(cd.totalBusyPct, 38.89, 0.1, "cpu totalBusyPct");
        // iowait = 50/900 = 5.55%, 50 jiffies * 10ms * 60 / 10s = 3000 ms/min
        assertClose(cd.iowaitMsPerMin, 3000.0, 1.0, "cpu iowaitMsPerMin");
        assertTrue(cd.coreBusyPct.length == 2, "coreBusyPct count");

        // 2. /proc/interrupts parser (real Niro EV & EV6 formats)
        String irq0Raw = "            CPU0       CPU1       CPU2       CPU3       \n"
                + " 27:    1000000    1000000    1000000    1000000       GIC  27  arch_timer\n"
                + " 35:        100          0          0          0       GIC  35  mip4_ts\n"
                + " 52:      10000          0          0          0       GIC  52  TCC_MAIN_D, wmixer1\n"
                + " 76:     800000          0          0          0       GIC  76  tcc-sdhc0\n"
                + " 80:          0      10000          0          0       GIC  80  dwc_otg\n"
                + "IPI2:     500000     500000     500000     500000  Rescheduling interrupts\n"
                + "Err:          0\n";
        String irq1Raw = "            CPU0       CPU1       CPU2       CPU3       \n"
                + " 27:    1005000    1005000    1005000    1005000       GIC  27  arch_timer\n"
                + " 35:        150          0          0          0       GIC  35  mip4_ts\n"
                + " 52:      10600          0          0          0       GIC  52  TCC_MAIN_D, wmixer1\n"
                + " 76:     801000          0          0          0       GIC  76  tcc-sdhc0\n"
                + " 80:          0      10350          0          0       GIC  80  dwc_otg\n"
                + "IPI2:     502000     502000     502000     502000  Rescheduling interrupts\n"
                + "Err:          0\n";
        KernelCollectors.IrqDelta id = KernelCollectors.IrqDelta.compute(
                KernelCollectors.IrqSnapshot.parse(irq0Raw),
                KernelCollectors.IrqSnapshot.parse(irq1Raw)
        );
        // Peripheral deltas: 50 + 600 + 1000 (cpu0=1650) + 350 (cpu1=350) = 2000 total -> 82.5% CPU0
        assertTrue(id.peripheralTotalDelta == 2000L, "peripheralTotalDelta=" + id.peripheralTotalDelta);
        assertClose(id.cpu0PeripheralSharePct, 82.5, 0.01, "cpu0PeripheralSharePct");
        assertTrue(id.touchDelta == 50L && id.displayDelta == 600L && id.emmcDelta == 1000L && id.usbDelta == 350L, "irq device buckets");

        // 3. /proc/diskstats mmcblk0 delta
        String ds0 = " 179       0 mmcblk0 1000 0 20000 500 500 0 4000 1000 0 800 1500\n";
        String ds1 = " 179       0 mmcblk0 1010 0 20200 520 560 0 4800 1300 0 1100 1820\n";
        KernelCollectors.DiskDelta dd = KernelCollectors.DiskDelta.compute(
                KernelCollectors.DiskSnapshot.parse(ds0, "mmcblk0"),
                KernelCollectors.DiskSnapshot.parse(ds1, "mmcblk0"),
                30.0
        );
        // dWrSec = 800 -> 400 KB in 30s -> 800 KB/min; dIoTicks = 300ms -> 600 ms/min
        assertClose(dd.writeKbPerMin, 800.0, 0.1, "writeKbPerMin");
        assertClose(dd.ioBusyMsPerMin, 600.0, 0.1, "ioBusyMsPerMin");

        // 4. /proc/<pid>/stat & schedstat parser (including parentheses/space in comm)
        KernelCollectors.ProcEntry pe = KernelCollectors.ProcSchedSnapshot.parseProcStatLine(
                72, "", "72 (tcc spi) S 2 0 0 0 -1 2129984 0 0 3 0 15 25 0 0 20 0 1 0 100 0 0");
        assertTrue(pe != null && "tcc spi".equals(pe.name) && pe.majflt == 3L && pe.utimeJiffies == 15L && pe.stimeJiffies == 25L,
                "parseProcStatLine with space in comm");
        KernelCollectors.ProcSchedSnapshot.parseSchedstatLine("5000000 12000000 42", pe);
        assertTrue(pe.execNs == 5000000L && pe.runDelayNs == 12000000L && pe.timeslices == 42L, "parseSchedstatLine");

        KernelCollectors.ProcSchedSnapshot ps0 = new KernelCollectors.ProcSchedSnapshot();
        KernelCollectors.ProcSchedSnapshot ps1 = new KernelCollectors.ProcSchedSnapshot();
        KernelCollectors.ProcEntry pA0 = KernelCollectors.ProcSchedSnapshot.parseProcStatLine(
                101, "com.heavy.bg", "101 (heavy) S 1 0 0 0 -1 0 0 0 0 0 100 50 0 0 20 0 1 0 100 0 0");
        KernelCollectors.ProcEntry pA1 = KernelCollectors.ProcSchedSnapshot.parseProcStatLine(
                101, "com.heavy.bg", "101 (heavy) S 1 0 0 0 -1 0 0 0 0 0 180 70 0 0 20 0 1 0 100 0 0");
        pA0.bucket = ""; pA1.bucket = "";
        KernelCollectors.ProcEntry pB0 = KernelCollectors.ProcSchedSnapshot.parseProcStatLine(
                102, "com.light.fg", "102 (light) S 1 0 0 0 -1 0 0 0 0 0 10 5 0 0 20 0 1 0 100 0 0");
        KernelCollectors.ProcEntry pB1 = KernelCollectors.ProcSchedSnapshot.parseProcStatLine(
                102, "com.light.fg", "102 (light) S 1 0 0 0 -1 0 0 0 0 0 20 15 0 0 20 0 1 0 100 0 0");
        pB0.bucket = ""; pB1.bucket = "";
        ps0.entries.put(Integer.valueOf(101), pA0); ps1.entries.put(Integer.valueOf(101), pA1);
        ps0.entries.put(Integer.valueOf(102), pB0); ps1.entries.put(Integer.valueOf(102), pB1);
        KernelCollectors.ProcSchedDelta pdTop = KernelCollectors.ProcSchedDelta.compute(ps0, ps1, 10.0, 4);
        assertTrue(pdTop.topCpuProcs.startsWith("com.heavy.bg(101)=2.5%")
                && pdTop.topCpuProcs.contains("com.light.fg(102)=0.5%"),
                "topCpuProcs=" + pdTop.topCpuProcs);

        // 5. am start -W, focused window, and SurfaceFlinger --latency parser
        double waitMs = ScenarioRunner.parseAmStartWaitTimeMs("Starting: Intent\nStatus: ok\nThisTime: 180\nTotalTime: 180\nWaitTime: 245\nComplete\n");
        assertClose(waitMs, 245.0, 0.01, "parseAmStartWaitTimeMs");
        double thisFallbackMs = ScenarioRunner.parseAmStartWaitTimeMs("Starting: Intent\nStatus: ok\nThisTime: 132\nComplete\n");
        assertClose(thisFallbackMs, 132.0, 0.01, "parseAmStartWaitTimeMs ThisTime fallback");

        String focus = ScenarioRunner.parseFocusedWindowName(
                "  mCurrentFocus=Window{425c11e8 u0 com.android.settings/com.android.settings.Settings}\n");
        assertTrue("com.android.settings/com.android.settings.Settings".equals(focus), "parseFocusedWindowName=" + focus);

        String sfRaw = "16666666\n"
                + "0\t0\t0\n"
                + "1000000000\t1000000000\t1005000000\n"
                + "1016666666\t1016666666\t1021666666\n" // 16.67ms (ok)
                + "1033333333\t1033333333\t1039000000\n" // 16.67ms (ok)
                + "1050000000\t1066666666\t1075000000\n" // 33.33ms (jank!)
                + "1083333333\t1083333333\t1088000000\n" // 16.67ms (ok)
                + "2000000000\t2000000000\t2005000000\n" // 916ms gap (filtered out >450ms!)
                + "2016666666\t2016666666\t2021000000\n" // 16.67ms (ok)
                + "2050000000\t2050000000\t2055000000\n" // 33.33ms (jank!)
                + "2066666666\t9223372036854775807\t9223372036854775807\n";
        ScenarioRunner.SfLatencyStats sf = ScenarioRunner.parseSurfaceFlingerLatency(sfRaw);
        // Valid consecutive deltas (excluding >450ms gap): 16.67, 16.67, 33.33, 16.67, 16.67, 33.33 -> 6 frames, 2 janks -> 33.33%
        assertTrue(sf.frameCount == 6 && sf.jankCount == 2, "sf frameCount=" + sf.frameCount + ", jankCount=" + sf.jankCount);
        assertClose(sf.jankPct, 33.33, 0.2, "sf jankPct");

        // 6. JSON round-trip & HTML/text report test
        BenchReport.BenchResult b = new BenchReport.BenchResult();
        b.slot = "before";
        b.platform = "tcc897x";
        b.product2 = "KIA.KOR.S5W_M.V039.001.260902";
        b.uptimeSec = 140.5;
        b.animWindowScale = 1.0;
        b.animTransitionScale = 1.0;
        b.animDurationScale = 1.0;
        b.activeUiRunDelayMsPerSec = 18.5;
        b.sfJankPct = 24.0;
        b.idleDiskWriteKbPerMin = 950.0;
        b.activeCpu0PeriphIrqPct = 98.5;
        b.launchDetail = "settings=245ms, \"quoted\"=120ms";
        b.smpAffinityWritable = "yes";
        b.activeTweaks = "loggers,ksm,vm_io";
        b.idleTopCpuProcs = "tcc_logd(501)=4.2%, Vrm.main(602)=3.1%";
        b.activeTopCpuProcs = "surfaceflinger(120)=8.0%, GeneralSettings(900)=6.4%";

        String json = b.toJson();
        BenchReport.BenchResult parsed = BenchReport.BenchResult.fromJson(json);
        assertTrue(parsed != null && "before".equals(parsed.slot) && b.launchDetail.equals(parsed.launchDetail)
                && "loggers,ksm,vm_io".equals(parsed.activeTweaks)
                && b.idleTopCpuProcs.equals(parsed.idleTopCpuProcs)
                && b.activeTopCpuProcs.equals(parsed.activeTopCpuProcs),
                "JSON roundtrip failed");
        assertClose(parsed.activeUiRunDelayMsPerSec, 18.5, 0.001, "JSON numeric roundtrip");

        BenchReport.BenchResult a = BenchReport.BenchResult.fromJson(json);
        a.slot = "after";
        a.activeUiRunDelayMsPerSec = 6.2;
        a.sfJankPct = 8.0;
        a.idleDiskWriteKbPerMin = 120.0;
        a.activeCpu0PeriphIrqPct = 38.0;
        a.animTransitionScale = 0.0;

        String txt = BenchReport.buildTextSummary(parsed, a, a);
        String html = BenchReport.buildHtmlReport(parsed, a, a);
        assertTrue(txt.contains("개선") && txt.contains("분리 해석"), "Summary delta & caveat check");
        assertTrue(html.contains("HKG Gen5W SysBench") && html.contains("BEFORE") && html.contains("AFTER"), "HTML report check");

        // 7. TweakManager IRQ classifier & Boot-ID session-only reset test
        assertTrue(TweakManager.classifyIrqTargetCpu("TCC-WDT", 4) == -1, "Protect WDT IRQ");
        assertTrue(TweakManager.classifyIrqTargetCpu("arch_timer", 4) == -1, "Protect arch_timer");
        assertTrue(TweakManager.classifyIrqTargetCpu("uart0", 4) == -1, "Protect MICOM UART");
        assertTrue(TweakManager.classifyIrqTargetCpu("mip4_ts", 4) == 1, "Touch IRQ -> CPU1");
        assertTrue(TweakManager.classifyIrqTargetCpu("Mali_GP", 4) == 2, "GPU IRQ -> CPU2");
        assertTrue(TweakManager.classifyIrqTargetCpu("tcc-sdhc0", 4) == 3, "eMMC IRQ -> CPU3");

        TweakManager.SessionState st = new TweakManager.SessionState("boot-uuid-1111");
        st.enabled.put("ksm", Boolean.TRUE);
        st.enabled.put("vm_io", Boolean.TRUE);
        st.origSysfs.put("/proc/sys/vm/swappiness", "60");
        st.hasOrigAnim = true;
        st.origAnimWin = 1.0f;
        String stJson = st.toJson();

        TweakManager.SessionState sameBoot = TweakManager.SessionState.fromJson(stJson, "boot-uuid-1111");
        assertTrue(sameBoot.isEnabled("ksm") && sameBoot.isEnabled("vm_io")
                && "60".equals(sameBoot.origSysfs.get("/proc/sys/vm/swappiness"))
                && sameBoot.hasOrigAnim, "Same boot restores tweaks");

        TweakManager.SessionState afterReboot = TweakManager.SessionState.fromJson(stJson, "boot-uuid-2222");
        assertTrue(!afterReboot.isEnabled("ksm") && !afterReboot.isEnabled("vm_io")
                && afterReboot.origSysfs.isEmpty() && !afterReboot.hasOrigAnim
                && "boot-uuid-2222".equals(afterReboot.bootId), "Reboot resets all tweaks to stock");

        System.out.println("[SysBench] Host self-tests PASSED (7/7 suites OK)");
    }
}
