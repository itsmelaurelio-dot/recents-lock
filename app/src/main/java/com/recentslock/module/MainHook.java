package com.recentslock.module;

import android.content.ComponentName;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Cara kerja (berjalan di dalam system_server, scope "Sistem Framework"):
 *
 * 1. Swipe satu kartu  -> ActivityTaskManagerService.removeTask(int)
 *    Jika task milik app terkunci, panggilan diganti hasil false (task tidak dihapus).
 *
 * 2. Tombol "Hapus semua" -> ActivityTaskManagerService.removeAllVisibleRecentTasks()
 *    -> RecentTasks.removeAllVisibleTasks(int), yang memakai isVisibleRecentTask(task)
 *    untuk memilih task yang dibuang. Selama proses itu berjalan (di thread yang sama),
 *    task app terkunci kita laporkan "tidak visible" sehingga dilewati: tidak dibuang
 *    dari daftar dan prosesnya tidak di-kill.
 *
 * Di luar proses "Hapus semua", isVisibleRecentTask tidak diubah, jadi kartu tetap
 * tampil normal di recents.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "RecentsLock: ";
    private static final String WM = "com.android.server.wm.";

    /** Kedalaman "sedang Hapus semua" di thread ini (0 = tidak sedang). */
    private static final ThreadLocal<int[]> CLEARING = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    private static XSharedPreferences prefs;
    private static volatile Set<String> locked = Collections.emptySet();
    private static volatile boolean enabled = true;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        try {
            if (Config.MODULE_PKG.equals(lpparam.packageName)) {
                // Supaya UI modul bisa menampilkan "Modul di framework: aktif"
                XposedHelpers.findAndHookMethod(
                        Config.MODULE_PKG + ".MainActivity", lpparam.classLoader,
                        "isModuleActive", XC_MethodReplacement.returnConstant(true));
                return;
            }
            if ("android".equals(lpparam.packageName) && "android".equals(lpparam.processName)) {
                hookSystemServer(lpparam.classLoader);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal memasang hook: " + t);
        }
    }

    private static void hookSystemServer(ClassLoader cl) {
        Class<?> atms = XposedHelpers.findClassIfExists(WM + "ActivityTaskManagerService", cl);
        Class<?> recents = XposedHelpers.findClassIfExists(WM + "RecentTasks", cl);
        if (atms == null || recents == null) {
            XposedBridge.log(TAG + "kelas ATMS/RecentTasks tidak ditemukan, ROM ini tidak didukung");
            return;
        }

        // --- "Hapus semua": tandai awal & akhir proses ---
        XC_MethodHook clearingMarker = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                reloadPrefs();
                CLEARING.get()[0]++;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int[] depth = CLEARING.get();
                if (depth[0] > 0) depth[0]--;
            }
        };
        report("RecentTasks.removeAllVisibleTasks",
                XposedBridge.hookAllMethods(recents, "removeAllVisibleTasks", clearingMarker));
        report("ATMS.removeAllVisibleRecentTasks",
                XposedBridge.hookAllMethods(atms, "removeAllVisibleRecentTasks", clearingMarker));

        // --- Selama "Hapus semua": task terkunci dianggap tidak visible -> dilewati ---
        report("RecentTasks.isVisibleRecentTask",
                XposedBridge.hookAllMethods(recents, "isVisibleRecentTask", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (CLEARING.get()[0] <= 0) return;
                        if (!Boolean.TRUE.equals(param.getResult())) return;
                        if (param.args.length > 0 && isLocked(param.args[0])) {
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }));

        // --- Swipe satu kartu ---
        report("ATMS.removeTask",
                XposedBridge.hookAllMethods(atms, "removeTask", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                        reloadPrefs();
                        Object task = findTask(param.thisObject, (Integer) param.args[0]);
                        if (task != null && isLocked(task)) {
                            XposedBridge.log(TAG + "swipe diblokir: " + packageOf(task));
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }));

        XposedBridge.log(TAG + "hook terpasang");
    }

    private static void report(String name, Set<?> hooks) {
        if (hooks == null || hooks.isEmpty()) {
            XposedBridge.log(TAG + "PERINGATAN: method tidak ditemukan -> " + name);
        }
    }

    // ------------------------------------------------------------------ prefs

    private static synchronized void reloadPrefs() {
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(Config.MODULE_PKG, Config.PREFS);
                prefs.makeWorldReadable();
            } else {
                prefs.reload();
            }
            Set<String> s = prefs.getStringSet(Config.KEY_LOCKED, null);
            locked = (s == null) ? Collections.<String>emptySet() : new HashSet<String>(s);
            enabled = prefs.getBoolean(Config.KEY_ENABLED, true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal membaca pengaturan: " + t);
        }
    }

    private static boolean isLocked(Object task) {
        if (!enabled) return false;
        String pkg = packageOf(task);
        return pkg != null && locked.contains(pkg);
    }

    // ------------------------------------------------------- akses objek Task

    private static String packageOf(Object task) {
        if (task == null) return null;
        try {
            ComponentName cn = (ComponentName) XposedHelpers.getObjectField(task, "realActivity");
            if (cn != null) return cn.getPackageName();
        } catch (Throwable ignored) {
        }
        try {
            Intent i = (Intent) XposedHelpers.callMethod(task, "getBaseIntent");
            if (i != null) {
                if (i.getComponent() != null) return i.getComponent().getPackageName();
                if (i.getPackage() != null) return i.getPackage();
            }
        } catch (Throwable ignored) {
        }
        try {
            ComponentName cn = (ComponentName) XposedHelpers.getObjectField(task, "origActivity");
            if (cn != null) return cn.getPackageName();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int taskIdOf(Object task) {
        try {
            return XposedHelpers.getIntField(task, "mTaskId");
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getIntField(task, "taskId");
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rawTasks(Object recentTasks) {
        try {
            return (List<Object>) XposedHelpers.callMethod(recentTasks, "getRawTasks");
        } catch (Throwable ignored) {
        }
        try {
            return (List<Object>) XposedHelpers.getObjectField(recentTasks, "mTasks");
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findTask(Object atms, int taskId) {
        try {
            Object recentTasks = XposedHelpers.getObjectField(atms, "mRecentTasks");
            Object lock;
            try {
                lock = XposedHelpers.getObjectField(atms, "mGlobalLock");
            } catch (Throwable t) {
                lock = MainHook.class;
            }
            synchronized (lock) {
                List<Object> raw = rawTasks(recentTasks);
                if (raw == null) return null;
                for (Object t : new ArrayList<Object>(raw)) {
                    if (taskIdOf(t) == taskId) return t;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal mencari task " + taskId + ": " + t);
        }
        return null;
    }
}
