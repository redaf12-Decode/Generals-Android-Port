/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android floating toolbox 17/10/2026 The in-game floating icon and the
// panel it opens, drawn over SDL's game surface in the same layer HotkeyOverlayView uses.
//
// Two rules make it safe to sit on top of the battlefield, and they are HotkeyOverlayView's
// rules, unchanged:
//
//  * Only the icon and the open panel consume a touch. A press anywhere else returns false
//    from onTouchEvent, so the view below (SDL's surface) receives that same gesture and the
//    engine's own touch path -- camera pan, selection, pinch -- is untouched.
//  * A tool presses the GAME'S OWN shortcut or writes the game's own settings file. Nothing
//    here reads engine internals, and nothing synthesises a mouse message.
//
// Everything is built from the launcher's ToolboxConfig, read once at attach time and again
// whenever a tool changes it, so a toolbox that is off in the launcher is not built at all
// (attach returns null) and draws nothing over the match.
//
// Dragging: a press that lands on the icon is owned by this view from ACTION_DOWN, so it can
// never turn into a battlefield command. It stays a candidate tap until the finger moves past
// the system touch slop; only then does it become a drag, and a drag that ends is written to
// ToolboxConfig as a new position. An interrupted gesture (ACTION_CANCEL: the system took the
// pointer, the activity went away) puts the icon back where it was rather than leaving it
// half-moved, and runs no tool.
//
// Cost: this view is invalidated only when something actually changed -- a press, a drag, a
// panel open/close. onDraw reuses every Paint, RectF and Path it draws with and caches its row
// labels, so a frame of the game underneath costs nothing here.

package com.generalsx.zerohour;

import android.app.Activity;
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

import java.io.File;

final class ToolboxOverlayView extends View {

    private static final String TAG = "GXToolbox";

    // The tools, and the order they are listed in the panel. Each one is implemented here:
    // there is no row that does nothing, and no row that claims a change the game cannot make
    // mid-match without a restart.
    private static final int TOOL_EDIT_LAYOUT = 0;
    private static final int TOOL_TOUCH_LAYER = 1;
    private static final int TOOL_RESTORE_BUTTONS = 2;
    private static final int TOOL_CLOSE = 3;
    /** The panel while the match runs. */
    private static final int[] PLAY_TOOLS = {
        TOOL_EDIT_LAYOUT, TOOL_TOUCH_LAYER, TOOL_RESTORE_BUTTONS, TOOL_CLOSE };

    // GeneralsX @feature Android floating toolbox 17/10/2026 The editor's rows, shown in place of
    // the tools above while the layout is being edited. Same model, same buttons, same keys --
    // this only changes where they sit and whether they are switched on.
    private static final int EDIT_ADD = 10;
    private static final int EDIT_DELETE = 11;
    private static final int EDIT_TOGGLE = 12;
    private static final int EDIT_DEFAULTS = 13;
    private static final int EDIT_SAVE = 14;
    private static final int EDIT_CANCEL = 15;
    private static final int[] EDIT_TOOLS = {
        EDIT_ADD, EDIT_DELETE, EDIT_TOGGLE, EDIT_DEFAULTS, EDIT_SAVE, EDIT_CANCEL };

    /** Room for either list without re-allocating the row rectangles. */
    private static final int MAX_ROWS = 8;

    private static final float ROW_HEIGHT_DP = 46f;
    private static final float PANEL_WIDTH_DP = 232f;
    private static final float PANEL_PADDING_DP = 6f;
    /** How far the icon is kept from a screen edge, a cutout, or a system gesture area. */
    private static final float EDGE_GUTTER_DP = 6f;

