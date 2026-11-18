package com.hkg.optimize;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point and standalone HKGSysOptOverlay (zero-resident-RAM) for HKG 5W Optimize.
 * Built strictly with Java 7 constructs + pure Android API 19 reflection so host self-tests
 * compile and run cleanly on PC JDK without android.jar.
 */
public final class OptimizeMain {

    public static final String VERSION = "1.0.2";

    private static final int DASH_WIDTH = 1240;
    private static final int SCROLL_HEIGHT = 255;
    private static final float TOUCH_SLOP_PX = 28.0f;

    private static final int TYPE_VOLUME_OVERLAY = 2020;
    private static final int TYPE_SYSTEM_ALERT = 2003;
    private static final int WM_FLAGS = 0x00000028; // FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
    private static final int PIXEL_FORMAT_TRANSLUCENT = -3;
    private static final int GRAVITY_TOP_LEFT = 51;

    private String installDir;
    private OptModel.OptConfig config;
    private volatile boolean running = true;
    private volatile boolean busy = false;
    private String lastActionNote = "대기 중 (항목 선택 또는 원클릭 적용 가능)";

    private Object androidContext;
    private Object mainHandler;
    private Object windowManager;
    private Object wmParams;
    private Object rootView;

    private Object headerBadgeView;
    private Object telemetryView;
    private Object actionNoteView;
    private Object bootAutoBtn;
    private final Map<String, Object> rowToggleBtns = new LinkedHashMap<String, Object>();
    private final Map<String, Object> rowStateLabels = new LinkedHashMap<String, Object>();
    private final Map<String, Object> presetBtns = new LinkedHashMap<String, Object>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private OptimizeMain(String installDir) {
        this.installDir = OptModel.resolveInstallDir(installDir);
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            return;
        }
        String mode = args[0];
        if ("test-self".equals(mode)) {
            runSelfTests();
            return;
        }

        setProcessArgV0("com.hkg.optimize");
        OptModel.ensureTmpDir();

        if ("restore-anim".equals(mode)) {
            float w = args.length > 1 ? OptModel.parseFloatSafe(args[1], 1.0f) : 1.0f;
            float t = args.length > 2 ? OptModel.parseFloatSafe(args[2], 1.0f) : 1.0f;
            float d = args.length > 3 ? OptModel.parseFloatSafe(args[3], 1.0f) : 1.0f;
            Object ctx = initAndroidContextQuiet();
            boolean ok = OptEngine.writeAnimScales(ctx, w, t, d);
            System.out.println("restore-anim ok=" + ok + " scales=" + w + "/" + t + "/" + d);
            System.exit(0);
            return;
        }

        if ("apply-boot".equals(mode)) {
            String dir = OptModel.resolveInstallDir(args.length > 1 ? args[1] : null);
            OptModel.OptConfig cfg = OptModel.loadConfig(dir);
            if (!cfg.autoBoot) {
                System.out.println("apply-boot skipped (auto_boot=0)");
                System.exit(0);
                return;
            }
            Object ctx = initAndroidContextQuiet();
            String res = OptEngine.applyAllConfigured(dir, cfg, ctx);
            System.out.println("apply-boot done: " + res);
            System.exit(0);
            return;
        }

        if ("refresh-sched".equals(mode)) {
            String dir = OptModel.resolveInstallDir(args.length > 1 ? args[1] : null);
            OptModel.OptConfig cfg = OptModel.loadConfig(dir);
            if (!cfg.autoBoot) {
                System.out.println("refresh-sched skipped (auto_boot=0)");
                System.exit(0);
                return;
            }
            Object ctx = initAndroidContextQuiet();
            String res = OptEngine.refreshSchedOnly(dir, cfg, ctx);
            System.out.println("refresh-sched done: " + res);
            System.exit(0);
            return;
        }

        if ("cli".equals(mode)) {
            String sub = args.length > 1 ? args[1] : "status";
            String dir = OptModel.resolveInstallDir(args.length > 2 ? args[2] : null);
            OptModel.OptConfig cfg = OptModel.loadConfig(dir);
            Object ctx = initAndroidContextQuiet();
            if ("on".equals(sub)) {
                String res = OptEngine.applyAllConfigured(dir, cfg, ctx);
                System.out.println("CLI_ON: " + res);
            } else if ("off".equals(sub)) {
                String res = OptEngine.revertAllToStock(dir, cfg, ctx);
                System.out.println("CLI_OFF: " + res);
            } else {
                OptEngine.LiveStatus st = OptEngine.readLiveStatus(ctx);
                System.out.println(formatLiveTelemetryText(st));
            }
            System.exit(0);
            return;
        }

        if ("daemon".equals(mode)) {
            String dir = OptModel.resolveInstallDir(args.length > 1 ? args[1] : null);
            OptimizeMain app = new OptimizeMain(dir);
            app.runDaemon();
            return;
        }

