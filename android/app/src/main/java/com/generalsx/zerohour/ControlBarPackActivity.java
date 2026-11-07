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

import java.util.List;

/**
 * GeneralsX @feature Android port 07/11/2026 ControlBar BIG Importer (Tools): import ControlBar
 * packages from a folder or a ZIP, switch one on to unpack its archives into the game folder, and
 * switch it off again. Packages live in {@code <user data>/Controlsbar-Manager}; only the active
 * one's files are in the game folder. See {@link ControlBarPackManager} for the storage, the
 * install manifest and the language handling.
 */
public class ControlBarPackActivity extends Activity {
    private static final int REQUEST_IMPORT_TREE = 2401;
    private static final int REQUEST_IMPORT_ZIP = 2402;

    private LinearLayout list;
    private List<ControlBarPackManager.Pack> packs;
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
        setTitle(R.string.cbpack_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.cbpack_title),
            R.drawable.ic_gzh_refresh, getString(R.string.cbpack_refresh), this::refresh);
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

    /** Reconciles the game folder with the saved active package, then shows the packages. */
    private void refresh() {
        ControlBarPackManager.sync(this);
        packs = ControlBarPackManager.listPacks(this);
        render();
    }

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        if (packs == null) {
            packs = ControlBarPackManager.listPacks(this);
        }

        LinearLayout importCard = UiKit.card(list);
        UiKit.sectionHeader(importCard, R.drawable.ic_gzh_download,
            getString(R.string.cbpack_import), false);
        LinearLayout row = UiKit.buttonRow(importCard);
        UiKit.share(UiKit.button(row, UiKit.BTN_TONAL, R.drawable.ic_gzh_folder,
            getString(R.string.cbpack_import_folder), this::importFolder), true);
        UiKit.share(UiKit.button(row, UiKit.BTN_TONAL, R.drawable.ic_gzh_save,
            getString(R.string.cbpack_import_zip), this::importZip), false);
        UiKit.helpText(importCard, getString(R.string.cbpack_import_help));

        LinearLayout statusCard = UiKit.card(list);
        UiKit.sectionHeader(statusCard, R.drawable.ic_gzh_sliders,
            getString(R.string.cbpack_active_title), false);
        String active = ControlBarPackManager.activeName(this);
        UiKit.body(statusCard, active == null
            ? getString(R.string.cbpack_none_active)
            : getString(R.string.cbpack_active_name, active));
        UiKit.helpText(statusCard, getString(R.string.cbpack_active_help));
        UiKit.supporting(statusCard, getString(R.string.cbpack_location_help,
            ControlBarPackManager.packsDir(this).getAbsolutePath(),
            ControlBarPackManager.gameLanguage(this)));

        LinearLayout listCard = UiKit.card(list);
        UiKit.sectionHeader(listCard, R.drawable.ic_gzh_folder,
            getString(R.string.cbpack_packs_title), false);
        if (packs.isEmpty()) {
            UiKit.supporting(listCard, getString(R.string.cbpack_none));
        } else {
            for (ControlBarPackManager.Pack pack : packs) {
                addPackRow(listCard, pack);
            }
        }
    }

    private void addPackRow(LinearLayout parent, ControlBarPackManager.Pack pack) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = UiKit.body(labels, pack.name);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        UiKit.caption(labels, getString(R.string.cbpack_row_supporting, pack.archiveCount(),
            getString(pack.active ? R.string.cbpack_state_active : R.string.cbpack_state_inactive)));
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
            UiKit.chip(parent, R.drawable.ic_gzh_check, getString(R.string.cbpack_chip_active),
                R.color.gzh_status_ok, R.color.gzh_surface_container_high);
        }
        parent.addView(actionRow(pack));
        UiKit.divider(parent);
    }

    private View actionRow(ControlBarPackManager.Pack pack) {
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tools.addView(UiKit.iconButton(this, R.drawable.ic_gzh_info,
            getString(R.string.cbpack_details_button), () -> showPack(pack)));
        tools.addView(UiKit.iconButton(this, R.drawable.ic_gzh_trash,
            getString(R.string.cbpack_delete_button), () -> confirmDelete(pack)));
        return tools;
    }

    // ------------------------------------------------------------------ pack page

    private void showPack(ControlBarPackManager.Pack pack) {
        StringBuilder names = new StringBuilder();
        for (String name : pack.archiveNames()) {
            if (names.length() > 0) {
                names.append('\n');
            }
            names.append(name);
        }
        new AlertDialog.Builder(this)
            .setTitle(pack.name)
            .setMessage(getString(R.string.cbpack_detail, pack.archiveCount(), pack.source, names))
            .setPositiveButton(pack.active ? R.string.cbpack_deactivate : R.string.cbpack_activate,
                (d, w) -> togglePack(pack, !pack.active))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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
        if (requestCode == REQUEST_IMPORT_TREE) {
            startImport(uri, true);
        } else if (requestCode == REQUEST_IMPORT_ZIP) {
            startImport(uri, false);
        }
    }

    private void startImport(Uri uri, boolean folder) {
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(R.string.cbpack_import_working);
        final String displayName = folder ? null : queryDisplayName(uri);
        workerThread = new Thread(() -> {
            try {
                ControlBarPackManager.Plan plan = folder
                    ? ControlBarPackManager.prepareTree(this, uri, signal)
                    : ControlBarPackManager.prepareZip(this, uri, displayName, signal);
                ControlBarPackManager.Pack pack = ControlBarPackManager.commit(this, plan);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    finishWork(getString(R.string.cbpack_import_done_title),
                        getString(R.string.cbpack_import_done, pack.name, pack.archiveCount()));
                });
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> finishWork(getString(R.string.cbpack_import_failed_title),
                    getString(R.string.mods_import_cancelled)));
            } catch (ModImportService.ImportProblem e) {
                runOnUiThread(() -> finishWork(getString(R.string.cbpack_import_failed_title),
                    getString(e.messageRes)));
            } catch (Exception e) {
                runOnUiThread(() -> finishWork(getString(R.string.cbpack_import_failed_title),
                    describe(e)));
            }
        }, "GeneralsX-ControlBarPackImport");
        workerThread.start();
    }

    // ------------------------------------------------------------------ activate

    private void togglePack(ControlBarPackManager.Pack pack, boolean activate) {
        if (busy) {
            return;
        }
        busy = true;
        render();
        ModImportService.CancellationSignal signal = startProgress(activate
            ? R.string.cbpack_activating : R.string.cbpack_deactivating);
        workerThread = new Thread(() -> {
            try {
                if (activate) {
                    ControlBarPackManager.Result result =
                        ControlBarPackManager.activate(this, pack, signal);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        finishWork(getString(R.string.cbpack_activated_title),
                            getString(R.string.cbpack_activated, result.packName, result.fileCount));
                    });
                } else {
                    ControlBarPackManager.deactivate(this, pack, signal);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        finishWork(getString(R.string.cbpack_deactivated_title),
                            getString(R.string.cbpack_deactivated, pack.name));
                    });
                }
            } catch (ModImportService.ImportCancelledException e) {
                runOnUiThread(() -> finishWork(getString(R.string.cbpack_failed_title),
                    getString(R.string.mods_import_cancelled)));
            } catch (ModImportService.ImportProblem e) {
                runOnUiThread(() -> finishWork(getString(R.string.cbpack_failed_title),
                    getString(e.messageRes)));
            } catch (Exception e) {
                runOnUiThread(() -> finishWork(getString(R.string.cbpack_failed_title), describe(e)));
            }
        }, "GeneralsX-ControlBarPackToggle");
        workerThread.start();
    }

    // ------------------------------------------------------------------ delete

    private void confirmDelete(ControlBarPackManager.Pack pack) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.cbpack_delete_title)
            .setMessage(getString(R.string.cbpack_delete_message, pack.name))
            .setPositiveButton(R.string.cbpack_delete_button, (d, w) -> {
                boolean wasActive = pack.active;
                boolean ok = ControlBarPackManager.delete(this, pack);
                if (ok && wasActive) {
                    ControlBarPackManager.sync(this);
                }
                Toast.makeText(this, ok ? R.string.cbpack_delete_done : R.string.cbpack_delete_failed,
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
            .setTitle(R.string.cbpack_working_title)
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
