package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GeneralsX @feature Android port 09/11/2026 A simple "a newer version exists" check, separate
 * from {@link UpdateManager}.
 *
 * {@code UpdateManager} replaces the engine inside an already-installed APK from a signed
 * manifest. This class does the other, much smaller job: it reads a plain, unsigned
 * {@code update.json} published on the project's own GitHub repository, compares its numeric
 * {@code versionCode} with the version code Android reports for this install, and, when the
 * remote one is higher, shows a dialog with the version name and the release notes. It downloads
 * nothing and installs nothing -- Phase 1 is a notification only.
 *
 * The URL is a compile-time constant ({@link #UPDATE_URL}), fetched over HTTPS. The check runs on
 * a background thread, is attempted at most once per application session, and any failure
 * (offline, timeout, HTTP error, malformed JSON) is swallowed silently: the launcher keeps working
 * exactly as it would have without the check.
 *
 * The remote JSON is untrusted input. It is only ever read for three fields, the version code must
 * be a non-negative integer (a JSON number or a string of digits -- never a sign, a decimal or an
 * exponent), the body is size-capped, and the release notes are shown as plain text. Nothing in
 * the JSON is executed, and no Android permission beyond INTERNET (already declared) is used.
 */
final class UpdateChecker {

    private static final String TAG = "GXAppUpdate";

    /** Where the published version lives. Must stay HTTPS; there is no other configuration. */
    static final String UPDATE_URL =
        "https://raw.githubusercontent.com/redaf12-Decode/Generals-Android-Port/New-app/update.json";

    /** A few kilobytes is generous for this file; anything larger is not ours and is refused. */
    private static final int MAX_BYTES = 128 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    private static final String PREFS = "gx_app_update";
    private static final String KEY_NOTIFIED = "notified_version_code";

    /** Once per process, however many times the launcher screen is created. */
    private static boolean sCheckedThisSession;

    private UpdateChecker() {
    }

    /** The remote version, or null when the check could not produce one. */
    static final class Update {
        final int versionCode;
        final String versionName;
        final List<String> releaseNotes;

        Update(int versionCode, String versionName, List<String> releaseNotes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.releaseNotes = releaseNotes;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Version information

    /** The version code Android has for this installed package, or 0 if it cannot be read. */
    static int installedVersionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long code = info.getLongVersionCode();
            return code > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) code;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The installed version name, or "" if it cannot be read. */
    static String installedVersionName(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Numeric comparison of the remote code against the installed one. Never uses the name. */
    static boolean isNewer(Update update, int installedVersionCode) {
        return update != null && update.versionCode > installedVersionCode;
    }

    // ---------------------------------------------------------------------------------------
    // Parsing

    /**
     * Reads an {@link Update} out of the raw JSON, or null when it is malformed or its version
     * code is missing, negative or not a plain non-negative integer.
     */
    static Update parse(String json) {
        if (json == null) {
            return null;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
        int versionCode = readVersionCode(obj);
        if (versionCode < 0) {
            return null;
        }
        String versionName = obj.optString("versionName", "").trim();

        List<String> notes = new ArrayList<>();
        JSONArray array = obj.optJSONArray("releaseNotes");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof String) {
                    String note = ((String) item).trim();
                    if (!note.isEmpty()) {
                        notes.add(note);
                    }
                }
            }
        }
        return new Update(versionCode, versionName, Collections.unmodifiableList(notes));
    }

    /**
     * A version code accepted as a JSON number or as a string of digits. Anything else -- a sign,
     * a decimal point, an exponent, an empty string, a value past Integer.MAX_VALUE -- is refused
     * rather than coerced.
     */
    private static int readVersionCode(JSONObject obj) {
        Object raw = obj.opt("versionCode");
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            // A fractional version code (1.5) is as invalid as a negative one; only whole
            // numbers up to Integer.MAX_VALUE are accepted.
            if (value != Math.floor(value) || value < 0 || value > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) value;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (!text.matches("\\d+")) {
                return -1;
            }
            try {
                long value = Long.parseLong(text);
                return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------
    // Checking

    /**
     * Blocking; call off the UI thread. Returns the remote version only when it is newer than the
     * installed one, otherwise null. Performs at most one network request per application session.
     */
    static Update check(Context ctx) {
        synchronized (UpdateChecker.class) {
            if (sCheckedThisSession) {
                return null;
            }
            sCheckedThisSession = true;
        }
        try {
            byte[] body = fetch(UPDATE_URL, MAX_BYTES);
            Update update = parse(new String(body, StandardCharsets.UTF_8));
            return isNewer(update, installedVersionCode(ctx)) ? update : null;
        } catch (IOException e) {
            // Offline, a timeout, an HTTP error: nothing to show and nothing to fix.
            Log.i(TAG, "update check skipped: " + e);
            return null;
        } catch (RuntimeException e) {
            // A JSON shape this class did not expect must not take the launcher down with it.
            Log.i(TAG, "update check skipped: " + e);
            return null;
        }
    }

    /** Runs {@link #check} on a background thread and shows the dialog on the UI thread. */
    static void maybeCheck(final Activity activity) {
        new Thread(() -> {
            final Update update = check(activity);
            if (update == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (!shouldNotify(activity, update)) {
                    return;
                }
                showDialog(activity, update);
            });
        }, "gx-app-update").start();
    }

    // ---------------------------------------------------------------------------------------
    // Notification memory

    /** The version code whose notification the user has already seen, or 0. */
    static int notifiedVersionCode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_NOTIFIED, 0);
    }

    static void markNotified(Context ctx, int versionCode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NOTIFIED, versionCode).apply();
    }

    /** True unless this exact version has already been shown (a newer one always notifies). */
    static boolean shouldNotify(Context ctx, Update update) {
        return update != null && update.versionCode > notifiedVersionCode(ctx);
    }

    // ---------------------------------------------------------------------------------------
    // The dialog

    static void showDialog(final Activity activity, Update update) {
        markNotified(activity, update.versionCode);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dim(activity, R.dimen.gzh_card_padding);
        body.setPadding(pad, pad, pad, 0);

        String label = update.versionName.isEmpty()
            ? Integer.toString(update.versionCode) : update.versionName;
        TextView version = new TextView(activity);
        version.setText(activity.getString(R.string.update_dialog_version, label));
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.dim(activity, R.dimen.gzh_text_body));
        version.setTextColor(UiKit.color(activity, R.color.gzh_primary));
        version.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(version);

        if (!update.releaseNotes.isEmpty()) {
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

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_dialog_title)
            .setIcon(R.mipmap.ic_launcher)
            .setView(body)
            .setPositiveButton(R.string.update_dialog_later, null)
            .show();
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

    /**
     * A ScrollView that wraps its content but never grows past {@code maxHeight}, so a long release
     * notes list scrolls instead of pushing the dialog off the screen. A plain LinearLayout child
     * has no maxHeight, hence the override.
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

    // ---------------------------------------------------------------------------------------
    // Transfer

    private static byte[] fetch(String url, int maxBytes) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        throw new IOException("update.json is larger than " + maxBytes + " bytes");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
