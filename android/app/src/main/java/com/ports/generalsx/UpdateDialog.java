// GeneralsX @feature Android port 09/11/2026
//
// Phase 2 UI: the three screens the update goes through -- "a new version is available" (with a
// Download button), "downloading" (with progress and a Cancel), and "ready to install" (with an
// Install button). Phase 1's single dialog is the first of the three, unchanged in shape when the
// release publishes no APK.
//
// Everything here is presentation. The download and the security checks live in ApkInstaller; this
// class only decides which dialog to show and hands user choices to it.

package com.ports.generalsx;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.List;
import java.util.Locale;

final class UpdateDialog {

    private UpdateDialog() {
    }

    /** The entry point, called from UpdateChecker once a newer version is known. */
    static void show(Activity activity, UpdateChecker.Update update) {
        UpdateChecker.markNotified(activity, update.versionCode);

        if (ApkInstaller.isRunning()) {
            // The screen was recreated while a download was in flight: pick the download back up
            // rather than offering it again.
            attachProgress(activity);
            return;
        }

        File apk = ApkInstaller.apkFile(activity, update);
        if (update.hasApk() && ApkInstaller.isVerified(activity, update) && apk.isFile()) {
            // Already downloaded and verified on an earlier launch: no second download.
            showReady(activity, update, apk);
            return;
        }

        showAvailable(activity, update);
    }

    /**
     * Called once at launch. If a download from the previous screen is still running (the process
     * survived an activity recreation), re-attach to it; otherwise clear stale APKs, keeping the
     * verified one. Never runs while a download is in flight, which is why the two are exclusive.
     */
    static void resumeIfDownloading(Activity activity) {
        if (ApkInstaller.isRunning()) {
            attachProgress(activity);
        } else {
            ApkInstaller.cleanup(activity);
        }
    }

    // ---------------------------------------------------------------------------------------
    // "A new version is available"

