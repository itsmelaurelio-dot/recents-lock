package com.recentslock.module;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_BACKUP = 101;
    private static final int REQ_RESTORE = 102;
    private static final int MENU_SELECT_ALL = 1;
    private static final int MENU_CLEAR_ALL = 2;
    private static final int MENU_SHOW_SYSTEM = 3;
    private static final int MENU_BACKUP = 4;
    private static final int MENU_RESTORE = 5;
    private static final Pattern PKG = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$");

    /** Di-hook oleh MainHook; bernilai true jika modul aktif di LSPosed. */
    public static boolean isModuleActive() {
        return false;
    }

    private static final class AppItem {
        final String label;
        final String pkg;
        final boolean system;
        final ApplicationInfo info;

        AppItem(String label, String pkg, boolean system, ApplicationInfo info) {
            this.label = label;
            this.pkg = pkg;
            this.system = system;
            this.info = info;
        }
    }

    private SharedPreferences prefs;
    private boolean sharedOk;
    private boolean showSystem;
    private String query = "";

    private final Set<String> selected = new HashSet<String>();
    private final List<AppItem> all = new ArrayList<AppItem>();
    private final List<AppItem> shown = new ArrayList<AppItem>();
    private final Map<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView status;
    private AppAdapter adapter;
    private MenuItem systemItem;

    @SuppressWarnings({"deprecation", "WorldReadableFiles"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Recents Lock");

        SharedPreferences p;
        boolean ok;
        try {
            // Hanya berhasil jika LSPosed aktif (modul ikut di-hook untuk mengizinkan mode ini)
            p = getSharedPreferences(Config.PREFS, MODE_WORLD_READABLE);
            ok = true;
        } catch (SecurityException e) {
            p = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
            ok = false;
        }
        prefs = p;
        sharedOk = ok;

        Set<String> saved = prefs.getStringSet(Config.KEY_LOCKED, null);
        if (saved != null) selected.addAll(saved);
        showSystem = prefs.getBoolean(Config.KEY_SHOW_SYSTEM, false);

        buildUi();
        loadApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ------------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(0xFFB0B0B0);
        status.setPadding(dp(16), dp(12), dp(16), dp(4));
        root.addView(status);

        Switch master = new Switch(this);
        master.setText("Aktifkan penguncian");
        master.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        master.setChecked(prefs.getBoolean(Config.KEY_ENABLED, true));
        master.setPadding(dp(16), dp(8), dp(16), dp(8));
        master.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(Config.KEY_ENABLED, checked).apply();
            }
        });
        root.addView(master);

        EditText search = new EditText(this);
        search.setHint("Cari app...");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(dp(12), 0, dp(12), 0);
        root.addView(search, sp);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                AppItem a = shown.get(pos);
                if (!selected.remove(a.pkg)) selected.add(a.pkg);
                saveSelected();
                adapter.notifyDataSetChanged();
                updateStatus();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateStatus();
    }

    private void updateStatus() {
        boolean active = isModuleActive();
        StringBuilder sb = new StringBuilder();
        sb.append("Penyimpanan bersama: ").append(sharedOk ? "aktif \u2713" : "tidak aktif \u2717").append('\n');
        sb.append("Modul di framework: ").append(active ? "aktif \u2713" : "tidak aktif \u2717").append('\n');
        sb.append("Dipilih: ").append(selected.size()).append(" app. Perubahan langsung berlaku.");
        if (!active || !sharedOk) {
            sb.append("\nAktifkan modul di LSPosed (scope: Sistem Framework + modul ini), lalu reboot.");
        }
        status.setText(sb.toString());
    }

    // ----------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SELECT_ALL, 0, "Pilih semua yang tampil");
        menu.add(0, MENU_CLEAR_ALL, 1, "Hapus semua yang tampil");
        systemItem = menu.add(0, MENU_SHOW_SYSTEM, 2, "Tampilkan app sistem");
        systemItem.setCheckable(true);
        systemItem.setChecked(showSystem);
        menu.add(0, MENU_BACKUP, 3, "Backup daftar ke file");
        menu.add(0, MENU_RESTORE, 4, "Restore daftar dari file");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SELECT_ALL:
                for (AppItem a : shown) selected.add(a.pkg);
                afterBulkChange();
                return true;
            case MENU_CLEAR_ALL:
                for (AppItem a : shown) selected.remove(a.pkg);
                afterBulkChange();
                return true;
            case MENU_SHOW_SYSTEM:
                showSystem = !showSystem;
                item.setChecked(showSystem);
                prefs.edit().putBoolean(Config.KEY_SHOW_SYSTEM, showSystem).apply();
                applyFilter();
                return true;
            case MENU_BACKUP: {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, "recentslock-backup.txt");
                startActivityForResult(i, REQ_BACKUP);
                return true;
            }
            case MENU_RESTORE: {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_RESTORE);
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void afterBulkChange() {
        saveSelected();
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_BACKUP) {
            final List<String> lines = new ArrayList<String>(new TreeSet<String>(selected));
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        StringBuilder sb = new StringBuilder();
                        for (String s : lines) sb.append(s).append('\n');
                        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        toast("Backup disimpan (" + lines.size() + " app)");
                    } catch (Exception e) {
                        toast("Backup gagal: " + e.getMessage());
                    }
                }
            });
        } else if (requestCode == REQ_RESTORE) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    final Set<String> loaded = new HashSet<String>();
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         BufferedReader r = new BufferedReader(
                                 new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty() && line.length() < 256 && PKG.matcher(line).matches()) {
                                loaded.add(line);
                            }
                        }
                    } catch (Exception e) {
                        toast("Restore gagal: " + e.getMessage());
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            selected.clear();
                            selected.addAll(loaded);
                            afterBulkChange();
                            applyFilter();
                            toast("Restore berhasil (" + loaded.size() + " app)");
                        }
                    });
                }
            });
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ----------------------------------------------------------------- data

    private void saveSelected() {
        prefs.edit().putStringSet(Config.KEY_LOCKED, new HashSet<String>(selected)).apply();
    }

    private void loadApps() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                final List<AppItem> items = new ArrayList<AppItem>();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                    CharSequence l = ai.loadLabel(pm);
                    items.add(new AppItem(l != null ? l.toString() : ai.packageName,
                            ai.packageName, sys, ai));
                }
                Collections.sort(items, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem a, AppItem b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(items);
                        applyFilter();
                    }
                });
            }
        });
    }

    /** Yang dipilih tampil di atas, sisanya urut abjad. */
    private void applyFilter() {
        List<AppItem> top = new ArrayList<AppItem>();
        List<AppItem> rest = new ArrayList<AppItem>();
        for (AppItem a : all) {
            if (a.system && !showSystem) continue;
            if (!query.isEmpty()
                    && !a.label.toLowerCase(Locale.ROOT).contains(query)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(query)) continue;
            (selected.contains(a.pkg) ? top : rest).add(a);
        }
        shown.clear();
        shown.addAll(top);
        shown.addAll(rest);
        adapter.notifyDataSetChanged();
    }

    // -------------------------------------------------------------- adapter

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Row row;
            if (convert == null) {
                row = new Row(MainActivity.this);
                convert = row.root;
                convert.setTag(row);
            } else {
                row = (Row) convert.getTag();
            }
            AppItem a = shown.get(position);
            row.name.setText(a.label);
            row.pkg.setText(a.pkg);
            row.check.setChecked(selected.contains(a.pkg));
            Drawable d = iconCache.get(a.pkg);
            if (d == null) {
                d = a.info.loadIcon(getPackageManager());
                iconCache.put(a.pkg, d);
            }
            row.icon.setImageDrawable(d);
            return convert;
        }
    }

    private final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView name;
        final TextView pkg;
        final CheckBox check;

        Row(Activity c) {
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(16), dp(10), dp(16), dp(10));

            icon = new ImageView(c);
            root.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMargins(dp(16), 0, dp(8), 0);

            name = new TextView(c);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            name.setSingleLine(true);
            pkg = new TextView(c);
            pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            pkg.setTextColor(0xFFA0A0A0);
            pkg.setSingleLine(true);
            texts.addView(name);
            texts.addView(pkg);
            root.addView(texts, tp);

            check = new CheckBox(c);
            check.setClickable(false);
            check.setFocusable(false);
            root.addView(check);
        }
    }
}
