# Recents Lock (modul LSPosed)

Mengunci app terpilih di **recent apps** supaya tidak hilang saat tombol
**Hapus semua** ditekan atau kartunya tidak sengaja ter-swipe.

## Cara pakai
1. Pasang APK, buka LSPosed -> Modul -> aktifkan **Recents Lock**.
2. Scope: centang **Sistem Framework** (dan modul ini sendiri). **Reboot**.
3. Buka app Recents Lock, centang app yang mau dikunci. Perubahan langsung
   berlaku, tidak perlu reboot lagi.

## Cara kerja (system_server)
- Swipe: hook `ActivityTaskManagerService.removeTask(int)`, task app terkunci tidak dihapus.
- Hapus semua: selama `RecentTasks.removeAllVisibleTasks` berjalan, task app terkunci
  dianggap tidak visible sehingga dilewati (tidak dibuang dan prosesnya tidak di-kill).

## Catatan
- Kartu app terkunci bisa terlihat hilang sesaat di animasi Recents saat Hapus semua
  (animasi dikerjakan launcher), tetapi muncul lagi saat Recents dibuka ulang.
- Log hook: LSPosed -> Log, cari `RecentsLock`.

## Keamanan repo
- Keystore TIDAK ada di repo (`.gitignore`). Tanda tangan APK memakai GitHub Secrets:
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Workflow hanya berjalan pada push ke `main`, tag `v*`, atau manual; izin token minimum.