        printUsage();
    }

    private static void printUsage() {
        System.out.println("HKG 5W Optimize v" + VERSION);
        System.out.println("Usage: OptimizeMain <test-self|daemon|cli <on|off|status>|apply-boot|refresh-sched|restore-anim>");
    }

    // =========================================================================
    // Daemon Lifecycle & Command Watcher
    // =========================================================================

    private void runDaemon() {
        OptModel.ensureTmpDir();
        try {
            Object pidObj = Class.forName("android.os.Process").getMethod("myPid").invoke(null);
            if (pidObj instanceof Number) {
                OptModel.writeFileUtf8(OptModel.PID_FILE, String.valueOf(((Number) pidObj).intValue()) + "\n");
            }
        } catch (Throwable ignored) {
        }
        prepareMainLooper();
        androidContext = initSystemContextOnMainThread();
        config = OptModel.loadConfig(installDir);
        mainHandler = createMainHandler();
        if (androidContext == null || mainHandler == null) {
            System.err.println("Failed to initialize Android Context/Handler");
            System.exit(1);
            return;
        }

        buildOverlayUi();
        attachOverlayWindow();
        startCommandWatcher();
        refreshLiveUiAsync();

        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Method loop = looperClass.getMethod("loop");
            loop.invoke(null);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void startCommandWatcher() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        File cmdFile = new File(OptModel.CMD_FILE);
                        if (cmdFile.isFile()) {
                            String raw = OptModel.readTrimmed(OptModel.CMD_FILE);
                            if (!raw.isEmpty()) {
                                cmdFile.delete();
                                handleExternalCommand(raw);
                            }
                        }
                        Thread.sleep(250L);
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "OptCmdWatcher");
        t.setDaemon(true);
        t.start();
    }

    private void handleExternalCommand(final String cmd) {
        if ("stop".equalsIgnoreCase(cmd)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    shutdownDaemon();
                }
            });
            return;
        }
        if ("apply".equalsIgnoreCase(cmd)) {
            runApplyAllAsync(false);
            return;
        }
        if ("revert".equalsIgnoreCase(cmd)) {
            runRevertAllAsync();
            return;
        }
        if ("toggle".equalsIgnoreCase(cmd)) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    if (rootView != null) {
                        int vis = getViewVisibility(rootView);
                        if (vis == 0) {
                            shutdownDaemon();
                        } else {
                            setViewVisibility(rootView, 0);
                            refreshLiveUiAsync();
                        }
                    }
                }
            });
            return;
        }
        // "show"
        postToUi(new Runnable() {
            @Override
            public void run() {
                if (rootView != null) {
                    setViewVisibility(rootView, 0);
                }
                refreshLiveUiAsync();
            }
        });
    }

    private void shutdownDaemon() {
        running = false;
        OptModel.saveConfig(installDir, config);
        if (rootView != null && windowManager != null) {
            try {
                Method rem = windowManager.getClass().getMethod("removeViewImmediate", Class.forName("android.view.View"));
                rem.invoke(windowManager, rootView);
            } catch (Throwable t) {
                try {
                    Method rem2 = windowManager.getClass().getMethod("removeView", Class.forName("android.view.View"));
                    rem2.invoke(windowManager, rootView);
                } catch (Throwable ignored) {
                }
            }
            rootView = null;
        }
        new File(OptModel.PID_FILE).delete();
        new File(OptModel.CMD_FILE).delete();
        worker.shutdownNow();
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Object mainL = looperClass.getMethod("getMainLooper").invoke(null);
            if (mainL != null) {
                looperClass.getMethod("quit").invoke(mainL);
            }
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }

    // =========================================================================
    // Async Actions
    // =========================================================================

    private void refreshLiveUiAsync() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final OptEngine.LiveStatus st = OptEngine.readLiveStatus(androidContext);
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        renderLiveStatus(st);
                    }
                });
            }
        });
    }

    private void runApplyAllAsync(final boolean closeAfter) {
        if (busy) {
            return;
        }
        busy = true;
        lastActionNote = "전체 최적화 적용 중...";
        postToUi(new Runnable() {
            @Override
            public void run() {
                updateNoteText();
            }
        });
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    OptModel.saveConfig(installDir, config);
                    String res = OptEngine.applyAllConfigured(installDir, config, androidContext);
                    lastActionNote = "적용 완료: " + shorten(res, 95);
                    final OptEngine.LiveStatus st = OptEngine.readLiveStatus(androidContext);
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            renderLiveStatus(st);
                            if (closeAfter) {
                                shutdownDaemon();
                            }
                        }
                    });
                } catch (Throwable t) {
                    busy = false;
                    lastActionNote = "적용 오류: " + t.getMessage();
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            updateNoteText();
                        }
                    });
                }
            }
        });
    }

    private void runRevertAllAsync() {
        if (busy) {
            return;
        }
        busy = true;
        lastActionNote = "순정 상태 원복 중...";
        postToUi(new Runnable() {
            @Override
            public void run() {
                updateNoteText();
            }
        });
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String res = OptEngine.revertAllToStock(installDir, config, androidContext);
                    lastActionNote = "원복 완료: " + shorten(res, 95);
                    final OptEngine.LiveStatus st = OptEngine.readLiveStatus(androidContext);
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            renderLiveStatus(st);
                        }
                    });
                } catch (Throwable t) {
                    busy = false;
                    lastActionNote = "원복 오류: " + t.getMessage();
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            updateNoteText();
                        }
                    });
                }
            }
        });
    }

    private void toggleSingleTweakAsync(final String key) {
        if (busy) {
            return;
        }
        busy = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    OptModel.BaselineState cur = OptModel.loadBaseline();
                    boolean currentlyApplied = cur != null && cur.activeTweaks.contains(key);
                    if (currentlyApplied) {
                        config.setEnabled(key, false);
                        OptModel.saveConfig(installDir, config);
                        String res = OptEngine.revertSingleTweak(installDir, key, config, androidContext);
                        lastActionNote = "[" + key + "] 해제/원복: " + shorten(res, 80);
                    } else {
                        config.setEnabled(key, true);
                        OptModel.saveConfig(installDir, config);
                        String res = OptEngine.applySingleTweak(installDir, key, config, androidContext);
                        lastActionNote = "[" + key + "] 적용: " + shorten(res, 80);
                    }
                    final OptEngine.LiveStatus st = OptEngine.readLiveStatus(androidContext);
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            renderLiveStatus(st);
                        }
                    });
                } catch (Throwable t) {
                    busy = false;
                    lastActionNote = "항목 전환 오류: " + t.getMessage();
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            updateNoteText();
                        }
                    });
                }
            }
        });
    }

    private void selectAnimPresetAsync(final String preset) {
        if (busy) {
            return;
        }
        config.animPreset = OptModel.normalizeAnimPreset(preset);
        config.setEnabled(OptModel.KEY_ANIM, true);
        OptModel.saveConfig(installDir, config);
        busy = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String res = OptEngine.applySingleTweak(installDir, OptModel.KEY_ANIM, config, androidContext);
                    lastActionNote = "애니메이션 배율 [" + config.animPreset + "x] 적용 (" + res + ")";
                    final OptEngine.LiveStatus st = OptEngine.readLiveStatus(androidContext);
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            renderLiveStatus(st);
                        }
                    });
                } catch (Throwable t) {
                    busy = false;
                    lastActionNote = "애니메이션 변경 오류: " + t.getMessage();
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            updateNoteText();
                        }
                    });
                }
            }
        });
    }

    private void toggleAutoBoot() {
        config.autoBoot = !config.autoBoot;
        OptModel.saveConfig(installDir, config);
        lastActionNote = config.autoBoot
                ? "시동 시 자동 적용: ON (재부팅 시 자동 최적화)"
                : "시동 시 자동 적용: OFF (부팅 시 순정 유지)";
        updateBootAutoButtonVisual();
        updateNoteText();
    }

    // =========================================================================
    // Overlay UI Builder (Pure Reflection, Compact <= 600px Total Height)
    // =========================================================================

    private void buildOverlayUi() {
        try {
            Object root = newLinearLayout(1, 0xFFF1F5F9); // VERTICAL
            setBackgroundRounded(root, 0xF50F172A, 18.0f, 2, 0xFF334155);
            setViewPadding(root, 18, 14, 18, 12);
            setImportantForAccessibilityNoHide(root);
            rootView = root;

            // 1. Header Bar (~54px)
            Object header = newLinearLayout(0, 0); // HORIZONTAL
            setGravityCenterVertical(header);
            setLayoutParamsLinear(header, -1, 52, 0, 0, 0, 8);

            Object titleLbl = newTextView("HKG 5W Optimize v" + VERSION, 21.0f, 0xFFF8FAFC, true);
            setLayoutParamsLinear(titleLbl, -2, -2, 0, 0, 12, 0);
            addView(header, titleLbl);

            Object ramPill = newTextView("RAM 0MB 무상주", 13.0f, 0xFF7DD3FC, true);
            setBackgroundRounded(ramPill, 0xFF0C4A6E, 10.0f, 1, 0xFF0284C7);
            setViewPadding(ramPill, 10, 4, 10, 4);
            setLayoutParamsLinear(ramPill, -2, -2, 0, 0, 10, 0);
            addView(header, ramPill);

            headerBadgeView = newTextView("상태 확인중", 13.0f, 0xFFE2E8F0, true);
            setBackgroundRounded(headerBadgeView, 0xFF1E293B, 10.0f, 1, 0xFF475569);
            setViewPadding(headerBadgeView, 12, 4, 12, 4);
            setLayoutParamsLinear(headerBadgeView, -2, -2, 0, 0, 10, 0);
            addView(header, headerBadgeView);

            Object dragHint = newTextView("≡ 드래그 이동", 13.0f, 0xFF94A3B8, false);
            setLayoutParamsLinearWeight(dragHint, 0, -2, 1.0f, 8, 0, 8, 0);
            addView(header, dragHint);

            Object refreshBtn = makeActionButton("↻ 새로고침", 0xFF1E293B, 0xFF475569, 0xFFE2E8F0, 14.0f);
            setLayoutParamsLinear(refreshBtn, 124, 42, 0, 0, 8, 0);
            attachTapClick(refreshBtn, new Runnable() {
                @Override
                public void run() {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            OptModel.BaselineState base = OptModel.loadBaseline();
                            if (base != null && base.activeTweaks.contains(OptModel.KEY_SCHED)) {
                                String msg = OptEngine.refreshSchedOnly(installDir, config, androidContext);
                                lastActionNote = "새로고침 + 스케줄러 재정렬: " + msg;
                            } else {
                                lastActionNote = "실시간 커널 상태 새로고침 완료";
                            }
                            final OptEngine.LiveStatus st = OptEngine.readLiveStatus(androidContext);
                            postToUi(new Runnable() {
                                @Override
                                public void run() {
                                    renderLiveStatus(st);
                                }
                            });
                        }
                    });
                }
            });
            addView(header, refreshBtn);

            Object closeBtn = makeActionButton("✕ 닫기", 0xFF7F1D1D, 0xFFDC2626, 0xFFFEE2E2, 14.0f);
            setLayoutParamsLinear(closeBtn, 96, 42, 0, 0, 0, 0);
            attachTapClick(closeBtn, new Runnable() {
                @Override
                public void run() {
                    shutdownDaemon();
                }
            });
            addView(header, closeBtn);

            attachHeaderDrag(header);
            addView(root, header);

            // 2. Live Telemetry Strip (~42px)
            telemetryView = newTextView("커널 상태 읽는 중...", 14.0f, 0xFFBAE6FD, false);
            setBackgroundRounded(telemetryView, 0xFF162238, 10.0f, 1, 0xFF1E3A5F);
            setViewPadding(telemetryView, 14, 8, 14, 8);
            setLayoutParamsLinear(telemetryView, -1, 42, 0, 0, 0, 8);
            setGravityCenterVertical(telemetryView);
            addView(root, telemetryView);

            // 3. ScrollView of 6 Tweak Cards (fixed height = 255px)
            Object scrollView = Class.forName("android.widget.ScrollView")
                    .getConstructor(Class.forName("android.content.Context"))
                    .newInstance(androidContext);
            setLayoutParamsLinear(scrollView, -1, SCROLL_HEIGHT, 0, 0, 0, 8);
            setBackgroundRounded(scrollView, 0xFF0B1324, 12.0f, 1, 0xFF1E293B);
            setViewPadding(scrollView, 10, 8, 10, 8);

            Object listBox = newLinearLayout(1, 0);
            setLayoutParamsLinear(listBox, -1, -2, 0, 0, 0, 0);
            List<OptModel.TweakDef> defs = OptModel.getTweakDefs();
            for (int i = 0; i < defs.size(); i++) {
                final OptModel.TweakDef def = defs.get(i);
                Object row = newLinearLayout(0, 0);
                setGravityCenterVertical(row);
                setBackgroundRounded(row, 0xFF172033, 10.0f, 1, 0xFF283548);
                setViewPadding(row, 12, 8, 12, 8);
                setLayoutParamsLinear(row, -1, 60, 0, 0, 0, (i == defs.size() - 1) ? 0 : 6);

                Object togglePill = newTextView("[✓ ON]", 14.0f, 0xFFDCFCE7, true);
                setBackgroundRounded(togglePill, 0xFF065F46, 8.0f, 1, 0xFF10B981);
                setViewPadding(togglePill, 10, 6, 10, 6);
                setGravityCenter(togglePill);
                setLayoutParamsLinear(togglePill, 94, 38, 0, 0, 12, 0);
                rowToggleBtns.put(def.key, togglePill);
                addView(row, togglePill);

                Object textCol = newLinearLayout(1, 0);
                setLayoutParamsLinearWeight(textCol, 0, -2, 1.0f, 0, 0, 10, 0);
                Object nameTv = newTextView(def.title, 16.0f, 0xFFF8FAFC, true);
                Object descTv = newTextView(def.description, 12.0f, 0xFF94A3B8, false);
                addView(textCol, nameTv);
                addView(textCol, descTv);
                addView(row, textCol);

                Object stateLbl = newTextView("순정", 13.0f, 0xFF94A3B8, true);
                setGravityCenter(stateLbl);
                setLayoutParamsLinear(stateLbl, 100, 32, 0, 0, 0, 0);
                rowStateLabels.put(def.key, stateLbl);
                addView(row, stateLbl);

                attachScrollSafeClick(row, new Runnable() {
                    @Override
                    public void run() {
                        toggleSingleTweakAsync(def.key);
                    }
                });
                addView(listBox, row);
            }
            addView(scrollView, listBox);
            addView(root, scrollView);

            // 4. Animation Preset Row + Boot Auto-Apply Toggle (~48px)
            Object midRow = newLinearLayout(0, 0);
            setGravityCenterVertical(midRow);
            setBackgroundRounded(midRow, 0xFF131D31, 10.0f, 1, 0xFF283548);
            setViewPadding(midRow, 12, 6, 12, 6);
            setLayoutParamsLinear(midRow, -1, 48, 0, 0, 0, 8);

            Object animTitle = newTextView("애니메이션 배율:", 14.0f, 0xFFE2E8F0, true);
            setLayoutParamsLinear(animTitle, -2, -2, 0, 0, 10, 0);
            addView(midRow, animTitle);

            String[] presets = new String[] { "1.0", "0.5", "0.25", "0.0" };
            for (int i = 0; i < presets.length; i++) {
                final String p = presets[i];
                String label = "0.25".equals(p) ? "0.25x(추천)" : (p + "x");
                Object pBtn = makeActionButton(label, 0xFF1E293B, 0xFF475569, 0xFFCBD5E1, 13.0f);
                setLayoutParamsLinear(pBtn, "0.25".equals(p) ? 104 : 74, 34, 0, 0, 8, 0);
                attachTapClick(pBtn, new Runnable() {
                    @Override
                    public void run() {
                        selectAnimPresetAsync(p);
                    }
                });
                presetBtns.put(p, pBtn);
                addView(midRow, pBtn);
            }

            Object spacer = newView();
            setLayoutParamsLinearWeight(spacer, 0, 1, 1.0f, 0, 0, 0, 0);
            addView(midRow, spacer);

            bootAutoBtn = makeActionButton("시동 시 자동 적용: ON", 0xFF065F46, 0xFF10B981, 0xFFECFDF5, 14.0f);
            setLayoutParamsLinear(bootAutoBtn, 236, 36, 0, 0, 0, 0);
            attachTapClick(bootAutoBtn, new Runnable() {
                @Override
                public void run() {
                    toggleAutoBoot();
                }
            });
            addView(midRow, bootAutoBtn);
            addView(root, midRow);

            // 5. Action Footer Buttons (~50px)
            Object footer = newLinearLayout(0, 0);
            setGravityCenterVertical(footer);
            setLayoutParamsLinear(footer, -1, 50, 0, 0, 0, 6);

            Object applyAllBtn = makeActionButton("🚀 원클릭 전체 최적화 적용 (즉시 적용)", 0xFF059669, 0xFF34D399, 0xFFFFFFFF, 16.0f);
            setLayoutParamsLinearWeight(applyAllBtn, 0, 48, 1.35f, 0, 0, 10, 0);
            attachTapClick(applyAllBtn, new Runnable() {
                @Override
                public void run() {
                    runApplyAllAsync(false);
                }
            });
            addView(footer, applyAllBtn);

            Object revertAllBtn = makeActionButton("↺ 전체 순정 원복", 0xFF334155, 0xFFF59E0B, 0xFFFDE68A, 15.0f);
            setLayoutParamsLinearWeight(revertAllBtn, 0, 48, 0.9f, 0, 0, 10, 0);
            attachTapClick(revertAllBtn, new Runnable() {
                @Override
                public void run() {
                    runRevertAllAsync();
                }
            });
            addView(footer, revertAllBtn);

            Object saveCloseBtn = makeActionButton("💾 설정 저장 후 닫기 (RAM 0MB)", 0xFF1D4ED8, 0xFF60A5FA, 0xFFFFFFFF, 15.0f);
            setLayoutParamsLinearWeight(saveCloseBtn, 0, 48, 1.05f, 0, 0, 0, 0);
            attachTapClick(saveCloseBtn, new Runnable() {
                @Override
                public void run() {
                    OptModel.saveConfig(installDir, config);
                    shutdownDaemon();
                }
            });
            addView(footer, saveCloseBtn);
            addView(root, footer);

            // 6. Status / Note Bar (~24px)
            actionNoteView = newTextView(lastActionNote, 12.0f, 0xFF94A3B8, false);
            setLayoutParamsLinear(actionNoteView, -1, 24, 2, 0, 2, 0);
            addView(root, actionNoteView);

            updateBootAutoButtonVisual();
            updatePresetButtonsVisual();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void renderLiveStatus(OptEngine.LiveStatus st) {
        if (st == null || rootView == null) {
            return;
        }
        int activeCount = st.activeTweaks.size();
        if (headerBadgeView != null) {
            if (activeCount > 0) {
                setTextViewText(headerBadgeView, "적용됨 " + activeCount + "/" + OptModel.ALL_TWEAK_KEYS.length);
                setTextViewColor(headerBadgeView, 0xFFD1FAE5);
                setBackgroundRounded(headerBadgeView, 0xFF065F46, 10.0f, 1, 0xFF10B981);
            } else {
                setTextViewText(headerBadgeView, "순정 상태 (0/6)");
                setTextViewColor(headerBadgeView, 0xFFCBD5E1);
                setBackgroundRounded(headerBadgeView, 0xFF1E293B, 10.0f, 1, 0xFF475569);
            }
        }
        if (telemetryView != null) {
            setTextViewText(telemetryView, formatLiveTelemetryText(st));
        }

        OptModel.BaselineState baseline = OptModel.loadBaseline();
        for (int i = 0; i < OptModel.ALL_TWEAK_KEYS.length; i++) {
            String key = OptModel.ALL_TWEAK_KEYS[i];
            boolean applied = baseline != null && baseline.activeTweaks.contains(key);
            boolean cfgEnabled = config != null && config.isEnabled(key);
            Object pill = rowToggleBtns.get(key);
            Object lbl = rowStateLabels.get(key);
            if (pill != null) {
                if (applied) {
                    setTextViewText(pill, "[✓ ON]");
                    setTextViewColor(pill, 0xFFDCFCE7);
                    setBackgroundRounded(pill, 0xFF065F46, 8.0f, 1, 0xFF10B981);
                } else if (cfgEnabled) {
                    setTextViewText(pill, "[예약]");
                    setTextViewColor(pill, 0xFFE0F2FE);
                    setBackgroundRounded(pill, 0xFF0C4A6E, 8.0f, 1, 0xFF38BDF8);
                } else {
                    setTextViewText(pill, "[OFF]");
                    setTextViewColor(pill, 0xFF94A3B8);
                    setBackgroundRounded(pill, 0xFF1E293B, 8.0f, 1, 0xFF475569);
                }
            }
            if (lbl != null) {
                setTextViewText(lbl, applied ? "적용됨" : (cfgEnabled ? "대기중" : "제외됨"));
                setTextViewColor(lbl, applied ? 0xFF34D399 : (cfgEnabled ? 0xFF7DD3FC : 0xFF64748B));
            }
        }

        updatePresetButtonsVisual();
        updateBootAutoButtonVisual();
        updateNoteText();
    }

    public static String formatLiveTelemetryText(OptEngine.LiveStatus st) {
        if (st == null) {
            return "";
        }
        String irqStr = st.cpu0IrqPct >= 0.0
                ? String.format(Locale.US, "CPU0 %.1f%%(이동 %d)", Double.valueOf(st.cpu0IrqPct), Integer.valueOf(st.dispersedIrqs))
                : "확인불가";
        String ksmStr = ("0".equals(st.ksmRun) ? "OFF" : "ON") + "(" + st.ksmSleepMs + "ms)";
        String animStr = String.format(Locale.US, "%.2f/%.2f/%.2f",
                Float.valueOf(st.animWin), Float.valueOf(st.animTrans), Float.valueOf(st.animDur));
        return "VFS압력: " + st.vfsCachePressure
                + "  |  Swap: " + st.swappiness
                + "  |  RA: " + st.readAheadKb + "KB"
                + "  |  KSM: " + ksmStr
                + "  |  로거: " + st.runningLoggers + "개"
                + "  |  IRQ: " + irqStr
                + "  |  Anim: " + animStr;
    }

    private void updatePresetButtonsVisual() {
        String activePreset = config != null ? OptModel.normalizeAnimPreset(config.animPreset) : "0.25";
        for (Map.Entry<String, Object> e : presetBtns.entrySet()) {
            boolean sel = activePreset.equals(e.getKey());
            Object btn = e.getValue();
            if (sel) {
                setBackgroundRounded(btn, 0xFF0369A1, 8.0f, 1, 0xFF38BDF8);
                setTextViewColor(btn, 0xFFFFFFFF);
            } else {
                setBackgroundRounded(btn, 0xFF1E293B, 8.0f, 1, 0xFF475569);
                setTextViewColor(btn, 0xFFCBD5E1);
            }
        }
    }

    private void updateBootAutoButtonVisual() {
        if (bootAutoBtn == null || config == null) {
            return;
        }
        if (config.autoBoot) {
            setTextViewText(bootAutoBtn, "시동 시 자동 적용: ON");
            setTextViewColor(bootAutoBtn, 0xFFECFDF5);
            setBackgroundRounded(bootAutoBtn, 0xFF065F46, 8.0f, 1, 0xFF10B981);
        } else {
            setTextViewText(bootAutoBtn, "시동 시 자동 적용: OFF");
            setTextViewColor(bootAutoBtn, 0xFFE2E8F0);
            setBackgroundRounded(bootAutoBtn, 0xFF334155, 8.0f, 1, 0xFF64748B);
        }
    }

    private void updateNoteText() {
        if (actionNoteView != null) {
            setTextViewText(actionNoteView, lastActionNote + "   |   경로: " + OptModel.TMP_DIR + " (RAM 디스크)");
        }
    }

    // =========================================================================
    // WindowManager & Touch Reflection Helpers
    // =========================================================================

    private void attachOverlayWindow() {
        if (rootView == null || androidContext == null) {
            return;
        }
        try {
            windowManager = androidContext.getClass().getMethod("getSystemService", String.class)
                    .invoke(androidContext, "window");
            Class<?> lpClass = Class.forName("android.view.WindowManager$LayoutParams");
            Object lp = lpClass.getConstructor(int.class, int.class, int.class, int.class, int.class)
                    .newInstance(
                            Integer.valueOf(DASH_WIDTH),
                            Integer.valueOf(-2), // WRAP_CONTENT
                            Integer.valueOf(TYPE_VOLUME_OVERLAY),
                            Integer.valueOf(WM_FLAGS),
                            Integer.valueOf(PIXEL_FORMAT_TRANSLUCENT));
            lpClass.getField("gravity").setInt(lp, GRAVITY_TOP_LEFT);
            lpClass.getField("x").setInt(lp, config != null ? config.x : 318);
            lpClass.getField("y").setInt(lp, config != null ? config.y : 18);
            lpClass.getMethod("setTitle", CharSequence.class).invoke(lp, "HKGSysOptOverlay");
            wmParams = lp;

            Class<?> viewClass = Class.forName("android.view.View");
            Class<?> vgLpClass = Class.forName("android.view.ViewGroup$LayoutParams");
            Method addView = windowManager.getClass().getMethod("addView", viewClass, vgLpClass);
            try {
                addView.invoke(windowManager, rootView, wmParams);
            } catch (Throwable primaryErr) {
                lpClass.getField("type").setInt(wmParams, TYPE_SYSTEM_ALERT);
                addView.invoke(windowManager, rootView, wmParams);
            }
            disableMediaKeysOnViewRoot(rootView);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void disableMediaKeysOnViewRoot(Object view) {
        if (view == null) {
            return;
        }
        try {
            Object parent = view.getClass().getMethod("getParent").invoke(view);
            if (parent != null) {
                Field f = parent.getClass().getDeclaredField("mMediaDisabled");
                f.setAccessible(true);
                f.setBoolean(parent, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void attachHeaderDrag(Object headerView) {
        try {
            Class<?> listenerClass = Class.forName("android.view.View$OnTouchListener");
            Object proxy = Proxy.newProxyInstance(
                    OptimizeMain.class.getClassLoader(),
                    new Class<?>[] { listenerClass },
                    new InvocationHandler() {
                        private float downRawX;
                        private float downRawY;
                        private int startX;
                        private int startY;
                        private boolean moved;

                        @Override
                        public Object invoke(Object proxyObj, Method method, Object[] args) throws Throwable {
                            if (!"onTouch".equals(method.getName()) || args == null || args.length < 2) {
                                return Boolean.FALSE;
                            }
                            Object ev = args[1];
                            int action = ((Integer) ev.getClass().getMethod("getActionMasked").invoke(ev)).intValue();
                            float rawX = ((Float) ev.getClass().getMethod("getRawX").invoke(ev)).floatValue();
                            float rawY = ((Float) ev.getClass().getMethod("getRawY").invoke(ev)).floatValue();
                            if (action == 0) {
                                downRawX = rawX;
                                downRawY = rawY;
                                moved = false;
                                if (wmParams != null) {
                                    startX = wmParams.getClass().getField("x").getInt(wmParams);
                                    startY = wmParams.getClass().getField("y").getInt(wmParams);
                                }
                                return Boolean.TRUE;
                            } else if (action == 2 && wmParams != null && windowManager != null && rootView != null) {
                                float dx = rawX - downRawX;
                                float dy = rawY - downRawY;
                                if (!moved && (Math.abs(dx) > 10.0f || Math.abs(dy) > 10.0f)) {
                                    moved = true;
                                }
                                if (moved) {
                                    int nx = clampInt(startX + (int) dx, 0, 1920 - DASH_WIDTH);
                                    int ny = clampInt(startY + (int) dy, 0, 680);
                                    wmParams.getClass().getField("x").setInt(wmParams, nx);
                                    wmParams.getClass().getField("y").setInt(wmParams, ny);
                                    Method upd = windowManager.getClass().getMethod(
                                            "updateViewLayout",
                                            Class.forName("android.view.View"),
                                            Class.forName("android.view.ViewGroup$LayoutParams"));
                                    upd.invoke(windowManager, rootView, wmParams);
                                }
                                return Boolean.TRUE;
                            } else if ((action == 1 || action == 3) && moved && wmParams != null && config != null) {
                                config.x = wmParams.getClass().getField("x").getInt(wmParams);
                                config.y = wmParams.getClass().getField("y").getInt(wmParams);
                                OptModel.saveConfig(installDir, config);
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        }
                    });
            headerView.getClass().getMethod("setOnTouchListener", listenerClass).invoke(headerView, proxy);
        } catch (Throwable ignored) {
        }
    }

    private void attachScrollSafeClick(Object view, Runnable action) {
        attachTouchSlopClick(view, action, TOUCH_SLOP_PX);
    }

    private void attachTapClick(Object view, Runnable action) {
        attachTouchSlopClick(view, action, 44.0f);
    }

    private void attachTouchSlopClick(Object view, final Runnable action, final float maxSlop) {
        try {
            Class<?> listenerClass = Class.forName("android.view.View$OnTouchListener");
            Object proxy = Proxy.newProxyInstance(
                    OptimizeMain.class.getClassLoader(),
                    new Class<?>[] { listenerClass },
                    new InvocationHandler() {
                        private float downRawX;
                        private float downRawY;
                        private boolean cancelled;

                        @Override
                        public Object invoke(Object proxyObj, Method method, Object[] args) throws Throwable {
                            if (!"onTouch".equals(method.getName()) || args == null || args.length < 2) {
                                return Boolean.FALSE;
                            }
                            Object ev = args[1];
                            int actionMasked = ((Integer) ev.getClass().getMethod("getActionMasked").invoke(ev)).intValue();
                            float rawX = ((Float) ev.getClass().getMethod("getRawX").invoke(ev)).floatValue();
                            float rawY = ((Float) ev.getClass().getMethod("getRawY").invoke(ev)).floatValue();
                            if (actionMasked == 0) { // DOWN
                                downRawX = rawX;
                                downRawY = rawY;
                                cancelled = false;
                                return Boolean.TRUE;
                            } else if (actionMasked == 2) { // MOVE
                                if (!cancelled && (Math.abs(rawX - downRawX) > maxSlop || Math.abs(rawY - downRawY) > maxSlop)) {
                                    cancelled = true;
                                }
                                return Boolean.TRUE;
                            } else if (actionMasked == 3) { // CANCEL (e.g. ScrollView intercepted)
                                cancelled = true;
                                return Boolean.FALSE;
                            } else if (actionMasked == 1) { // UP
                                if (!cancelled
                                        && Math.abs(rawX - downRawX) <= maxSlop
                                        && Math.abs(rawY - downRawY) <= maxSlop) {
                                    action.run();
                                }
                                return Boolean.TRUE;
                            }
                            return Boolean.FALSE;
                        }
                    });
            view.getClass().getMethod("setOnTouchListener", listenerClass).invoke(view, proxy);
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Android View & Looper Reflection Helpers
    // =========================================================================

    private static void setProcessArgV0(String name) {
        try {
            Class<?> proc = Class.forName("android.os.Process");
            proc.getMethod("setArgV0", String.class).invoke(null, name);
        } catch (Throwable ignored) {
        }
    }

    private static Object initAndroidContextQuiet() {
        try {
            prepareMainLooper();
            return initSystemContextOnMainThread();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void prepareMainLooper() {
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Object existing = looperClass.getMethod("getMainLooper").invoke(null);
            if (existing == null) {
                looperClass.getMethod("prepareMainLooper").invoke(null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object initSystemContextOnMainThread() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            if (at == null) {
                at = atClass.getMethod("systemMain").invoke(null);
            }
            if (at == null) {
                return null;
            }
            return atClass.getMethod("getSystemContext").invoke(at);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object createMainHandler() {
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Object looper = looperClass.getMethod("getMainLooper").invoke(null);
            Class<?> handlerClass = Class.forName("android.os.Handler");
            return handlerClass.getConstructor(looperClass).newInstance(looper);
        } catch (Throwable t) {
            return null;
        }
    }

    private void postToUi(Runnable r) {
        if (mainHandler == null || r == null) {
            return;
        }
        try {
            mainHandler.getClass().getMethod("post", Runnable.class).invoke(mainHandler, r);
        } catch (Throwable ignored) {
        }
    }

    private Object newLinearLayout(int orientation, int bgColor) throws Exception {
        Class<?> ctxClass = Class.forName("android.content.Context");
        Class<?> llClass = Class.forName("android.widget.LinearLayout");
        Object ll = llClass.getConstructor(ctxClass).newInstance(androidContext);
        llClass.getMethod("setOrientation", int.class).invoke(ll, Integer.valueOf(orientation));
        if (bgColor != 0) {
            llClass.getMethod("setBackgroundColor", int.class).invoke(ll, Integer.valueOf(bgColor));
        }
        return ll;
    }

    private Object newTextView(String text, float pxSize, int color, boolean bold) throws Exception {
        Class<?> ctxClass = Class.forName("android.content.Context");
        Class<?> tvClass = Class.forName("android.widget.TextView");
        Object tv = tvClass.getConstructor(ctxClass).newInstance(androidContext);
        tvClass.getMethod("setText", CharSequence.class).invoke(tv, text);
        tvClass.getMethod("setTextSize", int.class, float.class).invoke(tv, Integer.valueOf(0), Float.valueOf(pxSize));
        tvClass.getMethod("setTextColor", int.class).invoke(tv, Integer.valueOf(color));
        if (bold) {
            Class<?> tfClass = Class.forName("android.graphics.Typeface");
            Object defBold = tfClass.getField("DEFAULT_BOLD").get(null);
            tvClass.getMethod("setTypeface", tfClass).invoke(tv, defBold);
        }
        setImportantForAccessibilityNoHide(tv);
        return tv;
    }

    private Object newView() throws Exception {
        Class<?> ctxClass = Class.forName("android.content.Context");
        return Class.forName("android.view.View").getConstructor(ctxClass).newInstance(androidContext);
    }

    private Object makeActionButton(String label, int bgColor, int strokeColor, int textColor, float pxSize) throws Exception {
        Object tv = newTextView(label, pxSize, textColor, true);
        setGravityCenter(tv);
        setBackgroundRounded(tv, bgColor, 10.0f, 1, strokeColor);
        setViewPadding(tv, 10, 4, 10, 4);
        return tv;
    }

    private static void setTextViewText(Object tv, String text) {
        try {
            tv.getClass().getMethod("setText", CharSequence.class).invoke(tv, text);
        } catch (Throwable ignored) {
        }
    }

    private static void setTextViewColor(Object tv, int color) {
        try {
            tv.getClass().getMethod("setTextColor", int.class).invoke(tv, Integer.valueOf(color));
        } catch (Throwable ignored) {
        }
    }

    private static void setBackgroundRounded(Object view, int fillColor, float radiusPx, int strokePx, int strokeColor) {
        try {
            Class<?> gdClass = Class.forName("android.graphics.drawable.GradientDrawable");
            Object gd = gdClass.newInstance();
            gdClass.getMethod("setColor", int.class).invoke(gd, Integer.valueOf(fillColor));
            gdClass.getMethod("setCornerRadius", float.class).invoke(gd, Float.valueOf(radiusPx));
            if (strokePx > 0) {
                gdClass.getMethod("setStroke", int.class, int.class)
                        .invoke(gd, Integer.valueOf(strokePx), Integer.valueOf(strokeColor));
            }
            Class<?> drClass = Class.forName("android.graphics.drawable.Drawable");
            view.getClass().getMethod("setBackgroundDrawable", drClass).invoke(view, gd);
        } catch (Throwable ignored) {
        }
    }

    private static void setViewPadding(Object view, int l, int t, int r, int b) {
        try {
            view.getClass().getMethod("setPadding", int.class, int.class, int.class, int.class)
                    .invoke(view, Integer.valueOf(l), Integer.valueOf(t), Integer.valueOf(r), Integer.valueOf(b));
        } catch (Throwable ignored) {
        }
    }

    private static void setGravityCenter(Object view) {
        try {
            view.getClass().getMethod("setGravity", int.class).invoke(view, Integer.valueOf(17));
        } catch (Throwable ignored) {
        }
    }

    private static void setGravityCenterVertical(Object view) {
        try {
            view.getClass().getMethod("setGravity", int.class).invoke(view, Integer.valueOf(16));
        } catch (Throwable ignored) {
        }
    }

    private static void setLayoutParamsLinear(Object view, int w, int h, int ml, int mt, int mr, int mb) {
        try {
            Class<?> lpClass = Class.forName("android.widget.LinearLayout$LayoutParams");
            Object lp = lpClass.getConstructor(int.class, int.class).newInstance(Integer.valueOf(w), Integer.valueOf(h));
            lpClass.getMethod("setMargins", int.class, int.class, int.class, int.class)
                    .invoke(lp, Integer.valueOf(ml), Integer.valueOf(mt), Integer.valueOf(mr), Integer.valueOf(mb));
            view.getClass().getMethod("setLayoutParams", Class.forName("android.view.ViewGroup$LayoutParams")).invoke(view, lp);
        } catch (Throwable ignored) {
        }
    }

    private static void setLayoutParamsLinearWeight(Object view, int w, int h, float weight, int ml, int mt, int mr, int mb) {
        try {
            Class<?> lpClass = Class.forName("android.widget.LinearLayout$LayoutParams");
            Object lp = lpClass.getConstructor(int.class, int.class, float.class)
                    .newInstance(Integer.valueOf(w), Integer.valueOf(h), Float.valueOf(weight));
            lpClass.getMethod("setMargins", int.class, int.class, int.class, int.class)
                    .invoke(lp, Integer.valueOf(ml), Integer.valueOf(mt), Integer.valueOf(mr), Integer.valueOf(mb));
            view.getClass().getMethod("setLayoutParams", Class.forName("android.view.ViewGroup$LayoutParams")).invoke(view, lp);
        } catch (Throwable ignored) {
        }
    }

    private static void addView(Object parent, Object child) throws Exception {
        parent.getClass().getMethod("addView", Class.forName("android.view.View")).invoke(parent, child);
    }

    private static void setImportantForAccessibilityNoHide(Object view) {
        try {
            view.getClass().getMethod("setImportantForAccessibility", int.class).invoke(view, Integer.valueOf(4));
        } catch (Throwable ignored) {
        }
    }

    private static int getViewVisibility(Object view) {
        try {
            return ((Integer) view.getClass().getMethod("getVisibility").invoke(view)).intValue();
        } catch (Throwable t) {
            return 8;
        }
    }

    private static void setViewVisibility(Object view, int vis) {
        try {
            view.getClass().getMethod("setVisibility", int.class).invoke(view, Integer.valueOf(vis));
        } catch (Throwable ignored) {
        }
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        return v > max ? max : v;
    }

    private static String shorten(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : (s.substring(0, maxLen - 3) + "...");
    }

    // =========================================================================
    // Host Self-Tests (6 Suites - Pure JDK / No Android Runtime Required)
    // =========================================================================

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Self-test failed: " + msg);
        }
    }

    private static void runSelfTests() {
        testJsonRoundTrip();
        testConfigReadWrite();
        testActiveConfFormat();
        testIrqCoreMaskComputation();
        testProcessClassification();
        testAnimPresetMapping();
        System.out.println("HKG 5W Optimize host self-tests: 6/6 OK");
    }

    private static void testJsonRoundTrip() {
        OptModel.BaselineState st = new OptModel.BaselineState();
        st.bootId = "1234-abcd";
        st.ksmRun = "1";
        st.ksmSleepMs = "80";
        st.vfsCachePressure = "10000";
        st.swappiness = "60";
        st.dirtyBgRatio = "10";
        st.dirtyRatio = "20";
        st.minFreeKbytes = "8192";
        st.blockDev = "mmcblk0";
        st.readAheadKb = "128";
        st.nrRequests = "128";
        st.rqAffinity = "1";
        st.animWin = 1.0f;
        st.animTrans = 1.0f;
        st.animDur = 1.0f;
        st.activeTweaks.add(OptModel.KEY_VM_MMC);
        st.activeTweaks.add(OptModel.KEY_IRQ);
        st.irqMasks.put("42", "1");
        st.irqMasks.put("99", "f");

        String json = st.toJson();
        OptModel.BaselineState parsed = OptModel.BaselineState.fromJson(json);
        assertTrue(parsed != null, "parsed baseline null");
        assertTrue("1234-abcd".equals(parsed.bootId), "bootId");
        assertTrue("10000".equals(parsed.vfsCachePressure), "vfs");
        assertTrue("mmcblk0".equals(parsed.blockDev), "blockDev");
        assertTrue(Math.abs(parsed.animWin - 1.0f) < 0.001f, "animWin");
        assertTrue(parsed.activeTweaks.contains(OptModel.KEY_VM_MMC), "active vm_mmc");
        assertTrue(parsed.activeTweaks.contains(OptModel.KEY_IRQ), "active irq");
        assertTrue("1".equals(parsed.irqMasks.get("42")), "irq 42 mask");
        assertTrue("f".equals(parsed.irqMasks.get("99")), "irq 99 mask");
    }

    private static void testConfigReadWrite() {
        OptModel.OptConfig cfg = new OptModel.OptConfig();
        cfg.x = 400;
        cfg.y = 32;
        cfg.autoBoot = false;
        cfg.animPreset = "0.5";
        cfg.setEnabled(OptModel.KEY_KSM, false);

        String raw = cfg.serialize();
        OptModel.OptConfig parsed = OptModel.OptConfig.parse(raw);
        assertTrue(parsed.x == 400 && parsed.y == 32, "xy");
        assertTrue(!parsed.autoBoot, "autoBoot false");
        assertTrue("0.5".equals(parsed.animPreset), "animPreset 0.5");
        assertTrue(!parsed.isEnabled(OptModel.KEY_KSM), "ksm disabled");
        assertTrue(parsed.isEnabled(OptModel.KEY_SCHED), "sched enabled");
    }

    private static void testActiveConfFormat() {
        Set<String> set = new LinkedHashSet<String>(Arrays.asList(OptModel.KEY_LOGGERS, OptModel.KEY_SCHED, OptModel.KEY_ANIM));
        String txt = OptModel.formatActiveConf("boot-xyz", set, "0.25", "123.45");
        assertTrue(txt.contains("boot_id=boot-xyz\n"), "boot_id");
        assertTrue(txt.contains("active=1\n"), "active=1");
        assertTrue(txt.contains("tweaks=loggers,sched,anim\n"), "tweaks order");
        assertTrue(txt.contains("anim_preset=0.25\n"), "preset");

        String emptyTxt = OptModel.formatActiveConf("boot-xyz", new LinkedHashSet<String>(), "0.25", "125.00");
        assertTrue(emptyTxt.contains("active=0\n"), "active=0 when empty");
    }

    private static void testIrqCoreMaskComputation() {
        List<String> fakeInterrupts = Arrays.asList(
                "           CPU0       CPU1       CPU2       CPU3",
                " 29:       1000       1000       1000       1000       GIC  arch_timer",
                " 32:          5          0          0          0       GIC  tcc-wdt",
                " 44:      50000          0          0          0       GIC  mmc0",
                " 65:      20000          0          0          0       GIC  ehci_hcd:usb1",
                " 71:      10000          0          0          0       GIC  tcc-uart0",
                " 88:      15000          0          0          0       GIC  mali-gp",
                " 95:       8000          0          0          0       GIC  eth0");
        Map<String, Boolean> writable = new LinkedHashMap<String, Boolean>();
        writable.put("29", Boolean.TRUE);
        writable.put("32", Boolean.TRUE);
        writable.put("44", Boolean.TRUE);
        writable.put("65", Boolean.TRUE);
        writable.put("71", Boolean.TRUE);
        writable.put("88", Boolean.TRUE);
        writable.put("95", Boolean.TRUE);

        Map<String, String> plan = OptEngine.computeIrqAssignments(fakeInterrupts, 4, writable);
        assertTrue(!plan.containsKey("29"), "timer must stay protected");
        assertTrue(!plan.containsKey("32"), "wdt must stay protected");
        assertTrue(!plan.containsKey("71"), "uart must stay protected");
        assertTrue("2".equals(plan.get("44")), "mmc0 -> CPU1 (mask 2)");
        assertTrue("4".equals(plan.get("65")), "usb1 -> CPU2 (mask 4)");
        assertTrue("8".equals(plan.get("88")), "mali -> CPU3 (mask 8)");
        assertTrue("2".equals(plan.get("95")), "eth0 wraps -> CPU1 (mask 2)");

        Map<String, String> singleCore = OptEngine.computeIrqAssignments(fakeInterrupts, 1, writable);
        assertTrue(singleCore.isEmpty(), "1-core returns empty");
    }

    private static void testProcessClassification() {
        assertTrue(OptEngine.classifyProcess("/system/bin/surfaceflinger", "surfaceflinger") == OptEngine.ProcRole.SURFACEFLINGER, "sf");
        assertTrue(OptEngine.targetNiceForRole(OptEngine.ProcRole.SURFACEFLINGER, "Binder_1") == -8, "sf -8");

        assertTrue(OptEngine.classifyProcess("system_server", "system_server") == OptEngine.ProcRole.SYSTEM_SERVER, "ss");
        assertTrue(OptEngine.targetNiceForRole(OptEngine.ProcRole.SYSTEM_SERVER, "android.ui") == -8, "ss ui -8");
        assertTrue(OptEngine.targetNiceForRole(OptEngine.ProcRole.SYSTEM_SERVER, "FinalizerDaemon") == Integer.MIN_VALUE, "ss gc skipped");

        assertTrue(OptEngine.classifyProcess("com.mnsoft.navi", "om.mnsoft.navi") == OptEngine.ProcRole.NAVI, "navi");
        assertTrue(OptEngine.targetNiceForRole(OptEngine.ProcRole.NAVI, "GLThread 112") == -6, "navi gl -6");
        assertTrue(OptEngine.targetNiceForRole(OptEngine.ProcRole.NAVI, "HeapTrimmerDaemon") == Integer.MIN_VALUE, "navi gc skipped");

        assertTrue(OptEngine.classifyProcess("/system/bin/app_process /system/bin com.carrot.CarrotBridge", "CarrotBridge") == OptEngine.ProcRole.EXTERNAL_DAEMON, "carrot bridge");
        assertTrue(OptEngine.classifyProcess("/system/bin/app_process /system/bin com.turzx.TurzxMonitor", "app_process") == OptEngine.ProcRole.EXTERNAL_DAEMON, "turzx");
        assertTrue(OptEngine.targetNiceForRole(OptEngine.ProcRole.EXTERNAL_DAEMON, "main") == 4, "external daemon +4");

        assertTrue(OptEngine.classifyProcess("/system/bin/app_process /system/bin com.hkg.optimize.OptimizeMain daemon", "com.hkg.optimize") == OptEngine.ProcRole.PROTECTED_SELF, "self protected");
        assertTrue(OptEngine.classifyProcess("/system/bin/tcc_dxb_service", "tcc_dxb_service") == OptEngine.ProcRole.DXB_BLOAT, "dxb +6");
        assertTrue(OptEngine.classifyProcess("/system/bin/vrm", "vrm") == OptEngine.ProcRole.BG_BLOAT, "vrm +10");
        assertTrue(OptEngine.classifyProcess("/system/bin/export", "export") == OptEngine.ProcRole.NONE, "no false positive export");

        assertTrue(OptEngine.isExactDiskLoggerProcess("/system/bin/eventlogdump", "eventlogdump"), "eventlogdump");
        assertTrue(!OptEngine.isExactDiskLoggerProcess("logcat -v time", "logcat"), "never kill logcat child pipe");
    }

    private static void testAnimPresetMapping() {
        float[] p025 = OptModel.getAnimScalesForPreset("0.25");
        assertTrue(Math.abs(p025[0] - 0.25f) < 0.001f
                && Math.abs(p025[1] - 0.25f) < 0.001f
                && Math.abs(p025[2] - 0.50f) < 0.001f, "0.25x -> 0.25/0.25/0.50");

        float[] p05 = OptModel.getAnimScalesForPreset("0.5x");
        assertTrue(Math.abs(p05[0] - 0.5f) < 0.001f && Math.abs(p05[2] - 0.5f) < 0.001f, "0.5x");

        float[] p00 = OptModel.getAnimScalesForPreset("0.0");
        assertTrue(Math.abs(p00[0]) < 0.001f && Math.abs(p00[2]) < 0.001f, "0.0x");

        float[] p10 = OptModel.getAnimScalesForPreset("1.0");
        assertTrue(Math.abs(p10[0] - 1.0f) < 0.001f && Math.abs(p10[2] - 1.0f) < 0.001f, "1.0x");
    }
}
