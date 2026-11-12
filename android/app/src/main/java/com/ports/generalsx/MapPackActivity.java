/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
*/

package com.ports.generalsx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * GeneralsX @feature Android port 07/11/2026 Map Packs (Tools): import map packs, switch them on
 * and off, and see how much of the 200-map budget they use. Packs live in
 * {@code <user data>/maps-manager}; the maps of the active ones are copied into the game's
 * {@code Maps} folder. See {@link MapPackManager} for the storage, the cap and the sync.
 */
public class MapPackActivity extends Activity {
    private static final int REQUEST_IMPORT_TREE = 2301;
    private static final int REQUEST_IMPORT_ZIP = 2302;
    private static final int REQUEST_EXPORT_TREE = 2303;
    private static final int REQUEST_EXPORT_ZIP = 2304;

    private LinearLayout list;
    private List<MapPackManager.Pack> packs;
    private boolean busy;
    private AlertDialog progress;
    private ModImportService.CancellationSignal cancellation;
    private Thread workerThread;
    private MapPackManager.Pack pendingExport;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);
        setTitle(R.string.mappack_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.mappack_title),
            R.drawable.ic_gzh_refresh, getString(R.string.mappack_refresh), this::refresh);
        LinearLayout page = UiKit.scrollingPage(root);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        InsetUtil.applySafeInsets(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (workerThread == null && packs != null) {
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        if (cancellation != null) {
            cancellation.cancel();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (progress != null) {
            progress.dismiss();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ render

    /** Reconciles the Maps folder with the saved active list, then shows the packs. */
    private void refresh() {
        MapPackManager.sync(this);
        packs = MapPackManager.listPacks(this);
        render();
    }

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        if (packs == null) {
            packs = MapPackManager.listPacks(this);
        }

        LinearLayout importCard = UiKit.card(list);
        UiKit.sectionHeader(importCard, R.drawable.ic_gzh_download,
            getString(R.string.mappack_import), false);
        LinearLayout row = UiKit.buttonRow(importCard);
        MaterialButton fromFolder = UiKit.button(row, UiKit.BTN_TONAL, R.drawable.ic_gzh_folder,
            getString(R.string.mappack_import_folder), this::importFolder);
        UiKit.share(fromFolder, true);
        MaterialButton fromZip = UiKit.button(row, UiKit.BTN_TONAL, R.drawable.ic_gzh_save,
            getString(R.string.mappack_import_zip), this::importZip);
        UiKit.share(fromZip, false);
        fromFolder.setEnabled(!busy);
        fromZip.setEnabled(!busy);
        UiKit.helpText(importCard, getString(R.string.mappack_import_help));

        LinearLayout statusCard = UiKit.card(list);
        UiKit.sectionHeader(statusCard, R.drawable.ic_gzh_sliders,
            getString(R.string.mappack_active_title), false);
        UiKit.body(statusCard, getString(R.string.mappack_active_count,
            MapPackManager.activeMapCount(this), MapPackManager.MAX_ACTIVE_MAPS));
        UiKit.supporting(statusCard, getString(R.string.mappack_installed_count,
            MapManager.listMaps(this).size()));
        UiKit.helpText(statusCard, getString(R.string.mappack_active_help));
        UiKit.supporting(statusCard, getString(R.string.mappack_location_help,
            MapPackManager.packsDir(this).getAbsolutePath(),
            MapManager.mapsDir(this).getAbsolutePath()));

        LinearLayout listCard = UiKit.card(list);
        UiKit.sectionHeader(listCard, R.drawable.ic_gzh_folder,
            getString(R.string.mappack_packs_title), false);
        if (packs.isEmpty()) {
            UiKit.supporting(listCard, getString(R.string.mappack_none));
        } else {
            for (MapPackManager.Pack pack : packs) {
                addPackRow(listCard, pack);
            }
        }
    }

    private void addPackRow(LinearLayout parent, MapPackManager.Pack pack) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(labels, labelParams);

        TextView name = UiKit.body(labels, pack.name);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        UiKit.caption(labels, getString(R.string.mappack_row_supporting, pack.mapCount(),
            getString(pack.active ? R.string.mappack_state_active : R.string.mappack_state_inactive)));
        labels.setClickable(true);
        labels.setOnClickListener(v -> showPack(pack));

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(pack.active);
        toggle.setContentDescription(pack.name);
        toggle.setEnabled(!busy);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked != pack.active) {
                togglePack(pack, checked);
            }
        });
        row.addView(toggle);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = UiKit.dp(this, 6);
        parent.addView(row, rowParams);

        if (pack.active) {
            UiKit.chip(parent, R.drawable.ic_gzh_check, getString(R.string.mappack_chip_active),
                R.color.gzh_status_ok, R.color.gzh_surface_container_high);
        } else if (MapPackManager.activeCountWith(this, pack.name) > MapPackManager.MAX_ACTIVE_MAPS) {
            // Say so before the switch is flipped: activation would be refused whole.
            UiKit.chip(parent, R.drawable.ic_gzh_info, getString(R.string.mappack_limit_title),
                R.color.gzh_status_warn, R.color.gzh_surface_container_high);
        }
        parent.addView(actionRow(pack));
        UiKit.divider(parent);
    }

    private View actionRow(MapPackManager.Pack pack) {
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tools.addView(UiKit.iconButton(this, R.drawable.ic_gzh_info,
            getString(R.string.mappack_details_button), () -> showPack(pack)));
        tools.addView(UiKit.iconButton(this, R.drawable.ic_gzh_trash,
            getString(R.string.mappack_delete_button), () -> confirmDelete(pack)));
        return tools;
    }

    // ------------------------------------------------------------------ pack page

    private void showPack(MapPackManager.Pack pack) {
        StringBuilder names = new StringBuilder();
        for (String name : pack.mapNames()) {
            if (names.length() > 0) {
                names.append('\n');
            }
            names.append(name);
        }
        new AlertDialog.Builder(this)
            .setTitle(pack.name)
            .setMessage(getString(R.string.mappack_detail, pack.mapCount(), pack.source, names))
            .setPositiveButton(pack.active ? R.string.mappack_deactivate : R.string.mappack_activate,
                (d, w) -> togglePack(pack, !pack.active))
            .setNeutralButton(R.string.mappack_export, (d, w) -> chooseExport(pack))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ------------------------------------------------------------------ export

    private void chooseExport(MapPackManager.Pack pack) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.mappack_export_title)
            .setItems(new CharSequence[] {
                    getString(R.string.mappack_export_folder),
                    getString(R.string.mappack_export_zip) },
                (d, which) -> {
                    if (which == 0) {
                        pick(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_EXPORT_TREE, pack);
                    } else {
                        pick(createZipIntent(pack.name + ".zip"), REQUEST_EXPORT_ZIP, pack);
                    }
                })
            .show();
    }

    private Intent createZipIntent(String suggestedName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        return intent;
    }

    // ------------------------------------------------------------------ import

    private void importFolder() {
        pick(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_IMPORT_TREE, null);
    }

    private void importZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
            new String[] { "application/zip", "application/x-zip-compressed", "application/octet-stream" });
        pick(intent, REQUEST_IMPORT_ZIP, null);
    }

    private void pick(Intent intent, int request, MapPackManager.Pack pack) {
        if (busy) {
            return;
        }
        pendingExport = pack;
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            pendingExport = null;
            Toast.makeText(this, R.string.mods_import_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExport = null;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_TREE) {
            startImport(uri, true);
        } else if (requestCode == REQUEST_IMPORT_ZIP) {
            startImport(uri, false);
        } else if (requestCode == REQUEST_EXPORT_TREE && pendingExport != null) {
            startExport(uri, true, pendingExport);
        } else if (requestCode == REQUEST_EXPORT_ZIP && pendingExport != null) {
            startExport(uri, false, pendingExport);
        }
    }

    private void startImport(Uri uri, boolean folder) {
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(R.string.mappack_import_working);
        final String displayName = folder ? null : queryDisplayName(uri);
        workerThread = new Thread(() -> {
            try {
                MapPackManager.Plan plan = folder
                    ? MapPackManager.prepareTree(this, uri, signal)
                    : MapPackManager.prepareZip(this, uri, displayName, signal);
                MapPackManager.Pack pack = MapPackManager.commit(this, plan);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    finishWork(getString(R.string.mappack_import_done_title),
                        getString(R.string.mappack_import_done, pack.name, pack.mapCount()));
                });
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_import_failed_title),
                    getString(R.string.mods_import_cancelled)));
            } catch (ModImportService.ImportProblem e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_import_failed_title),
                    getString(e.messageRes)));
            } catch (Exception e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_import_failed_title),
                    describe(e)));
            }
        }, "GeneralsX-MapPackImport");
        workerThread.start();
    }

    // ------------------------------------------------------------------ export

    private void startExport(Uri uri, boolean folder, MapPackManager.Pack pack) {
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(R.string.mappack_export_working);
        workerThread = new Thread(() -> {
            try {
                int count = folder
                    ? MapPackManager.exportFolder(this, pack, uri, signal)
                    : MapPackManager.exportZip(this, pack, uri, signal);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    finishWork(getString(R.string.mappack_export_done_title),
                        getString(R.string.mappack_export_done, pack.name, count));
                });
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_export_failed_title),
                    getString(R.string.mods_import_cancelled)));
            } catch (Exception e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_export_failed_title),
                    describe(e)));
            }
        }, "GeneralsX-MapPackExport");
        workerThread.start();
    }

    // ------------------------------------------------------------------ activate

    private void togglePack(MapPackManager.Pack pack, boolean activate) {
        if (busy) {
            return;
        }
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(activate
            ? R.string.mappack_activating : R.string.mappack_deactivating);
        workerThread = new Thread(() -> {
            try {
                MapPackManager.Result result = activate
                    ? MapPackManager.activate(this, pack, signal)
                    : MapPackManager.deactivate(this, pack, signal);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    finishWork(getString(activate ? R.string.mappack_activated_title
                            : R.string.mappack_deactivated_title),
                        getString(activate ? R.string.mappack_activated : R.string.mappack_deactivated,
                            pack.name, result.activeMaps, MapPackManager.MAX_ACTIVE_MAPS));
                });
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_failed_title),
                    getString(R.string.mods_import_cancelled)));
            } catch (ModImportService.ImportProblem e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_limit_title),
                    getString(e.messageRes)));
            } catch (Exception e) {
                runOnUiThread(() -> finishWork(getString(R.string.mappack_failed_title), describe(e)));
            }
        }, "GeneralsX-MapPackToggle");
        workerThread.start();
    }

    // ------------------------------------------------------------------ delete

    private void confirmDelete(MapPackManager.Pack pack) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.mappack_delete_title)
            .setMessage(getString(R.string.mappack_delete_message, pack.name))
            .setPositiveButton(R.string.mappack_delete_button, (d, w) -> {
                boolean wasActive = pack.active;
                boolean ok = MapPackManager.delete(this, pack);
                if (ok && wasActive) {
                    MapPackManager.sync(this);
                }
                Toast.makeText(this, ok ? R.string.mappack_delete_done : R.string.mappack_delete_failed,
                    Toast.LENGTH_LONG).show();
                refresh();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ------------------------------------------------------------------ progress

    private ModImportService.CancellationSignal startProgress(int messageRes) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 20);
        body.setPadding(pad, UiKit.dp(this, 8), pad, 0);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        body.addView(spinner);
        TextView message = UiKit.supporting(body, getString(messageRes));
        progress = new AlertDialog.Builder(this)
            .setTitle(R.string.mappack_working_title)
            .setView(body)
            .setNegativeButton(R.string.mods_import_cancel_action, null)
            .setCancelable(false)
            .show();
        cancellation = new ModImportService.CancellationSignal();
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancellation.cancel();
            if (workerThread != null) {
                workerThread.interrupt();
            }
            message.setText(R.string.mods_import_cancelling);
            v.setEnabled(false);
        });
        return cancellation;
    }

    private void finishWork(String title, String message) {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
        workerThread = null;
        cancellation = null;
        pendingExport = null;
        busy = false;
        refresh();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    return cursor.getString(col);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return uri.getLastPathSegment();
    }

    private static String describe(Exception e) {
        String detail = e.getMessage();
        return detail == null || detail.trim().isEmpty() ? e.getClass().getSimpleName() : detail;
    }
}
