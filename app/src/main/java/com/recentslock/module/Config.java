package com.recentslock.module;

/** Konstanta yang dipakai bersama oleh UI modul dan hook di system_server. */
public final class Config {
    private Config() {}

    public static final String MODULE_PKG = "com.recentslock.module";
    public static final String PREFS = "settings";

    /** Set<String> nama paket yang dikunci. */
    public static final String KEY_LOCKED = "locked_packages";
    /** Saklar utama (true = penguncian aktif). */
    public static final String KEY_ENABLED = "enabled";
    /** Preferensi tampilan: tampilkan app sistem di daftar. */
    public static final String KEY_SHOW_SYSTEM = "show_system";
}