    private final Activity activity;
    /** Null when the hotkey panel is switched off; the toolbox still edits the layout. */
    private final HotkeyOverlayView hotkeyOverlay;
    private final String game;
    /**
     * The launcher's settings for this game, read once at attach. Nothing can change them while
     * the match runs -- the launcher is not on screen -- so it is not re-read per draw.
     */
    private final ToolboxConfig config;

    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editSelectedStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path iconPath = new Path();
    private final RectF iconRect = new RectF();
    private final RectF panelRect = new RectF();
    private final RectF glyphBody = new RectF();
    private final RectF glyphHandle = new RectF();
    private final RectF buttonRect = new RectF();
    private final RectF handleRect = new RectF();
    private final RectF[] rowRects = new RectF[MAX_ROWS];
    private final String[] rowLabels = new String[MAX_ROWS];
    private final String[] rowValues = new String[MAX_ROWS];

    private final int touchSlop;
    private final float rowHeight;
    private final float panelWidth;
    private final float panelPadding;
    private final float edgeGutter;
    private final int accent;

    /** Icon centre, in pixels; the persisted fractions are the source of truth. */
    private float iconCx;
    private float iconCy;
    /** Centre the icon is put back to when a gesture is cancelled. */
    private float settledCx;
    private float settledCy;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    private int activePointer = -1;
    private int pressedRow = -1;
    private boolean iconPressed;
    private boolean dragging;
    private boolean panelOpen;
    private float downX;
    private float downY;

    // GeneralsX @feature Android floating toolbox 17/10/2026 Editing state. Nothing here is
    // stored until the Save row is used: the editor works on a copy of the layout, so Cancel,
    // Back, closing the panel and any interruption all leave the stored layout untouched.
    private boolean editing;
    private TouchLayoutEditor editor;
    private boolean movingButton;
    private boolean resizingButton;
    private boolean editGesture;
    private float lastTouchX;
    private float lastTouchY;

    ToolboxOverlayView(Activity activity, HotkeyOverlayView hotkeyOverlay, String game) {
        super(activity);
        this.activity = activity;
        this.hotkeyOverlay = hotkeyOverlay;
        this.game = game;
        this.accent = ThemeHelper.accentColor(activity);
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.rowHeight = dp(ROW_HEIGHT_DP);
        this.panelWidth = dp(PANEL_WIDTH_DP);
        this.panelPadding = dp(PANEL_PADDING_DP);
        this.edgeGutter = dp(EDGE_GUTTER_DP);

        config = ToolboxConfig.load(activity, game);
        iconCx = config.iconX * screenWidthGuess();
        iconCy = config.iconY * screenHeightGuess();
        settledCx = iconCx;
        settledCy = iconCy;

        iconFill.setStyle(Paint.Style.FILL);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeWidth(dp(1.6f));
        iconStroke.setColor(accent);
        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(Color.WHITE);
        panelFill.setColor(withAlpha(UiKit.surfaceContainerHighColor(activity), 236));
        panelStroke.setStyle(Paint.Style.STROKE);
        panelStroke.setStrokeWidth(dp(1.4f));
        panelStroke.setColor(accent);
        rowFill.setColor(withAlpha(UiKit.accentContainer(activity), 232));
        rowLabelPaint.setColor(UiKit.color(activity, R.color.gzh_on_surface));
        rowLabelPaint.setTextSize(dp(15));
        rowValuePaint.setColor(accent);
        rowValuePaint.setTextSize(dp(13));
        rowValuePaint.setTextAlign(Paint.Align.RIGHT);
        editFill.setColor(withAlpha(UiKit.surfaceContainerHighestColor(activity), 216));
        editStroke.setStyle(Paint.Style.STROKE);
        editStroke.setStrokeWidth(dp(1.2f));
        editStroke.setColor(withAlpha(UiKit.outlineColor(activity), 210));
        editSelectedStroke.setStyle(Paint.Style.STROKE);
        editSelectedStroke.setStrokeWidth(dp(2.4f));
        editSelectedStroke.setColor(accent);
        editHandle.setColor(accent);
        editTextPaint.setColor(UiKit.color(activity, R.color.gzh_on_surface));
        editTextPaint.setTextAlign(Paint.Align.CENTER);
        editTextPaint.setFakeBoldText(true);
        editTextPaint.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);

