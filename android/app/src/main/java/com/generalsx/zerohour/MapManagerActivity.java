/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
*/

package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

/**
 * GeneralsX @feature Android port 07/11/2026 Map Manager (Tools): browse the maps installed in
 * {@code <user data>/Maps}, import one from a folder or a ZIP, and export one as a folder or a
 * ZIP. See {@link MapManager} for where maps live and how an import is validated.
 */
public class MapManagerActivity extends Activity {
    private static final int REQUEST_IMPORT_TREE = 2301;
    private static final int REQUEST_IMPORT_ZIP = 2302;
    private static final int REQUEST_EXPORT_TREE = 2303;
    private static final int REQUEST_EXPORT_ZIP = 2304;

    private LinearLayout list;
    private List<MapManager.Map> maps;
    private boolean busy;
    private AlertDialog progress;
    private ModImportService.CancellationSignal cancellation;
    private Thread workerThread;
    private MapManager.Map exportMap;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);
        setTitle(R.string.mapman_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.mapman_title),
            R.drawable.ic_gzh_refresh, getString(R.string.mapman_refresh), this::refresh);
        LinearLayout page = UiKit.scrollingPage(root);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        InsetUtil.applySafeInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (workerThread == null) {
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

    private void refresh() {
        maps = MapManager.listMaps(this);
        render();
    }

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();

        LinearLayout importCard = UiKit.card(list);
        UiKit.sectionHeader(importCard, R.drawable.ic_gzh_download,
            getString(R.string.mapman_import), false);
        LinearLayout row = UiKit.buttonRow(importCard);
        MaterialButton fromFolder = UiKit.button(row, UiKit.BTN_TONAL, R.drawable.ic_gzh_folder,
            getString(R.string.mapman_import_folder), this::importFolder);
        UiKit.share(fromFolder, true);
        MaterialButton fromZip = UiKit.button(row, UiKit.BTN_TONAL, R.drawable.ic_gzh_save,
            getString(R.string.mapman_import_zip), this::importZip);
        UiKit.share(fromZip, false);
        fromFolder.setEnabled(!busy);
        fromZip.setEnabled(!busy);
        UiKit.helpText(importCard, getString(R.string.mapman_import_help));

        LinearLayout installed = UiKit.card(list);
        UiKit.sectionHeader(installed, R.drawable.ic_gzh_folder,
            getString(R.string.mapman_installed), false);
        if (maps == null || maps.isEmpty()) {
            UiKit.supporting(installed, getString(R.string.mapman_none));
        } else {
            for (MapManager.Map map : maps) {
                UiKit.listRow(installed, R.drawable.ic_gzh_folder, map.name,
                    getString(R.string.mapman_row_supporting, map.fileCount,
                        humanBytes(map.bytes)),
                    () -> showMap(map));
            }
        }
        UiKit.helpText(installed, getString(R.string.mapman_location_help,
            MapManager.mapsDir(this).getAbsolutePath()));
    }

    // ------------------------------------------------------------------ import

    private void importFolder() {
        pick(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_IMPORT_TREE);
    }

    private void importZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
            new String[] { "application/zip", "application/x-zip-compressed", "application/octet-stream" });
        pick(intent, REQUEST_IMPORT_ZIP);
    }

    private void pick(Intent intent, int request) {
        if (busy) {
            return;
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.mods_import_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        switch (requestCode) {
            case REQUEST_IMPORT_TREE:
                prepareImport(uri, true);
                break;
            case REQUEST_IMPORT_ZIP:
                prepareImport(uri, false);
                break;
            case REQUEST_EXPORT_TREE:
                runExport(uri, true);
                break;
            case REQUEST_EXPORT_ZIP:
                runExport(uri, false);
                break;
            default:
                break;
        }
    }

    private void prepareImport(Uri uri, boolean folder) {
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(R.string.mapman_import_working);
        workerThread = new Thread(() -> {
            try {
                MapManager.Plan plan = folder
                    ? MapManager.prepareTree(this, uri, signal)
                    : MapManager.prepareZip(this, uri, signal);
                List<String> conflicts = MapManager.conflicts(plan, this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        MapManager.abort(plan);
                        return;
                    }
                    if (conflicts.isEmpty()) {
                        commitImport(plan, MapManager.Policy.REPLACE);
                    } else {
                        askConflict(plan, conflicts);
                    }
                });
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> finishImport(null, getString(R.string.mods_import_cancelled)));
            } catch (ModImportService.ImportProblem e) {
                runOnUiThread(() -> finishImport(null, getString(e.messageRes)));
            } catch (Exception e) {
                runOnUiThread(() -> finishImport(null, describe(e)));
            }
        }, "GeneralsX-MapImport");
        workerThread.start();
    }

    private void askConflict(MapManager.Plan plan, List<String> conflicts) {
        dismissProgress();
        busy = false;
        String names = String.join(", ", conflicts);
        new AlertDialog.Builder(this)
            .setTitle(R.string.mapman_conflict_title)
            .setMessage(getString(R.string.mapman_conflict_message, names))
            .setPositiveButton(R.string.mapman_conflict_replace,
                (d, w) -> commitImport(plan, MapManager.Policy.REPLACE))
            .setNeutralButton(R.string.mapman_conflict_keep_both,
                (d, w) -> commitImport(plan, MapManager.Policy.KEEP_BOTH))
            .setNegativeButton(android.R.string.cancel, (d, w) -> {
                MapManager.abort(plan);
                render();
            })
            .setOnCancelListener(d -> MapManager.abort(plan))
            .show();
    }

    private void commitImport(MapManager.Plan plan, MapManager.Policy policy) {
        busy = true;
        render();
        startProgress(R.string.mapman_install_working);
        workerThread = new Thread(() -> {
            try {
                MapManager.Result result = MapManager.commit(this, plan, policy);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String message = getString(R.string.mapman_import_done,
                        result.installed.size(), result.replaced + result.keptBoth);
                    finishImport(message, null);
                });
            } catch (Exception e) {
                runOnUiThread(() -> finishImport(null, describe(e)));
            }
        }, "GeneralsX-MapInstall");
        workerThread.start();
    }

    private void finishImport(String success, String failure) {
        dismissProgress();
        workerThread = null;
        cancellation = null;
        busy = false;
        refresh();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (failure != null) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.mapman_import_failed_title)
                .setMessage(failure)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } else {
            new AlertDialog.Builder(this)
                .setTitle(R.string.mapman_import_done_title)
                .setMessage(success)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        }
    }

    // ------------------------------------------------------------------ map page

    private void showMap(MapManager.Map map) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 20);
        body.setPadding(pad, UiKit.dp(this, 4), pad, 0);
        UiKit.supporting(body, getString(R.string.mapman_map_detail, map.mapFile.getName(),
            map.fileCount, humanBytes(map.bytes)));
        UiKit.supporting(body, getString(R.string.mapman_map_preview,
            map.preview == null ? getString(R.string.mapman_map_no_preview) : map.preview.getName()));

        new AlertDialog.Builder(this)
            .setTitle(map.name)
            .setView(body)
            .setPositiveButton(R.string.mapman_export_folder, (d, w) -> exportFolder(map))
            .setNeutralButton(R.string.mapman_export_zip, (d, w) -> exportZip(map))
            .setNegativeButton(R.string.mapman_delete, (d, w) -> confirmDelete(map))
            .show();
    }

    private void exportFolder(MapManager.Map map) {
        exportMap = map;
        pick(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_EXPORT_TREE);
    }

    private void exportZip(MapManager.Map map) {
        exportMap = map;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, map.name + ".zip");
        pick(intent, REQUEST_EXPORT_ZIP);
    }

    private void runExport(Uri uri, boolean folder) {
        final MapManager.Map map = exportMap;
        exportMap = null;
        if (map == null || busy) {
            return;
        }
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(R.string.mapman_export_working);
        workerThread = new Thread(() -> {
            try {
                int count = folder
                    ? MapManager.exportFolder(this, map, uri, signal)
                    : MapManager.exportZip(this, map, uri, signal);
                runOnUiThread(() -> finishExport(map, count, null));
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> finishExport(null, 0, getString(R.string.mods_import_cancelled)));
            } catch (Exception e) {
                runOnUiThread(() -> finishExport(null, 0, describe(e)));
            }
        }, "GeneralsX-MapExport");
        workerThread.start();
    }

    private void finishExport(MapManager.Map map, int count, String failure) {
        dismissProgress();
        workerThread = null;
        cancellation = null;
        busy = false;
        render();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(failure == null ? R.string.mapman_export_done_title : R.string.mapman_export_failed_title)
            .setMessage(failure == null
                ? getString(R.string.mapman_export_done, map.name, count)
                : failure)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void confirmDelete(MapManager.Map map) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.mapman_delete_title)
            .setMessage(getString(R.string.mapman_delete_message, map.name))
            .setPositiveButton(R.string.mapman_delete, (d, w) -> {
                boolean ok = MapManager.delete(this, map);
                Toast.makeText(this, ok ? R.string.mapman_delete_done : R.string.mapman_delete_failed,
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
            .setTitle(R.string.mapman_working_title)
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

    private void dismissProgress() {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
    }

    private static String describe(Exception e) {
        String detail = e.getMessage();
        return detail == null || detail.trim().isEmpty() ? e.getClass().getSimpleName() : detail;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = { "KB", "MB", "GB", "TB" };
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }
}