    private static void showAvailable(final Activity activity, final UpdateChecker.Update update) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dim(activity, R.dimen.gzh_card_padding);
        body.setPadding(pad, pad, pad, 0);
        addVersionAndNotes(activity, body, update);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_dialog_title)
            .setIcon(R.mipmap.ic_launcher)
            .setView(body)
            .setNegativeButton(R.string.update_dialog_later, null);

        if (update.hasApk()) {
            builder.setPositiveButton(R.string.update_dialog_download, (d, w) ->
                startDownload(activity, update));
        }
        builder.show();
    }

    // ---------------------------------------------------------------------------------------
    // "Downloading"

    private static void startDownload(final Activity activity, final UpdateChecker.Update update) {
        ProgressUi ui = new ProgressUi(activity, update);
        ui.show();
        ApkInstaller.start(activity, update, ui);
    }

    private static void attachProgress(final Activity activity) {
        UpdateChecker.Update update = ApkInstaller.runningUpdate();
        if (update == null) {
            return;
        }
        ProgressUi ui = new ProgressUi(activity, update);
        ui.show();
        ApkInstaller.attach(activity, ui);
    }

    /** The progress dialog and the callbacks that keep it current. */
    private static final class ProgressUi implements ApkInstaller.Callbacks {
        private final Activity activity;
        private final UpdateChecker.Update update;
        private final androidx.appcompat.app.AlertDialog dialog;
        private final LinearProgressIndicator bar;
        private final TextView percent;
        private final TextView sizes;

        ProgressUi(Activity activity, UpdateChecker.Update update) {
            this.activity = activity;
            this.update = update;

            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = UiKit.dim(activity, R.dimen.gzh_card_padding);
            body.setPadding(pad, pad, pad, 0);

            bar = new LinearProgressIndicator(activity);
            bar.setIndeterminate(false);
            bar.setMax(100);
            body.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            percent = UiKit.body(body, activity.getString(R.string.update_progress_percent, 0));
            sizes = UiKit.supporting(body, "");

            dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_progress_title)
                .setView(body)
                .setNegativeButton(R.string.update_dialog_cancel, (d, w) -> ApkInstaller.cancel())
                .setCancelable(false)
                .create();
        }

        void show() {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                dialog.show();
            }
        }

        @Override
        public void onProgress(long bytes, long total) {
            int pct = total > 0 ? (int) (bytes * 100 / total) : 0;
            bar.setIndeterminate(total <= 0);
            if (total > 0) {
                bar.setProgressCompat(pct, true);
            }
            percent.setText(activity.getString(R.string.update_progress_percent, pct));
            sizes.setText(total > 0
                ? activity.getString(R.string.update_progress_sizes,
                    humanSize(bytes), humanSize(total))
                : humanSize(bytes));
        }

        @Override
        public void onVerifying() {
            bar.setIndeterminate(true);
            percent.setText(R.string.update_progress_verifying);
            sizes.setText("");
        }

        @Override
        public void onReady(File apk, UpdateChecker.Update u) {
            dismiss();
            showReady(activity, u, apk);
        }

        @Override
        public void onFailed(String code) {
            dismiss();
            showError(activity, update, code);
        }

        private void dismiss() {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // "Ready to install"

    static void showReady(final Activity activity, final UpdateChecker.Update update, final File apk) {
        String label = update.versionName.isEmpty()
            ? Integer.toString(update.versionCode) : update.versionName;
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dim(activity, R.dimen.gzh_card_padding);
        body.setPadding(pad, pad, pad, 0);

        TextView version = new TextView(activity);
        version.setText(activity.getString(R.string.update_dialog_version, label));
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.dim(activity, R.dimen.gzh_text_body));
        version.setTextColor(UiKit.color(activity, R.color.gzh_primary));
        version.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(version);
        body.addView(UiKit.supporting(body, activity.getString(R.string.update_ready_verified)));

        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_ready_title)
            .setIcon(R.mipmap.ic_launcher)
            .setView(body)
            .setPositiveButton(R.string.update_ready_install, (d, w) ->
                ApkInstaller.requestInstall(activity, apk))
            .setNegativeButton(R.string.update_dialog_later, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Failure

    private static void showError(final Activity activity, final UpdateChecker.Update update,
                                  String code) {
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_error_title)
            .setMessage(ApkInstaller.message(activity, code))
            .setPositiveButton(R.string.update_error_retry, (d, w) -> {
                if (update.hasApk()) {
                    startDownload(activity, update);
                }
            })
            .setNegativeButton(R.string.update_dialog_later, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Shared bits

    /** The version line and the bulleted release notes, capped so long notes scroll. */
    private static void addVersionAndNotes(Activity activity, LinearLayout body,
                                           UpdateChecker.Update update) {
        String label = update.versionName.isEmpty()
            ? Integer.toString(update.versionCode) : update.versionName;
        TextView version = new TextView(activity);
        version.setText(activity.getString(R.string.update_dialog_version, label));
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.dim(activity, R.dimen.gzh_text_body));
        version.setTextColor(UiKit.color(activity, R.color.gzh_primary));
        version.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(version);

        if (update.releaseNotes.isEmpty()) {
            return;
        }
        TextView header = new TextView(activity);
        header.setText(R.string.update_dialog_whats_new);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.dim(activity, R.dimen.gzh_text_body));
        header.setTextColor(UiKit.color(activity, R.color.gzh_on_surface));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = UiKit.dim(activity, R.dimen.gzh_item_gap);
        body.addView(header, headerParams);

        TextView notes = new TextView(activity);
        notes.setText(bullets(update.releaseNotes));
        notes.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.dim(activity, R.dimen.gzh_text_body));
        notes.setTextColor(UiKit.color(activity, R.color.gzh_on_surface_variant));
        notes.setLineSpacing(0f, 1.3f);

        int maxHeight = Math.round(
            activity.getResources().getDisplayMetrics().heightPixels * 0.45f);
        ScrollView scroll = new CappedScrollView(activity, maxHeight);
        scroll.addView(notes, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = UiKit.dp(activity, 4);
        body.addView(scroll, scrollParams);
    }

    /** "- note" lines; the notes are plain text and are never interpreted as markup. */
    private static String bullets(List<String> notes) {
        StringBuilder sb = new StringBuilder();
        for (String note : notes) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("\u2022 ").append(note);
        }
        return sb.toString();
    }

    /** A compact byte count. Locale.US keeps the decimal separator stable regardless of language. */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /**
     * A ScrollView that wraps its content but never grows past {@code maxHeight}, so a long release
     * notes list scrolls instead of pushing the dialog off the screen.
     */
    private static final class CappedScrollView extends ScrollView {
        private final int maxHeight;

        CappedScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, capped);
        }
    }
}
