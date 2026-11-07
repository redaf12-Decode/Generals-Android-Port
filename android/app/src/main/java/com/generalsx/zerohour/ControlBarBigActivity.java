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
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * GeneralsX @feature Android port 07/11/2026 ControlBar BIG Importer (Tools): select a .big,
 * inspect what it holds for the control bar, and import those files as a managed mod. See
 * {@link ControlBarBig} for the archive format and the file rules.
 */
public class ControlBarBigActivity extends Activity {
    private static final int REQUEST_PICK_BIG = 2401;

    private LinearLayout list;
    private ControlBarBig.Detection detection;
    private boolean busy;
    private AlertDialog progress;
    private ModImportService.CancellationSignal cancellation;
    private Thread workerThread;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);
        setTitle(R.string.cbbig_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.cbbig_title),
            R.drawable.ic_gzh_download, getString(R.string.cbbig_select), this::selectArchive);
        LinearLayout page = UiKit.scrollingPage(root);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        InsetUtil.applySafeInsets(root);
        render();
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
        ControlBarBig.discard(detection);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ render

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();

        LinearLayout card = UiKit.card(list);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_download, getString(R.string.cbbig_select), false);
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_folder,
            getString(R.string.cbbig_select), this::selectArchive).setEnabled(!busy);
        UiKit.helpText(card, getString(R.string.cbbig_select_help));

        if (detection == null) {
            UiKit.supporting(card, getString(R.string.cbbig_none_selected));
            return;
        }

        LinearLayout inspect = UiKit.card(list);
        UiKit.sectionHeader(inspect, R.drawable.ic_gzh_info, getString(R.string.cbbig_inspect), false);
        UiKit.body(inspect, detection.archiveName);
        UiKit.supporting(inspect, getString(R.string.cbbig_archive_entries, detection.totalEntries));
        addCategory(inspect, R.string.cbbig_cat_layout, detection.layouts);
        addCategory(inspect, R.string.cbbig_cat_scheme, detection.schemes);
        addCategory(inspect, R.string.cbbig_cat_images, detection.images);
        addCategory(inspect, R.string.cbbig_cat_art, detection.art);

        LinearLayout importCard = UiKit.card(list);
        UiKit.sectionHeader(importCard, R.drawable.ic_gzh_save,
            getString(R.string.cbbig_import), false);
        UiKit.button(importCard, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_save,
            getString(R.string.cbbig_import), this::importControlBar).setEnabled(!busy);
        UiKit.helpText(importCard, getString(R.string.cbbig_import_help));
    }

    private void addCategory(LinearLayout parent, int labelRes, List<ControlBarBig.Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        UiKit.divider(parent);
        UiKit.body(parent, getString(R.string.cbbig_category_count,
            getString(labelRes), entries.size()));
        int shown = Math.min(entries.size(), 8);
        for (int i = 0; i < shown; i++) {
            UiKit.caption(parent, entries.get(i).path);
        }
        if (entries.size() > shown) {
            UiKit.caption(parent, getString(R.string.cbbig_more, entries.size() - shown));
        }
    }

    // ------------------------------------------------------------------ inspect

    private void selectArchive() {
        if (busy) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_BIG);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.mods_import_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_BIG || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        inspect(data.getData());
    }

    private void inspect(Uri uri) {
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(R.string.cbbig_inspecting);
        final String displayName = queryDisplayName(uri);
        workerThread = new Thread(() -> {
            try {
                ControlBarBig.Detection found = ControlBarBig.inspect(this, uri, displayName, signal);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        ControlBarBig.discard(found);
                        return;
                    }
                    ControlBarBig.discard(detection);
                    detection = found;
                    finishWork();
                    render();
                });
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> fail(getString(R.string.mods_import_cancelled)));
            } catch (ModImportService.ImportProblem e) {
                runOnUiThread(() -> fail(getString(e.messageRes)));
            } catch (Exception e) {
                runOnUiThread(() -> fail(describe(e)));
            }
        }, "GeneralsX-ControlBarInspect");
        workerThread.start();
    }

    // ------------------------------------------------------------------ import

    private void importControlBar() {
        if (busy || detection == null) {
            return;
        }
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(R.string.cbbig_importing);
        workerThread = new Thread(() -> {
            try {
                ControlBarBig.Imported imported = ControlBarBig.importAsMod(this, detection, signal);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    finishWork();
                    render();
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.cbbig_import_done_title)
                        .setMessage(getString(R.string.cbbig_import_done,
                            imported.modDir.getName(), imported.fileCount))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> fail(getString(R.string.mods_import_cancelled)));
            } catch (ModImportService.ImportProblem e) {
                runOnUiThread(() -> fail(getString(e.messageRes)));
            } catch (Exception e) {
                runOnUiThread(() -> fail(describe(e)));
            }
        }, "GeneralsX-ControlBarImport");
        workerThread.start();
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
            .setTitle(R.string.cbbig_working_title)
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

    private void finishWork() {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
        workerThread = null;
        cancellation = null;
        busy = false;
    }

    private void fail(String message) {
        finishWork();
        render();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.cbbig_failed_title)
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