        for (int i = 0; i < MAX_ROWS; i++) {
            rowRects[i] = new RectF();
        }

        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setContentDescription(activity.getString(R.string.toolbox_icon_description));
        // The panel's labels and values are cached once here and refreshed whenever a tool
        // changes something, so onDraw never builds a string.
        rebuildRows();

        // Cutouts and the rounded corners of a modern phone are where an icon pinned to the
        // screen edge would be clipped or sit under the system's own gestures. The engine gets
        // the same numbers as fractions (GeneralsZHActivity.computeSafeInsetsArgument); here
        // they only keep the icon inside the area the user can actually see and touch.
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.systemGestures());
            if (safe.left != insetLeft || safe.top != insetTop
                    || safe.right != insetRight || safe.bottom != insetBottom) {
                insetLeft = safe.left;
                insetTop = safe.top;
                insetRight = safe.right;
                insetBottom = safe.bottom;
                clampIconToScreen();
            }
            return windowInsets;
        });
    }

    /**
     * Attaches the overlay to a launching game, or returns null when the toolbox is switched
     * off for the game being launched -- in which case nothing at all is added to the window.
     */
    static ToolboxOverlayView attach(Activity activity, HotkeyOverlayView hotkeyOverlay) {
        String game = SetupActivity.getSelectedGame(activity);
        ToolboxConfig config = ToolboxConfig.load(activity, game);
        if (!config.enabled) {
            Log.i(TAG, "floating toolbox is off for " + game + "; no overlay attached");
            return null;
        }
        ToolboxOverlayView overlay = new ToolboxOverlayView(activity, hotkeyOverlay, game);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        // Above HotkeyOverlayView (1000f): the toolbox may be opened over the hotkey panel,
        // and its own hit areas are the only ones it consumes.
        overlay.setElevation(1100f);
        Log.i(TAG, "floating toolbox attached for " + game);
        return overlay;
    }

    // ------------------------------------------------------------------ geometry

    private int screenWidthGuess() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeightGuess() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw == 0 && oldh == 0) {
            // First real measurement: the guesses from the display metrics are close enough to
            // start from, and the fractions are what is stored, so recompute from them.
            iconCx = config.iconX * w;
            iconCy = config.iconY * h;
            settledCx = iconCx;
            settledCy = iconCy;
        }
        clampIconToScreen();
        layoutPanel();
    }

    /** Rebuilds the icon rectangle around its centre, at the configured size and style. */
    private void rebuildIconRect() {
        float side = dp(ToolboxConfig.clampSize(config.iconSizeDp));
        iconRect.set(iconCx - side / 2f, iconCy - side / 2f, iconCx + side / 2f, iconCy + side / 2f);
    }

    private void clampIconToScreen() {
        rebuildIconRect();
        float half = iconRect.width() / 2f;
        float minX = insetLeft + edgeGutter + half;
        float maxX = getWidth() - insetRight - edgeGutter - half;
        float minY = insetTop + edgeGutter + half;
        float maxY = getHeight() - insetBottom - edgeGutter - half;
        if (maxX < minX) {
            iconCx = getWidth() / 2f;
        } else {
            iconCx = Math.max(minX, Math.min(maxX, iconCx));
        }
        if (maxY < minY) {
            iconCy = getHeight() / 2f;
        } else {
            iconCy = Math.max(minY, Math.min(maxY, iconCy));
        }
        rebuildIconRect();
    }

    private void moveIconTo(float x, float y) {
        iconCx = x;
        iconCy = y;
        clampIconToScreen();
        layoutPanel();
        invalidate();
    }

    /** The row list for the mode the toolbox is in. */
    private int[] tools() {
        return editing ? EDIT_TOOLS : PLAY_TOOLS;
    }

    private void layoutPanel() {
        int count = tools().length;
        float height = count * rowHeight + panelPadding * 2f;
        float width = Math.min(panelWidth, getWidth() - 2f * (insetLeft + edgeGutter));
        if (width <= 0) {
            width = panelWidth;
        }
        float left;
        if (iconCx > getWidth() / 2f) {
            left = iconRect.left - dp(10f) - width;
        } else {
            left = iconRect.right + dp(10f);
        }
        left = Math.max(insetLeft + edgeGutter, Math.min(getWidth() - insetRight - edgeGutter - width, left));
        float top = iconCy - height / 2f;
        top = Math.max(insetTop + edgeGutter, Math.min(getHeight() - insetBottom - edgeGutter - height, top));
        panelRect.set(left, top, left + width, top + height);
        for (int i = 0; i < count; i++) {
            rowRects[i].set(panelRect.left + panelPadding, panelRect.top + panelPadding + i * rowHeight,
                panelRect.right - panelPadding, panelRect.top + panelPadding + (i + 1) * rowHeight);
        }
    }

    /** Caches the panel's row labels and values: onDraw must not build strings. */
    private void rebuildRows() {
        int[] tools = tools();
        for (int i = 0; i < tools.length; i++) {
            switch (tools[i]) {
                case TOOL_EDIT_LAYOUT:
                    rowLabels[i] = getContext().getString(R.string.toolbox_tool_edit_layout);
                    rowValues[i] = "";
                    break;
                case TOOL_TOUCH_LAYER:
                    rowLabels[i] = getContext().getString(R.string.toolbox_tool_touch_layer);
                    rowValues[i] = getContext().getString(
                        TouchControlConfig.load(getContext()).enabled
                            ? R.string.toolbox_state_on : R.string.toolbox_state_off);
                    break;
                case TOOL_RESTORE_BUTTONS:
                    rowLabels[i] = getContext().getString(R.string.toolbox_tool_restore_buttons);
                    rowValues[i] = "";
                    break;
                case TOOL_CLOSE:
                    rowLabels[i] = getContext().getString(R.string.toolbox_close);
                    rowValues[i] = "";
                    break;
                case EDIT_ADD:
                    rowLabels[i] = getContext().getString(R.string.touch_add_button);
                    rowValues[i] = "";
                    break;
                case EDIT_DELETE:
                    rowLabels[i] = getContext().getString(R.string.touch_delete_button);
                    // The row names what it would delete, so "Delete" is never a blind action.
                    rowValues[i] = selectedLabel();
                    break;
                case EDIT_TOGGLE:
                    rowLabels[i] = getContext().getString(R.string.toolbox_edit_toggle);
                    rowValues[i] = selectedEnabledLabel();
                    break;
                case EDIT_DEFAULTS:
                    rowLabels[i] = getContext().getString(R.string.touch_reset_button);
                    rowValues[i] = "";
                    break;
                case EDIT_SAVE:
                    rowLabels[i] = getContext().getString(R.string.touch_save_button);
                    rowValues[i] = "";
                    break;
                case EDIT_CANCEL:
                default:
                    rowLabels[i] = getContext().getString(R.string.common_cancel);
                    rowValues[i] = "";
                    break;
            }
        }
    }

    private String selectedLabel() {
        TouchControlConfig.ButtonSpec spec = editor != null ? editor.selectedButton() : null;
        return spec != null ? spec.label : "";
    }

    private String selectedEnabledLabel() {
        TouchControlConfig.ButtonSpec spec = editor != null ? editor.selectedButton() : null;
        if (spec == null) {
            return "";
        }
        return getContext().getString(
            spec.enabled ? R.string.toolbox_state_on : R.string.toolbox_state_off);
    }

    private int rowAt(float x, float y) {
        int[] tools = tools();
        for (int i = 0; i < tools.length; i++) {
            if (rowRects[i].contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editing) {
            drawButtons(canvas);
        }
        drawIcon(canvas);
        if (panelOpen) {
            drawPanel(canvas);
        }
    }

    /**
     * The layout being edited: every button where it will sit, the selected one outlined, and a
     * grab square on its corner for resizing. A switched-off button stays visible here (dimmed)
     * -- switching it off must not look the same as deleting it.
     */
    private void drawButtons(Canvas canvas) {
        if (editor == null) {
            return;
        }
        for (int i = 0; i < editor.buttonCount(); i++) {
            TouchControlConfig.ButtonSpec spec = editor.working.buttons.get(i);
            editor.rectInto(i, buttonRect);
            float radius = Math.min(buttonRect.width(), buttonRect.height()) * 0.22f;
            editFill.setAlpha(spec.enabled ? 216 : 120);
            canvas.drawRoundRect(buttonRect, radius, radius, editFill);
            boolean isSelected = i == editor.selectedIndex();
            canvas.drawRoundRect(buttonRect, radius, radius,
                isSelected ? editSelectedStroke : editStroke);

            editTextPaint.setAlpha(spec.enabled ? 255 : 140);
            editTextPaint.setTextSize(dp(spec.label.length() > 5 ? 12 : 15) * editor.working.buttonScale);
            Paint.FontMetrics metrics = editTextPaint.getFontMetrics();
            canvas.drawText(spec.label, buttonRect.centerX(),
                buttonRect.centerY() - (metrics.ascent + metrics.descent) * 0.5f, editTextPaint);

            if (isSelected) {
                editor.resizeHandleInto(handleRect);
                float handleRadius = Math.min(handleRect.width(), handleRect.height()) * 0.25f;
                canvas.drawRoundRect(handleRect, handleRadius, handleRadius, editHandle);
            }
        }
    }

    private void drawIcon(Canvas canvas) {
        int alpha = Math.round(255f * ToolboxConfig.clampOpacity(config.iconOpacity));
        iconFill.setColor(withAlpha(UiKit.surfaceContainerHighColor(activity), alpha));
        iconStroke.setAlpha(alpha);
        glyphPaint.setAlpha(alpha);

        iconPath.reset();
        float side = iconRect.width();
        float cx = iconRect.centerX();
        float cy = iconRect.centerY();
        switch (ToolboxConfig.clampStyle(config.iconStyle)) {
            case ToolboxConfig.STYLE_CIRCLE:
                iconPath.addOval(iconRect, Path.Direction.CW);
                break;
            case ToolboxConfig.STYLE_DIAMOND:
                iconPath.moveTo(cx, iconRect.top);
                iconPath.lineTo(iconRect.right, cy);
                iconPath.lineTo(cx, iconRect.bottom);
                iconPath.lineTo(iconRect.left, cy);
                iconPath.close();
                break;
            case ToolboxConfig.STYLE_ROUNDED:
            default:
                float radius = side * 0.28f;
                iconPath.addRoundRect(iconRect, radius, radius, Path.Direction.CW);
                break;
        }
        canvas.drawPath(iconPath, iconFill);
        canvas.drawPath(iconPath, iconStroke);
        drawGlyph(canvas, cx, cy, side);
    }

    /**
     * A toolbox: a body with a handle above it. Drawn with the two rectangles and one arc this
     * view already owns -- no allocation per frame, and no bitmap to ship.
     */
    private void drawGlyph(Canvas canvas, float cx, float cy, float side) {
        float bodyWidth = side * 0.54f;
        float bodyHeight = side * 0.30f;
        float radius = side * 0.06f;
        glyphBody.set(cx - bodyWidth / 2f, cy - bodyHeight * 0.1f,
            cx + bodyWidth / 2f, cy - bodyHeight * 0.1f + bodyHeight);
        canvas.drawRoundRect(glyphBody, radius, radius, glyphPaint);

        float handleWidth = side * 0.30f;
        float handleHeight = side * 0.16f;
        glyphHandle.set(cx - handleWidth / 2f, glyphBody.top - handleHeight,
            cx + handleWidth / 2f, glyphBody.top + handleHeight * 0.4f);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(Math.max(1f, side * 0.045f));
        canvas.drawArc(glyphHandle, 180f, 180f, false, glyphPaint);
        glyphPaint.setStyle(Paint.Style.FILL);
    }

    private void drawPanel(Canvas canvas) {
        float radius = dp(14f);
        canvas.drawRoundRect(panelRect, radius, radius, panelFill);
        canvas.drawRoundRect(panelRect, radius, radius, panelStroke);
        int[] tools = tools();
        for (int i = 0; i < tools.length; i++) {
            RectF row = rowRects[i];
            if (i == pressedRow) {
                float rowRadius = dp(9f);
                canvas.drawRoundRect(row, rowRadius, rowRadius, rowFill);
            }
            Paint.FontMetrics metrics = rowLabelPaint.getFontMetrics();
            float baseline = row.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
            canvas.drawText(rowLabels[i], row.left + dp(12f), baseline, rowLabelPaint);
            if (rowValues[i] != null && !rowValues[i].isEmpty()) {
                canvas.drawText(rowValues[i], row.right - dp(12f), baseline, rowValuePaint);
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                downY = event.getY();
                lastTouchX = downX;
                lastTouchY = downY;
                if (iconRect.contains(downX, downY)) {
                    iconPressed = true;
                    dragging = false;
                    activePointer = event.getPointerId(0);
                    return true;
                }
                int row = panelOpen ? rowAt(downX, downY) : -1;
                if (row >= 0) {
                    pressedRow = row;
                    dragging = false;
                    activePointer = event.getPointerId(0);
                    invalidate();
                    return true;
                }
                if (editing && editor != null) {
                    // While the editor is up the whole surface belongs to it: a press that lands
                    // on empty space clears the selection rather than reaching the battlefield,
                    // so an edit gesture can never turn into a gameplay command.
                    //
                    // The selected button's handle is tested FIRST, before looking for a button
                    // under the finger: the shipped layout's buttons overlap, so a later button
                    // drawn over the selected one's corner would otherwise swallow the grab.
                    if (editor.selectedIndex() >= 0 && editor.isOnResizeHandle(downX, downY)) {
                        resizingButton = true;
                        movingButton = false;
                    } else {
                        editor.select(editor.hitTest(downX, downY));
                        resizingButton = false;
                        movingButton = editor.selectedIndex() >= 0;
                    }
                    editGesture = true;
                    activePointer = event.getPointerId(0);
                    rebuildRows();
                    invalidate();
                    return true;
                }
                // Anywhere else: this is the battlefield's gesture, not ours.
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activePointer < 0) {
                    return false;
                }
                int index = event.findPointerIndex(activePointer);
                if (index < 0) {
                    return true;
                }
                float x = event.getX(index);
                float y = event.getY(index);
                if (iconPressed) {
                    if (!dragging && distance(x, y) > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        moveIconTo(x, y);
                    }
                    return true;
                }
                if (movingButton || resizingButton) {
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    lastTouchX = x;
                    lastTouchY = y;
                    if (resizingButton) {
                        editor.resizeSelectedBy(dx, dy);
                    } else {
                        editor.moveSelectedBy(dx, dy);
                    }
                    invalidate();
                    return true;
                }
                if (pressedRow >= 0 && !rowRects[pressedRow].contains(x, y)) {
                    // Dragged off the row before lifting: not a press.
                    pressedRow = -1;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                return finishGesture(event, true);
            case MotionEvent.ACTION_CANCEL:
                if (activePointer < 0) {
                    return false;
                }
                finishGesture(event, false);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // A second finger while we own the gesture: keep consuming, never act on it.
                return activePointer >= 0;
            default:
                return activePointer >= 0;
        }
    }

    /**
     * Ends the gesture we own. commit=false is an interrupted one (ACTION_CANCEL): no tool
     * runs and a moved icon goes back to where it was, so a system interruption cannot leave
     * the icon somewhere the player did not put it.
     */
    private boolean finishGesture(MotionEvent event, boolean commit) {
        if (!iconPressed && pressedRow < 0 && !editGesture) {
            activePointer = -1;
            return false;
        }
        if (iconPressed) {
            if (!commit) {
                iconCx = settledCx;
                iconCy = settledCy;
                clampIconToScreen();
                layoutPanel();
            } else if (dragging) {
                settledCx = iconCx;
                settledCy = iconCy;
                persistIconPosition();
            } else {
                // A tap on the icon: open or close the panel, and nothing else. The press was
                // consumed at ACTION_DOWN, so it never reached the engine.
                togglePanel();
                performClick();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        } else if (pressedRow >= 0) {
            int row = pressedRow;
            if (commit && rowRects[row].contains(event.getX(), event.getY())) {
                runRow(row);
            }
        }
        // An edit gesture needs no finalisation: the button was moved or resized as the finger
        // moved, and the layout is only written by the Save row.
        activePointer = -1;
        pressedRow = -1;
        iconPressed = false;
        dragging = false;
        editGesture = false;
        movingButton = false;
        resizingButton = false;
        invalidate();
        return true;
    }

    private float distance(float x, float y) {
        float dx = x - downX;
        float dy = y - downY;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void persistIconPosition() {
        ToolboxConfig.savePosition(activity, game, iconCx / getWidth(), iconCy / getHeight());
    }

    private void togglePanel() {
        setPanelOpen(!panelOpen);
    }

    private void setPanelOpen(boolean open) {
        if (!open && editing) {
            // Closing the toolbox while editing discards what was not saved, and says so rather
            // than leaving the user to guess whether the layout changed.
            leaveEditMode(true);
        }
        panelOpen = open;
        pressedRow = -1;
        if (open) {
            rebuildRows();
            layoutPanel();
        }
        invalidate();
    }

    // ------------------------------------------------------------------ tools

    private void runRow(int index) {
        int tool = tools()[index];
        switch (tool) {
            case TOOL_EDIT_LAYOUT:
                enterEditMode();
                break;
            case TOOL_TOUCH_LAYER:
                onToggleTouchLayer();
                break;
            case TOOL_RESTORE_BUTTONS:
                onRestoreButtons();
                break;
            case TOOL_CLOSE:
                setPanelOpen(false);
                break;
            case EDIT_ADD:
                onEditorAdd();
                break;
            case EDIT_DELETE:
                onEditorDelete();
                break;
            case EDIT_TOGGLE:
                onEditorToggle();
                break;
            case EDIT_DEFAULTS:
                onEditorDefaults();
                break;
            case EDIT_SAVE:
                onEditorSave();
                break;
            case EDIT_CANCEL:
            default:
                onEditorCancel();
                break;
        }
    }

    // ------------------------------------------------------------------ editor

    /** Opens the editor on a copy of the saved layout; nothing is stored until Save. */
    private void enterEditMode() {
        editor = new TouchLayoutEditor(TouchControlConfig.load(getContext()));
        editor.setViewport(getWidth(), getHeight(), getResources().getDisplayMetrics().density);
        editing = true;
        rebuildRows();
        layoutPanel();
        invalidate();
        toast(getContext().getString(R.string.touch_drag_help_short));
    }

    /**
     * Leaves the editor. discard is true when the exit was not a Save: the working copy is
     * dropped (and the stored layout, which was never touched, is left exactly as it was) and a
     * message says so when something would have been lost.
     */
    private void leaveEditMode(boolean discard) {
        boolean lost = false;
        if (discard && editor != null) {
            lost = editor.isDirty();
            editor.cancel(getContext());
        }
        editing = false;
        editor = null;
        movingButton = false;
        resizingButton = false;
        editGesture = false;
        rebuildRows();
        layoutPanel();
        invalidate();
        if (lost) {
            toast(getContext().getString(R.string.toolbox_toast_edits_discarded));
        }
    }

    private void onEditorAdd() {
        if (editor == null) {
            return;
        }
        if (!editor.addButton()) {
            toast(getContext().getString(R.string.touch_too_many_buttons));
            return;
        }
        rebuildRows();
        invalidate();
    }

    private void onEditorDelete() {
        if (editor == null) {
            return;
        }
        if (!editor.deleteSelected()) {
            toast(getContext().getString(R.string.touch_select_button_first));
            return;
        }
        rebuildRows();
        invalidate();
    }

    private void onEditorToggle() {
        if (editor == null) {
            return;
        }
        if (!editor.toggleSelectedEnabled()) {
            toast(getContext().getString(R.string.touch_select_button_first));
            return;
        }
        rebuildRows();
        invalidate();
    }

    private void onEditorDefaults() {
        if (editor == null) {
            return;
        }
        editor.restoreDefaults();
        rebuildRows();
        invalidate();
    }

    /**
     * Writes the edited layout and applies it at once: the Java touch panel reloads (and shows or
     * hides itself) from the new file. The engine's own fixed group panel still follows the
     * layer's on/off setting at the next launch, which is what the saved message says.
     */
    private void onEditorSave() {
        if (editor == null) {
            return;
        }
        editor.save(getContext());
        prepareForNextLaunch();
        if (hotkeyOverlay != null) {
            hotkeyOverlay.reload();
        }
        leaveEditMode(false);
        toast(getContext().getString(R.string.touch_saved));
    }

    private void onEditorCancel() {
        if (editor == null) {
            return;
        }
        leaveEditMode(true);
    }

    /**
     * Turns the touch-button panel on or off in place. The Java layer follows immediately
     * (HotkeyOverlayView.reload); the engine's own fixed group panel is decided from the same
     * setting when the game starts, so that half is applied on the next launch and the user is
     * told exactly that.
     */
    private void onToggleTouchLayer() {
        TouchControlConfig config = TouchControlConfig.load(getContext());
        config.enabled = !config.enabled;
        config.save(getContext());
        prepareForNextLaunch();
        if (hotkeyOverlay != null) {
            hotkeyOverlay.reload();
        }
        rebuildRows();
        invalidate();
        toast(getContext().getString(R.string.touch_saved));
    }

    /** Back to the shipped button layout, for the same reason: the files are left as a fresh install's. */
    private void onRestoreButtons() {
        TouchControlConfig.reset(getContext());
        prepareForNextLaunch();
        if (hotkeyOverlay != null) {
            hotkeyOverlay.reload();
        }
        rebuildRows();
        invalidate();
        toast(getContext().getString(R.string.toolbox_toast_buttons_restored));
    }

    /**
     * Settles the bundled group panel against the setting that has just changed, exactly as the
     * launcher does before a launch (TouchControlConfig.prepareForLaunch): with the panel on the
     * loose Window\GroupPanel.wnd override is removed, with it off it is restored.
     */
    private void prepareForNextLaunch() {
        String path = SetupActivity.getSavedGamePath(activity, game);
        if (path != null) {
            TouchControlConfig.prepareForLaunch(activity, new File(path));
        }
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Closes the panel if it is open; true means this view handled Back. The activity calls
     * this before letting Back reach the game, so Back closes the toolbox rather than the
     * in-game menu.
     */
    boolean closePanel() {
        if (!panelOpen) {
            return false;
        }
        setPanelOpen(false);
        return true;
    }

    // ------------------------------------------------------------------ hooks for tests

    /** The icon's current hit rectangle, in this view's coordinates. */
    RectF iconBounds() {
        return new RectF(iconRect);
    }

    /** The panel's current rectangle; only meaningful while the panel is open. */
    RectF panelBounds() {
        return new RectF(panelRect);
    }

    /** One row's hit rectangle; only meaningful while the panel is open. */
    RectF rowBounds(int index) {
        return new RectF(rowRects[index]);
    }

    boolean isPanelOpen() {
        return panelOpen;
    }

    int toolCount() {
        return tools().length;
    }

    boolean isEditing() {
        return editing;
    }

    /** The editor's model while it is up, or null. For tests. */
    TouchLayoutEditor editorForTest() {
        return editor;
    }

    String rowLabel(int index) {
        return rowLabels[index];
    }

    String rowValue(int index) {
        return rowValues[index];
    }

    /** Taps a row of the open panel, as a release inside it would. */
    void pressRow(int index) {
        runRow(index);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
