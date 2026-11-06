/*
**      Command & Conquer Generals Zero Hour(tm)
**      Copyright 2025 Electronic Arts Inc.
**
**      This program is free software: you can redistribute it and/or modify
**      it under the terms of the GNU General Public License as published by
**      the Free Software Foundation, either version 3 of the License, or
**      (at your option) any later version.
**
**      This program is distributed in the hope that it will be useful,
**      but WITHOUT ANY WARRANTY; without even the implied warranty of
**      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**      GNU General Public License for more details.
**
**      You should have received a copy of the GNU General Public License
**      along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android touch editor 05/11/2026 The floating icon in the match, and the
// small panel it opens: the way into the editor without leaving the game.
//
// It follows HotkeyOverlayView's two rules exactly. Only the icon and the open panel consume a
// touch; everything else returns false from onTouchEvent, so the gesture reaches SDL and the
// engine's own touch path is untouched. And a tool acts through the game's own layout or key
// path, never through a synthetic mouse message.
//
// A press that starts on the icon is owned from ACTION_DOWN, so it can never become a battlefield
// command. It stays a candidate tap until the finger passes the system touch slop; only then is it
// a drag, and a drag that ends is written back as a screen fraction. A cancelled gesture (the
// system took the pointer, the activity went away) puts the icon back where it was and runs
// nothing -- the case a device hits and a screenshot never shows.
//
// Cost: invalidate() only when something changed; onDraw reuses every Paint, RectF and Path.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

final class ToolboxOverlayView extends View {

    private static final String TAG = "GXToolbox";

    /** The panel's rows, in order. Each one is implemented; none is a placeholder. */
    private static final int TOOL_EDIT_LAYOUT = 0;
    private static final int TOOL_ADD_BUTTON = 1;
    private static final int TOOL_TOUCH_LAYER = 2;
    private static final int TOOL_BUTTON_SIZE = 3;
    private static final int TOOL_SAVE_DEFAULT = 4;
    private static final int TOOL_LOAD_DEFAULT = 5;
    private static final int TOOL_RESTORE_BUTTONS = 6;
    private static final int TOOL_HIDE_TOOLBOX = 7;
    private static final int TOOL_CLOSE = 8;
    private static final int TOOL_COUNT = 9;

    private static final float ROW_HEIGHT_DP = 40f;
    /** The shortest a row may be squeezed to before the panel is allowed to scroll. */
    private static final float MIN_ROW_HEIGHT_DP = 26f;
    private static final float PANEL_PADDING_DP = 10f;
    private static final float PANEL_WIDTH_DP = 220f;
    private static final float EDGE_GUTTER_DP = 10f;

    private static final float[] BUTTON_SIZE_PRESETS = {0.75f, 1.0f, 1.25f, 1.5f};

    private final Activity activity;
    private final HotkeyOverlayView hotkeyOverlay;
    private final String game;
    private ToolboxConfig config;

    private boolean panelOpen;
    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 Whether the floating icon is on screen.
     * This is a runtime view of {@link ToolboxConfig#enabled}: the single stored flag is what the
     * launcher switch and the in-game Hide row both write, and the overlay mirrors it here so a
     * change made in the launcher takes effect the next time the match regains focus.
     */
    private boolean iconVisible;
    private int activePointer = MotionEvent.INVALID_POINTER_ID;
    private boolean draggingIcon;
    private float downX;
    private float downY;
    private float iconDownX;
    private float iconDownY;
    private final float touchSlop;
    /** How far the panel is scrolled when it is taller than the window, and its scroll range. */
    private float panelScroll;
    private float panelScrollPx;
    private boolean scrollingPanel;

    private final RectF iconRect = new RectF();
    private final RectF panelRect = new RectF();
    private final RectF[] rowRects = new RectF[TOOL_COUNT];
    private final String[] rowLabels = new String[TOOL_COUNT];
    private final String[] rowValues = new String[TOOL_COUNT];

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The toolbox glyph, loaded once from the
     * bundled VectorDrawable (res/drawable/ic_gzh_toolbox.xml, traced from the Flaticon "Control"
     * icon) and drawn as a bitmap scaled to the icon box. Drawn on the UI thread, so it never
     * touches the render thread's frame budget; the raster happens once, at construction.
     */
    private android.graphics.Bitmap iconBitmap;
    private final android.graphics.Matrix iconMatrix = new android.graphics.Matrix();

    private final int accentColor;
    private final int panelBackground = 0xF014181C;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    private ToolboxOverlayView(Activity activity, HotkeyOverlayView hotkeyOverlay, String game) {
        super(activity);
        this.activity = activity;
        this.hotkeyOverlay = hotkeyOverlay;
        this.game = game;
        this.config = ToolboxConfig.load(activity, game);
        this.iconVisible = config.enabled;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.accentColor = resolveAccent();
        this.panelOpen = config.panelOpen;
        loadIconBitmap();
        for (int i = 0; i < TOOL_COUNT; ++i) {
            rowRects[i] = new RectF();
            rowLabels[i] = "";
            rowValues[i] = "";
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        installInsetsListener();
        rebuildRows();
    }

    private int resolveAccent() {
        try {
            return ThemeHelper.swatchColor(activity, ThemeHelper.getAccent(activity));
        } catch (Exception e) {
            return Color.rgb(220, 181, 86);
        }
    }

    /**
     * Rasterises the bundled toolbox VectorDrawable once, at the largest size the icon can take
     * (the large preset), so every density and every size setting samples from a sharp source and
     * onDraw only ever draws a bitmap. A failure here leaves the icon null and the draw falls back
     * to the panel's own outline, so a bad resource can never crash the game surface.
     */
    private void loadIconBitmap() {
        try {
            android.graphics.drawable.Drawable drawable = androidx.core.content.ContextCompat
                .getDrawable(activity, R.drawable.ic_gzh_toolbox);
            if (drawable == null) {
                return;
            }
            int side = Math.round(dp(ToolboxConfig.sizeDp(ToolboxConfig.SIZE_LARGE))
                * 2f);
            drawable.setBounds(0, 0, side, side);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(side, side,
                android.graphics.Bitmap.Config.ARGB_8888);
            drawable.draw(new Canvas(bitmap));
            iconBitmap = bitmap;
        } catch (Exception e) {
            Log.w(TAG, "could not load the toolbox icon drawable", e);
        }
    }

    /**
     * Builds the icon, or returns null when the launcher has it off for this game. A toolbox
     * that is off is not a hidden view: it does not exist.
     */
    static ToolboxOverlayView attach(Activity activity, HotkeyOverlayView hotkeyOverlay) {
        String game = SetupActivity.getSelectedGame(activity);
        ToolboxConfig.migrate(activity, game);
        ToolboxConfig config = ToolboxConfig.load(activity, game);
        if (!config.enabled) {
            Log.i(TAG, "floating toolbox is off for " + game + "; no overlay attached");
            return null;
        }
        ToolboxOverlayView overlay = new ToolboxOverlayView(activity, hotkeyOverlay, game);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        // Above HotkeyOverlayView (1000f): the toolbox may be opened over the button layer, and
        // its own hit areas are the only ones it consumes.
        overlay.setElevation(1100f);
        Log.i(TAG, "floating toolbox attached for " + game);
        return overlay;
    }

    private void installInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            insetLeft = bars.left;
            insetTop = bars.top;
            insetRight = bars.right;
            insetBottom = bars.bottom;
            invalidate();
            return windowInsets;
        });
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private float dp(float value) {
        return value * density();
    }

    // ------------------------------------------------------------------ geometry

    private void layoutIcon() {
        float size = dp(ToolboxConfig.sizeDp(config.size));
        float cx = config.x * getWidth();
        float cy = config.y * getHeight();
        float half = size * 0.5f;
        cx = TouchControlConfig.clamp(cx, insetLeft + dp(EDGE_GUTTER_DP) + half,
            getWidth() - insetRight - dp(EDGE_GUTTER_DP) - half);
        cy = TouchControlConfig.clamp(cy, insetTop + dp(EDGE_GUTTER_DP) + half,
            getHeight() - insetBottom - dp(EDGE_GUTTER_DP) - half);
        iconRect.set(cx - half, cy - half, cx + half, cy + half);
    }

    private void layoutPanel() {
        float width = dp(PANEL_WIDTH_DP);
        // The panel's nine rows at their full height are taller than a phone held in landscape,
        // so the row height gives way to the window before the panel does: every row, including
        // Hide and Close, stays reachable. Below MIN_ROW_HEIGHT_DP the panel is allowed to be
        // taller than the window and is scrolled instead, which is the smaller evil.
        float available = getHeight() - insetTop - insetBottom - dp(EDGE_GUTTER_DP) * 2f
            - dp(PANEL_PADDING_DP) * 2f;
        float rowHeight = Math.max(dp(MIN_ROW_HEIGHT_DP),
            Math.min(dp(ROW_HEIGHT_DP), available / TOOL_COUNT));
        float height = dp(PANEL_PADDING_DP) * 2f + TOOL_COUNT * rowHeight;
        float left;
        if (iconRect.centerX() > getWidth() * 0.5f) {
            left = iconRect.left - dp(10f) - width;
        } else {
            left = iconRect.right + dp(10f);
        }
        left = TouchControlConfig.clamp(left, insetLeft + dp(EDGE_GUTTER_DP),
            getWidth() - insetRight - dp(EDGE_GUTTER_DP) - width);
        float top = TouchControlConfig.clamp(iconRect.centerY() - height * 0.5f,
            insetTop + dp(EDGE_GUTTER_DP),
            Math.max(insetTop + dp(EDGE_GUTTER_DP),
                getHeight() - insetBottom - dp(EDGE_GUTTER_DP) - height));
        panelRect.set(left, top, left + width, top + height);
        // How far the panel's bottom overhangs the lowest edge it may reach: that is the scroll
        // range, so at full scroll the last row sits on the bottom edge instead of past it.
        panelScrollPx = Math.max(0f, panelRect.bottom
            - (getHeight() - insetBottom - dp(EDGE_GUTTER_DP)));
        panelScroll = TouchControlConfig.clamp(panelScroll, 0f, panelScrollPx);
        float rowTop = panelRect.top + dp(PANEL_PADDING_DP) - panelScroll;
        for (int i = 0; i < TOOL_COUNT; ++i) {
            rowRects[i].set(panelRect.left + dp(PANEL_PADDING_DP),
                rowTop + i * rowHeight,
                panelRect.right - dp(PANEL_PADDING_DP),
                rowTop + (i + 1) * rowHeight);
        }
    }

    /** Caches the row labels and values: onDraw must not build strings. */
    private void rebuildRows() {
        rowLabels[TOOL_EDIT_LAYOUT] = activity.getString(R.string.toolbox_tool_edit_layout);
        rowLabels[TOOL_ADD_BUTTON] = activity.getString(R.string.toolbox_tool_add_button);
        rowLabels[TOOL_TOUCH_LAYER] = activity.getString(R.string.toolbox_tool_touch_layer);
        rowValues[TOOL_TOUCH_LAYER] = activity.getString(
            TouchControlConfig.load(activity).enabled
                ? R.string.toolbox_state_on : R.string.toolbox_state_off);
        rowLabels[TOOL_BUTTON_SIZE] = activity.getString(R.string.toolbox_tool_button_size);
        rowValues[TOOL_BUTTON_SIZE] = Math.round(
            TouchControlConfig.load(activity).buttonScale * 100f) + "%";
        rowLabels[TOOL_SAVE_DEFAULT] = activity.getString(R.string.toolbox_tool_save_default);
        rowLabels[TOOL_LOAD_DEFAULT] = activity.getString(R.string.toolbox_tool_load_default);
        rowLabels[TOOL_RESTORE_BUTTONS] =
            activity.getString(R.string.toolbox_tool_restore_buttons);
        rowLabels[TOOL_HIDE_TOOLBOX] = activity.getString(R.string.toolbox_tool_hide_toolbox);
        rowLabels[TOOL_CLOSE] = activity.getString(R.string.toolbox_close);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // The icon is drawn only while it is visible: "Hide toolbox" removes it from the screen
        // entirely, rather than leaving an invisible tappable box behind.
        if (iconVisible) {
            layoutIcon();
            drawIcon(canvas);
        }
        if (panelOpen) {
            layoutPanel();
            drawPanel(canvas);
        }
    }

    private void drawIcon(Canvas canvas) {
        iconPaint.setColor(Color.rgb(22, 33, 44));
        iconPaint.setAlpha(Math.round(255f * config.opacity));
        iconPath.reset();
        switch (config.style) {
            case ToolboxConfig.STYLE_CIRCLE:
                iconPath.addOval(iconRect, Path.Direction.CW);
                break;
            case ToolboxConfig.STYLE_DIAMOND:
                iconPath.moveTo(iconRect.centerX(), iconRect.top);
                iconPath.lineTo(iconRect.right, iconRect.centerY());
                iconPath.lineTo(iconRect.centerX(), iconRect.bottom);
                iconPath.lineTo(iconRect.left, iconRect.centerY());
                iconPath.close();
                break;
            case ToolboxConfig.STYLE_ROUNDED:
            default:
                iconPath.addRoundRect(iconRect, dp(12f), dp(12f), Path.Direction.CW);
                break;
        }
        canvas.drawPath(iconPath, iconPaint);
        glyphPaint.setColor(accentColor);
        glyphPaint.setStrokeWidth(dp(2.5f));
        glyphPaint.setAlpha(255);
        canvas.drawPath(iconPath, glyphPaint);

        // The bundled Flaticon "Control" glyph, centred in the icon and kept at its own aspect
        // ratio: it is square, so it is scaled by the shorter side of the icon box.
        if (iconBitmap != null && !iconBitmap.isRecycled()) {
            float inset = iconRect.width() * 0.18f;
            float side = Math.min(iconRect.width(), iconRect.height()) - inset * 2f;
            if (side > 0f) {
                float left = iconRect.centerX() - side * 0.5f;
                float top = iconRect.centerY() - side * 0.5f;
                iconMatrix.reset();
                iconMatrix.setRectToRect(
                    new android.graphics.RectF(0, 0, iconBitmap.getWidth(),
                        iconBitmap.getHeight()),
                    new android.graphics.RectF(left, top, left + side, top + side),
                    android.graphics.Matrix.ScaleToFit.CENTER);
                iconPaint.setAlpha(255);
                canvas.drawBitmap(iconBitmap, iconMatrix, iconPaint);
            }
        }
    }

    private void drawPanel(Canvas canvas) {
        // A scrolled panel is taller than the window: its rows are clipped to the panel's own
        // box so a row never paints over the battlefield above or below it.
        canvas.save();
        canvas.clipRect(panelRect);
        panelPaint.setColor(panelBackground);
        panelPaint.setAlpha(0xF0);
        canvas.drawRoundRect(panelRect, dp(10f), dp(10f), panelPaint);
        glyphPaint.setColor(accentColor);
        glyphPaint.setStrokeWidth(dp(1.5f));
        canvas.drawRoundRect(panelRect, dp(10f), dp(10f), glyphPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(dp(13f));
        for (int i = 0; i < TOOL_COUNT; ++i) {
            RectF row = rowRects[i];
            if (i == TOOL_TOUCH_LAYER || i == TOOL_BUTTON_SIZE) {
                rowPaint.setColor(0x22FFFFFF);
                canvas.drawRoundRect(row, dp(6f), dp(6f), rowPaint);
            }
            textPaint.setColor(0xFFEDEDED);
            textPaint.setAlpha(255);
            canvas.drawText(rowLabels[i], row.left + dp(8f),
                row.centerY() - (textPaint.ascent() + textPaint.descent()) * 0.5f, textPaint);
            if (rowValues[i] != null && !rowValues[i].isEmpty()) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setColor(accentColor);
                canvas.drawText(rowValues[i], row.right - dp(8f),
                    row.centerY() - (textPaint.ascent() + textPaint.descent()) * 0.5f, textPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);
            }
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.restore();
    }

    // ------------------------------------------------------------------ test hooks

    /** Opens the panel and lays it out, for a test that drives the real touch path. */
    void ensurePanelOpenForTest() {
        panelOpen = true;
        layoutIcon();
        layoutPanel();
    }

    boolean isPanelOpenForTest() {
        return panelOpen;
    }

    /** True while the toolbox icon is on screen and tappable: the reopen affordance. */
    boolean iconIsVisibleForTest() {
        layoutIcon();
        return iconVisible && !iconRect.isEmpty()
            && iconRect.width() > 0f && iconRect.height() > 0f;
    }

    /** The icon-visibility flag the overlay draws and hit-tests with. */
    boolean iconVisibleFlagForTest() {
        return iconVisible;
    }

    android.graphics.PointF iconCenterForTest() {
        layoutIcon();
        return new android.graphics.PointF(iconRect.centerX(), iconRect.centerY());
    }

    /** A tool row's centre, by index, for a test that taps it. */
    android.graphics.PointF toolRowCenterForTest(int index) {
        layoutIcon();
        layoutPanel();
        RectF row = rowRects[index];
        return new android.graphics.PointF(row.centerX(), row.centerY());
    }

    /** The panel's own box, as laid out at this size and density. */
    android.graphics.RectF panelRectForTest() {
        layoutIcon();
        layoutPanel();
        return new android.graphics.RectF(panelRect);
    }

    /** How far the panel can be scrolled: zero when every row already fits the window. */
    float panelScrollRangeForTest() {
        layoutIcon();
        layoutPanel();
        return panelScrollPx;
    }

    /** The rows' own boxes, as laid out and scrolled, for a test that checks they stay on screen. */
    android.graphics.RectF rowRectForTest(int index) {
        layoutIcon();
        layoutPanel();
        return new android.graphics.RectF(rowRects[index]);
    }

    /** True when the bundled VectorDrawable rasterised to a bitmap with any opaque pixel. */
    boolean iconBitmapHasContentForTest() {
        if (iconBitmap == null || iconBitmap.isRecycled()) {
            return false;
        }
        for (int y = 0; y < iconBitmap.getHeight(); y += 2) {
            for (int x = 0; x < iconBitmap.getWidth(); x += 2) {
                if (android.graphics.Color.alpha(iconBitmap.getPixel(x, y)) > 16) {
                    return true;
                }
            }
        }
        return false;
    }

    android.graphics.RectF iconRectForTest() {
        layoutIcon();
        return new android.graphics.RectF(iconRect);
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                layoutIcon();
                if (panelOpen) {
                    layoutPanel();
                }
                downX = event.getX();
                downY = event.getY();
                scrollingPanel = false;
                if (iconVisible && iconRect.contains(downX, downY)) {
                    activePointer = event.getPointerId(0);
                    draggingIcon = false;
                    iconDownX = config.x;
                    iconDownY = config.y;
                    return true;
                }
                if (panelOpen && panelRect.contains(downX, downY)) {
                    activePointer = event.getPointerId(0);
                    draggingIcon = false;
                    return true;
                }
                // Neither the icon nor the open panel: refuse, so the gesture reaches SDL.
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activePointer == MotionEvent.INVALID_POINTER_ID) {
                    return false;
                }
                int index = event.findPointerIndex(activePointer);
                if (index < 0) {
                    return true;
                }
                float x = event.getX(index);
                float y = event.getY(index);
                if (draggingIcon) {
                    config.x = TouchControlConfig.clamp(config.x + (x - downX) / getWidth(),
                        0.02f, 0.98f);
                    config.y = TouchControlConfig.clamp(config.y + (y - downY) / getHeight(),
                        0.04f, 0.96f);
                    downX = x;
                    downY = y;
                    invalidate();
                    return true;
                }
                float dx = x - downX;
                float dy = y - downY;
                if (dx * dx + dy * dy >= touchSlop * touchSlop) {
                    // A finger inside the panel scrolls it when it overflows; anywhere else it
                    // drags the icon. Decided once, at the moment the finger leaves the slop.
                    if (scrollingPanel
                            || (panelScrollPx > 0f && !iconRect.contains(downX, downY))) {
                        scrollingPanel = true;
                        panelScroll = TouchControlConfig.clamp(panelScroll - (y - downY),
                            0f, panelScrollPx);
                        downX = x;
                        downY = y;
                        invalidate();
                        return true;
                    }
                    draggingIcon = true;
                    downX = x;
                    downY = y;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (activePointer == MotionEvent.INVALID_POINTER_ID) {
                    return false;
                }
                int index = event.getActionIndex();
                float x = event.getX(index);
                float y = event.getY(index);
                activePointer = MotionEvent.INVALID_POINTER_ID;
                if (draggingIcon) {
                    // A drag that ended: remember where the icon is, and run nothing.
                    draggingIcon = false;
                    config.save(activity, game);
                    invalidate();
                    return true;
                }
                if (scrollingPanel) {
                    // The gesture scrolled the panel; it is not a tap on a row.
                    scrollingPanel = false;
                    invalidate();
                    return true;
                }
                if (iconVisible && iconRect.contains(x, y)) {
                    panelOpen = !panelOpen;
                    panelScroll = 0f;
                    persistPanelState();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    rebuildRows();
                    invalidate();
                    return true;
                }
                for (int i = 0; i < TOOL_COUNT; ++i) {
                    if (panelRect.contains(x, y) && rowRects[i].contains(x, y)) {
                        runTool(i);
                        return true;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                // The system took the pointer: put the icon back and run nothing.
                if (draggingIcon) {
                    config.x = iconDownX;
                    config.y = iconDownY;
                }
                draggingIcon = false;
                scrollingPanel = false;
                activePointer = MotionEvent.INVALID_POINTER_ID;
                invalidate();
                return true;
            }
            default:
                return activePointer != MotionEvent.INVALID_POINTER_ID;
        }
    }

    private void runTool(int tool) {
        switch (tool) {
            case TOOL_EDIT_LAYOUT:
                panelOpen = false;
                persistPanelState();
                invalidate();
                if (hotkeyOverlay != null) {
                    hotkeyOverlay.enterEditor();
                }
                return;
            case TOOL_ADD_BUTTON:
                addButtonFromToolbox();
                return;
            case TOOL_TOUCH_LAYER:
                setTouchLayerEnabled(!TouchControlConfig.load(activity).enabled);
                return;
            case TOOL_BUTTON_SIZE:
                cycleButtonSize();
                return;
            case TOOL_SAVE_DEFAULT:
                saveDefaultLayout();
                return;
            case TOOL_LOAD_DEFAULT:
                loadDefaultLayout();
                return;
            case TOOL_RESTORE_BUTTONS:
                TouchControlConfig.reset(activity);
                reloadHotkeys();
                rebuildRows();
                invalidate();
                toast(activity.getString(R.string.toolbox_toast_buttons_restored));
                return;
            case TOOL_HIDE_TOOLBOX:
                hideToolbox();
                return;
            case TOOL_CLOSE:
                panelOpen = false;
                persistPanelState();
                invalidate();
                return;
            default:
                return;
        }
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The panel's "Add button": it opens the
     * editor and the key-selection menu through the same model call the editor toolbar and the
     * launcher page use. Nothing is created until a key is chosen, so there is no placeholder
     * button; the maximum is reported here, before the menu would open.
     */
    private void addButtonFromToolbox() {
        panelOpen = false;
        persistPanelState();
        invalidate();
        if (hotkeyOverlay == null) {
            return;
        }
        if (!hotkeyOverlay.openKeyPickerInEditor()) {
            toast(activity.getString(R.string.touch_too_many_buttons));
        }
    }

    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 "Hide toolbox" now removes the floating
     * icon as well as closing the panel, which is what the row says. It writes only the toolbox's
     * own flag (ToolboxConfig.enabled, the same flag the launcher's "Floating toolbox" switch
     * reads and writes); it never touches TouchControlConfig, so every game button stays visible,
     * keeps its key, and the button layer stays on.
     *
     * The icon is off the screen rather than transparent, so its tap and drag areas are gone too
     * (onTouchEvent and onDraw both consult iconVisible). The icon's last dragged position is left
     * untouched, so the launcher switch brings it back exactly where it was.
     */
    private void hideToolbox() {
        panelOpen = false;
        config.panelOpen = false;
        config.enabled = false;
        config.save(activity, game);
        iconVisible = false;
        draggingIcon = false;
        scrollingPanel = false;
        activePointer = MotionEvent.INVALID_POINTER_ID;
        invalidate();
        toast(activity.getString(R.string.toolbox_toast_hidden));
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Mirrors the launcher's "Floating toolbox"
     * switch onto the live overlay. The launcher writes the stored flag; the running match re-reads
     * it when it regains focus, so a hide in the launcher takes the icon off the battlefield and a
     * show brings it back at its stored position. The game buttons are never touched.
     */
    void syncIconVisibility() {
        boolean stored = ToolboxConfig.load(activity, game).enabled;
        if (stored == iconVisible) {
            return;
        }
        iconVisible = stored;
        if (!stored) {
            panelOpen = false;
        }
        rebuildRows();
        invalidate();
    }

    /** Stores the panel's visibility, which is per game and independent of the buttons. */
    private void persistPanelState() {
        config.panelOpen = panelOpen;
        config.save(activity, game);
    }

    /**
     * Turns the button layer on or off in place. The Java layer follows immediately; the engine's
     * own fixed group panel is not touched, because on this branch it is the army drawer and not
     * a duplicate of the overlay -- see the audit report, section 5.
     */
    private void setTouchLayerEnabled(boolean enabled) {
        TouchControlConfig config = TouchControlConfig.load(activity);
        config.enabled = enabled;
        config.save(activity);
        if (!enabled && hotkeyOverlay != null) {
            hotkeyOverlay.releaseAllKeys();
        }
        reloadHotkeys();
        rebuildRows();
        invalidate();
        toast(activity.getString(R.string.toolbox_toast_layer_changed));
    }

    private void cycleButtonSize() {
        TouchControlConfig layout = TouchControlConfig.load(activity);
        int nearest = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < BUTTON_SIZE_PRESETS.length; ++i) {
            float distance = Math.abs(layout.buttonScale - BUTTON_SIZE_PRESETS[i]);
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        float next = BUTTON_SIZE_PRESETS[(nearest + 1) % BUTTON_SIZE_PRESETS.length];
        layout.buttonScale = next;
        layout.save(activity);
        reloadHotkeys();
        rebuildRows();
        invalidate();
        toast(activity.getString(R.string.toolbox_toast_button_size, Math.round(next * 100f)));
    }

    private void saveDefaultLayout() {
        try {
            config.defaultLayoutJson = TouchControlConfig.load(activity).toJsonString();
        } catch (Exception e) {
            Log.w(TAG, "could not serialise the current layout", e);
            return;
        }
        config.save(activity, game);
        rebuildRows();
        invalidate();
        toast(activity.getString(R.string.toolbox_toast_default_saved));
    }

    private void loadDefaultLayout() {
        ToolboxConfig stored = ToolboxConfig.load(activity, game);
        TouchControlConfig snapshot = stored.loadDefaultLayout();
        if (snapshot == null) {
            toast(activity.getString(stored.hasDefaultLayout()
                ? R.string.toolbox_toast_default_broken : R.string.toolbox_toast_no_default));
            return;
        }
        snapshot.save(activity);
        reloadHotkeys();
        rebuildRows();
        invalidate();
        toast(activity.getString(R.string.toolbox_toast_layout_loaded));
    }

    private void reloadHotkeys() {
        if (hotkeyOverlay != null) {
            hotkeyOverlay.reload();
        }
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Closes the panel if it is open; true means this view handled Back. The activity calls this
     * before the game sees the key, so the engine's own Back does not open the in-game menu
     * behind the panel.
     */
    boolean handleBack() {
        if (!panelOpen) {
            return false;
        }
        panelOpen = false;
        invalidate();
        return true;
    }
}
