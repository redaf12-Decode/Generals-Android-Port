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

// GeneralsX @feature Android touch editor 05/11/2026 The layer of on-screen buttons over the
// battlefield, and the in-game editor that rearranges them.
//
// Touch ownership, which is the whole reason this is safe to put over a running match:
//
//  * Only a configured button rectangle consumes a touch. A press that lands anywhere else
//    returns false from onTouchEvent, so the view below -- SDL's surface -- receives that same
//    gesture and the engine's own touch path (camera pan, selection, pinch, placement) is
//    untouched. Nothing here synthesises a mouse message, and nothing reads engine internals.
//  * A button acts by pressing a real keyboard key through SDLActivity.onNativeKeyDown/Up, so
//    every button is the game's own shortcut, resolved by the engine's normal keyboard path.
//  * While the editor is open, every touch belongs to it -- including empty space -- because an
//    edit gesture must never be able to reach the battlefield. That is the one state where the
//    pass-through rule is deliberately suspended, and it is entered and left explicitly.
//
// A consequence of Android's dispatch worth stating rather than hiding: once this view consumes
// an ACTION_DOWN, every further pointer of that gesture is delivered here, not to SDL. So a finger
// held on a button and a second finger panning at the same time is not possible -- the second
// finger is simply ignored. A gesture that starts on the battlefield is unaffected, including its
// extra fingers, because this view returned false for its ACTION_DOWN and never joined it.
// Forwarding the ignored pointer into SDL would mean synthesising events the engine must not
// receive, which is exactly what this port's touch design forbids.
//
// Cost: invalidate() is called only when something actually changed. onDraw reuses every Paint,
// RectF and Path it owns, draws from arrays rebuilt only when the layout changes, and builds no
// string in the runtime path. A frame with nothing happening on the overlay costs nothing here.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.libsdl.app.SDLActivity;

import java.util.List;

final class HotkeyOverlayView extends View {

    /** The toolbar's cells, left to right. */
    private static final int TOOL_ADD = 0;
    private static final int TOOL_SHAPE = 1;
    private static final int TOOL_SIZE_DOWN = 2;
    private static final int TOOL_SIZE_VALUE = 3;
    private static final int TOOL_SIZE_UP = 4;
    private static final int TOOL_ALPHA_DOWN = 5;
    private static final int TOOL_ALPHA_VALUE = 6;
    private static final int TOOL_ALPHA_UP = 7;
    private static final int TOOL_CANCEL = 8;
    private static final int TOOL_SAVE = 9;
    private static final int TOOL_COUNT = 10;

    /** The shape menu's cells, in two rows: the six shapes, then the four polygon types. */
    private static final int SHAPE_COUNT = 6;
    private static final int POLYGON_COUNT = 4;

    private static final float TOOLBAR_HEIGHT_DP = 56f;
    private static final float SHAPE_CELL_HEIGHT_DP = 44f;
    private static final float STEP_SIZE_DP = 4f;
    private static final float STEP_ALPHA = 0.06f;

    private final Activity activity;
    private TouchKeyPresser presser;
    private final TouchEditorRenderer editorRenderer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TouchControlConfig config;
    private final TouchLayoutEditor editor;
    private boolean editing;

    /** True while the shape menu is open over the editor toolbar. */
    private boolean shapeMenuOpen;

    /**
     * The buttons to draw, in draw order, with their boxes and their index in the model. Rebuilt
     * only when the layout changes, so a frame allocates nothing.
     */
    private TouchControlConfig.ButtonSpec[] liveButtons = new TouchControlConfig.ButtonSpec[0];
    private RectF[] liveRects = new RectF[0];
    private int[] liveIndices = new int[0];

    /** pointerId -> index into liveButtons, for the runtime mode. */
    private final SparseIntArray activePointers = new SparseIntArray();

    /** The pointer that started the editor gesture, and what it is doing. */
    private int editorPointer = MotionEvent.INVALID_POINTER_ID;
    private boolean dragging;
    private boolean resizing;
    private float downX;
    private float downY;
    private final float touchSlop;

    private final RectF workRect = new RectF();
    private final RectF handleRect = new RectF();
    private final RectF toolbarRect = new RectF();
    private final RectF shapeMenuRect = new RectF();
    private final RectF[] toolbarCells = new RectF[TOOL_COUNT];
    private final RectF[] shapeCells = new RectF[SHAPE_COUNT];
    private final RectF[] polygonCells = new RectF[POLYGON_COUNT];

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chromePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shapePath = new Path();
    private final Path dashPath = new Path();

    private final int accentColor;
    private final int toolbarBackground = 0xE0101418;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    private HotkeyOverlayView(Activity activity) {
        super(activity);
        this.activity = activity;
        this.config = TouchControlConfig.load(activity);
        this.editor = new TouchLayoutEditor(config);
        this.presser = new TouchKeyPresser(new SdlSink(), new HandlerScheduler());
        this.editorRenderer = new TouchEditorRenderer(activity);
        this.accentColor = resolveAccentColor(activity);
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        for (int i = 0; i < TOOL_COUNT; ++i) {
            toolbarCells[i] = new RectF();
        }
        for (int i = 0; i < SHAPE_COUNT; ++i) {
            shapeCells[i] = new RectF();
        }
        for (int i = 0; i < POLYGON_COUNT; ++i) {
            polygonCells[i] = new RectF();
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        borderPaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setPathEffect(new android.graphics.DashPathEffect(
            new float[] {dp(7f), dp(5f)}, 0f));
        setFocusable(false);
        installInsetsListener();
    }

    /**
     * The accent the launcher's theme is using, so a selected button's frame matches the app
     * rather than being a colour of its own.
     */
    private static int resolveAccentColor(Context context) {
        try {
            return ThemeHelper.swatchColor(context, ThemeHelper.getAccent(context));
        } catch (Exception e) {
            return Color.rgb(220, 181, 86);
        }
    }

    /** Attaches the layer above SDL's surface. */
    static HotkeyOverlayView attach(Activity activity) {
        HotkeyOverlayView overlay = new HotkeyOverlayView(activity);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        overlay.setElevation(1000f);
        overlay.reload();
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
            rebuildGeometry();
            invalidate();
            return windowInsets;
        });
    }

    /**
     * Re-reads the saved layout and shows or hides the layer. Called when the layer is attached
     * and whenever the toolbox or the editor changes the layout, so a change is visible without
     * restarting the game.
     */
    void reload() {
        config = TouchControlConfig.load(activity);
        rebuildGeometry();
        if (!editing) {
            setVisibility(config.enabled ? VISIBLE : GONE);
        }
        invalidate();
    }

    boolean isEditing() {
        return editing;
    }

    /**
     * Opens the in-game editor. From here every touch belongs to the editor until leaveEditor()
     * or saveEditor() is called, so an edit gesture cannot become a gameplay command.
     */
    void enterEditor() {
        if (editing) {
            return;
        }
        presser.releaseAll();
        activePointers.clear();
        editorPointer = MotionEvent.INVALID_POINTER_ID;
        dragging = false;
        resizing = false;
        shapeMenuOpen = false;
        editing = true;
        setVisibility(VISIBLE);
        rebuildGeometry();
        invalidate();
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Opens the editor and adds a button at
     * once -- the in-game toolbox's "Add button" entry point. It calls the same editor model and
     * the same addButton() the toolbar and the launcher page call, so there is one implementation
     * of "add", not three. Returns false at the model's maximum, so the caller can report it.
     */
    boolean addButtonInEditor() {
        enterEditor();
        boolean added = editor.addButton();
        rebuildGeometry();
        invalidate();
        return added;
    }

    /**
     * Leaves the editor without writing. The toolbar's Cancel and the toolbox's Back both come
     * through here.
     */
    void leaveEditor() {
        if (!editing) {
            return;
        }
        editor.cancel(activity);
        presser.releaseAll();
        editorPointer = MotionEvent.INVALID_POINTER_ID;
        dragging = false;
        resizing = false;
        shapeMenuOpen = false;
        editing = false;
        reload();
    }

    /** Writes the editor's working copy and applies it at once. Returns false when unchanged. */
    boolean saveEditor() {
        boolean wrote = editor.save(activity);
        presser.releaseAll();
        editorPointer = MotionEvent.INVALID_POINTER_ID;
        dragging = false;
        resizing = false;
        shapeMenuOpen = false;
        editing = false;
        reload();
        return wrote;
    }

    /**
     * Releases every key this layer is holding. Called on pause, on focus loss, and when the
     * toolbox switches the layer off, so no key can outlive the gesture that pressed it.
     */
    void releaseAllKeys() {
        presser.releaseAll();
        activePointers.clear();
        invalidate();
    }

    /** The overlay's add entry point, for a test: it adds through the model and reports the max. */
    int editorButtonCountForTest() {
        return editor.buttons().size();
    }

    /** Whether the shape menu is open, for a test of the real touch path. */
    boolean shapeMenuOpenForTest() {
        return shapeMenuOpen;
    }

    /** A shape-menu cell's centre, for a test that drives the real touch path. */
    android.graphics.PointF shapeCellCenterForTest(int index) {
        layoutToolbar();
        layoutShapeMenu();
        RectF cell = index < SHAPE_COUNT ? shapeCells[index] : polygonCells[index - SHAPE_COUNT];
        return new android.graphics.PointF(cell.centerX(), cell.centerY());
    }

    TouchLayoutEditor editorModel() {
        return editor;
    }

    /**
     * Test seam. The production presser's sink is SDLActivity.onNativeKeyDown/Up, which is native
     * and cannot run in a JVM test, so the routing test injects a recording presser instead. The
     * touch routing this replaces is what the test is about; the key state machine has its own
     * tests against the same class.
     */
    void setPresserForTest(TouchKeyPresser testPresser) {
        this.presser = testPresser;
    }

    /**
     * Applies the layer's current size and insets to the edit session. The launcher's editor has
     * no such view, so it calls this with its own measured size; the in-game editor's size is the
     * live window.
     */
    void applyViewport() {
        float scale = editing ? editor.working().buttonScale : config.buttonScale;
        editor.viewport().set(getWidth() > 0 ? getWidth() : 1, getHeight() > 0 ? getHeight() : 1,
            density(), scale);
        editor.viewport().setInsets(insetLeft, insetTop, insetRight, insetBottom);
        editor.setReservedBottomPx(toolbarHeightPx());
    }

    /** Selects the button under a point, for a host that handles the touch itself. */
    boolean selectByPoint(float px, float py) {
        return editor.selectAt(px, py);
    }

    // ------------------------------------------------------------------ geometry

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private void rebuildGeometry() {
        float scale = editing ? editor.working().buttonScale : config.buttonScale;
        editor.viewport().set(getWidth() > 0 ? getWidth() : 1, getHeight() > 0 ? getHeight() : 1,
            density(), scale);
        editor.viewport().setInsets(insetLeft, insetTop, insetRight, insetBottom);
        editor.setReservedBottomPx(toolbarHeightPx());

        TouchControlConfig source = editing ? editor.working() : config;
        List<TouchControlConfig.ButtonSpec> ordered = source.inDrawOrder();
        int count = ordered.size();
        if (liveButtons.length != count) {
            liveButtons = new TouchControlConfig.ButtonSpec[count];
            liveRects = new RectF[count];
            liveIndices = new int[count];
            for (int i = 0; i < count; ++i) {
                liveRects[i] = new RectF();
            }
        }
        for (int i = 0; i < count; ++i) {
            TouchControlConfig.ButtonSpec spec = ordered.get(i);
            liveButtons[i] = spec;
            liveIndices[i] = source.buttons.indexOf(spec);
            editor.bounds(spec, liveRects[i]);
        }
    }

    private int findButton(float px, float py) {
        // Topmost first: the last drawn is the first hit.
        for (int i = liveButtons.length - 1; i >= 0; --i) {
            TouchControlConfig.ButtonSpec spec = liveButtons[i];
            if (!spec.enabled) {
                continue;
            }
            if (TouchButtonShape.contains(spec.shape, spec.polygonType,
                    spec.cornerDp * density() * config.buttonScale, liveRects[i], px, py)) {
                return i;
            }
        }
        return -1;
    }

    /** The toolbar's height in pixels, insets included: the strip a new button must avoid. */
    private float toolbarHeightPx() {
        return dp(TOOLBAR_HEIGHT_DP) + insetBottom;
    }

    private void layoutToolbar() {
        float height = toolbarHeightPx();
        toolbarRect.set(0, getHeight() - height, getWidth(), getHeight());
        float cell = getWidth() / (float) TOOL_COUNT;
        for (int i = 0; i < TOOL_COUNT; ++i) {
            toolbarCells[i].set(i * cell, toolbarRect.top, (i + 1) * cell, toolbarRect.bottom);
        }
    }

    /** The shape menu sits just above the toolbar; two rows: six shapes, then four polygons. */
    private void layoutShapeMenu() {
        float cellWidth = getWidth() / (float) SHAPE_COUNT;
        float rowHeight = dp(SHAPE_CELL_HEIGHT_DP);
        float height = rowHeight * 2f + dp(8f);
        shapeMenuRect.set(0, toolbarRect.top - height, getWidth(), toolbarRect.top);
        float top = shapeMenuRect.top + dp(4f);
        for (int i = 0; i < SHAPE_COUNT; ++i) {
            shapeCells[i].set(i * cellWidth, top, (i + 1) * cellWidth, top + rowHeight);
        }
        float polyWidth = getWidth() / (float) POLYGON_COUNT;
        float polyTop = top + rowHeight;
        for (int i = 0; i < POLYGON_COUNT; ++i) {
            polygonCells[i].set(i * polyWidth, polyTop, (i + 1) * polyWidth,
                polyTop + rowHeight);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editing) {
            drawEditor(canvas);
        } else {
            drawButtons(canvas);
        }
    }

    private void drawButtons(Canvas canvas) {
        int baseAlpha = Math.round(255f * config.buttonOpacity);
        for (int i = 0; i < liveButtons.length; ++i) {
            TouchControlConfig.ButtonSpec spec = liveButtons[i];
            if (!spec.enabled) {
                continue;
            }
            RectF rect = liveRects[i];
            boolean pressed = presser.isButtonDown(liveIndices[i]);
            float alpha = TouchControlConfig.clamp(spec.opacity * config.buttonOpacity, 0f, 1f);
            int buttonAlpha = Math.round(255f * alpha);
            fillPaint.setColor(pressed ? lighten(spec.fillColor, 0.16f) : spec.fillColor);
            fillPaint.setAlpha(pressed ? Math.min(255, buttonAlpha + 45) : buttonAlpha);
            borderPaint.setColor(spec.borderColor);
            borderPaint.setStrokeWidth(Math.max(1f, dp(spec.borderWidthDp)));
            borderPaint.setAlpha(pressed ? 255 : Math.max(120, baseAlpha));
            drawShape(canvas, rect, spec, config.buttonScale);
            textPaint.setColor(spec.textColor);
            textPaint.setAlpha(buttonAlpha);
            drawLabel(canvas, rect, spec, config.buttonScale);
        }
    }

    /**
     * The edit mode: every button drawn with its own colours, the selected one marked by the
     * renderer's external selection frame and its toolbar along the bottom. Unselected buttons
     * keep their own appearance, so entering the editor never recolours a button.
     */
    private void drawEditor(Canvas canvas) {
        editorRenderer.draw(canvas, editor);
        drawToolbar(canvas);
        if (shapeMenuOpen) {
            drawShapeMenu(canvas);
        }
    }

    private void drawToolbar(Canvas canvas) {
        layoutToolbar();
        chromePaint.setColor(toolbarBackground);
        chromePaint.setAlpha(0xE0);
        canvas.drawRect(toolbarRect, chromePaint);
        chromePaint.setColor(accentColor);
        chromePaint.setAlpha(180);
        canvas.drawRect(toolbarRect.left, toolbarRect.top, toolbarRect.right,
            toolbarRect.top + Math.max(1f, dp(1f)), chromePaint);

        textPaint.setTextSize(dp(15f));
        for (int i = 0; i < TOOL_COUNT; ++i) {
            RectF cell = toolbarCells[i];
            textPaint.setColor(0xFFEDEDED);
            textPaint.setAlpha(255);
            // The cells are narrow and a shape's name can be long ("Pentagon"), so each label is
            // shrunk until it fits its own cell instead of overrunning the neighbour.
            drawFittedText(canvas, toolbarLabel(i), cell, dp(15f));
            if (i != TOOL_COUNT - 1) {
                chromePaint.setColor(0x33FFFFFF);
                chromePaint.setAlpha(0x33);
                canvas.drawRect(cell.right, cell.top + dp(8f), cell.right + 1f,
                    cell.bottom - dp(8f), chromePaint);
            }
        }
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        if (spec != null && !shapeMenuOpen) {
            textPaint.setTextSize(dp(11f));
            textPaint.setColor(0xFF9FD4D8);
            textPaint.setAlpha(255);
            canvas.drawText(modeText(spec), getWidth() * 0.5f, toolbarRect.top - dp(6f), textPaint);
        }
    }

    private String modeText(TouchControlConfig.ButtonSpec spec) {
        String combination = TouchKeyCatalog.combinationName(spec.keyCode, spec.modifiers);
        String base = TouchControlConfig.modeName(spec.activationMode) + " \u2022 " + combination;
        if (TouchKeyCatalog.isSwallowedByEngine(spec.keyCode, spec.modifiers)) {
            return base + "  (Alt+Tab is ignored by the game)";
        }
        return base;
    }

    private String toolbarLabel(int tool) {
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        switch (tool) {
            case TOOL_ADD: return "+";
            case TOOL_SHAPE: return shapeShortName(spec);
            case TOOL_SIZE_DOWN: return "\u2212";
            case TOOL_SIZE_UP: return "+";
            case TOOL_ALPHA_DOWN: return "A\u2212";
            case TOOL_ALPHA_UP: return "A+";
            case TOOL_CANCEL: return "Cancel";
            case TOOL_SAVE: return "Save";
            case TOOL_SIZE_VALUE:
                return spec == null ? "\u2013" : Math.round(spec.widthDp) + "dp";
            case TOOL_ALPHA_VALUE:
                return spec == null ? "\u2013" : Math.round(spec.opacity * 100f) + "%";
            default: return "";
        }
    }

    /** The shape button's caption: the selected button's shape, or "Shape" when none is selected. */
    private String shapeShortName(TouchControlConfig.ButtonSpec spec) {
        if (spec == null) {
            return activity.getString(R.string.touch_shape_label);
        }
        if (spec.shape == TouchButtonShape.POLYGON) {
            return polygonLabel(spec.polygonType);
        }
        return shapeLabel(spec.shape);
    }

    private void drawShapeMenu(Canvas canvas) {
        layoutShapeMenu();
        chromePaint.setColor(toolbarBackground);
        chromePaint.setAlpha(0xF0);
        canvas.drawRect(shapeMenuRect, chromePaint);
        chromePaint.setColor(accentColor);
        chromePaint.setAlpha(180);
        canvas.drawRect(shapeMenuRect.left, shapeMenuRect.top, shapeMenuRect.right,
            shapeMenuRect.top + Math.max(1f, dp(1f)), chromePaint);

        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        textPaint.setTextSize(dp(13f));
        int[] shapes = {TouchButtonShape.RECTANGLE, TouchButtonShape.ROUNDED,
            TouchButtonShape.SQUARE, TouchButtonShape.CIRCLE, TouchButtonShape.OVAL,
            TouchButtonShape.POLYGON};
        for (int i = 0; i < shapes.length; ++i) {
            boolean on = spec != null && spec.shape == shapes[i];
            drawMenuCell(canvas, shapeCells[i], shapeLabel(shapes[i]), on);
        }
        int[] types = {TouchButtonShape.POLY_TRIANGLE, TouchButtonShape.POLY_PENTAGON,
            TouchButtonShape.POLY_HEXAGON, TouchButtonShape.POLY_OCTAGON};
        for (int i = 0; i < types.length; ++i) {
            boolean on = spec != null && spec.shape == TouchButtonShape.POLYGON
                && spec.polygonType == types[i];
            drawMenuCell(canvas, polygonCells[i], polygonLabel(types[i]), on);
        }
    }

    /** The shape menu is localised: a shape is named by its own string resource. */
    private String shapeLabel(int shape) {
        switch (shape) {
            case TouchButtonShape.RECTANGLE: return activity.getString(R.string.touch_shape_rectangle);
            case TouchButtonShape.SQUARE: return activity.getString(R.string.touch_shape_square);
            case TouchButtonShape.CIRCLE: return activity.getString(R.string.touch_shape_circle);
            case TouchButtonShape.OVAL: return activity.getString(R.string.touch_shape_oval);
            case TouchButtonShape.POLYGON: return activity.getString(R.string.touch_shape_polygon);
            default: return activity.getString(R.string.touch_shape_rounded);
        }
    }

    private String polygonLabel(int polygonType) {
        switch (polygonType) {
            case TouchButtonShape.POLY_TRIANGLE:
                return activity.getString(R.string.touch_polygon_triangle);
            case TouchButtonShape.POLY_PENTAGON:
                return activity.getString(R.string.touch_polygon_pentagon);
            case TouchButtonShape.POLY_OCTAGON:
                return activity.getString(R.string.touch_polygon_octagon);
            default: return activity.getString(R.string.touch_polygon_hexagon);
        }
    }

    private void drawMenuCell(Canvas canvas, RectF cell, String label, boolean selected) {
        if (selected) {
            chromePaint.setColor(accentColor);
            chromePaint.setAlpha(60);
            canvas.drawRoundRect(cell, dp(6f), dp(6f), chromePaint);
        }
        textPaint.setColor(selected ? accentColor : 0xFFEDEDED);
        textPaint.setAlpha(255);
        drawFittedText(canvas, label, cell, dp(13f));
    }

    /**
     * Draws a centred label, shrinking it until it fits its cell. Both the toolbar and the shape
     * menu carry variable-length names, and a label that overruns its cell would sit on top of the
     * next one. Allocation-free: only the Paint's text size changes.
     */
    private void drawFittedText(Canvas canvas, String label, RectF cell, float maxSizePx) {
        float size = maxSizePx;
        float limit = Math.max(dp(1f), cell.width() - dp(4f));
        textPaint.setTextSize(size);
        while (size > dp(7f) && textPaint.measureText(label) > limit) {
            size -= dp(1f);
            textPaint.setTextSize(size);
        }
        canvas.drawText(label, cell.centerX(),
            cell.centerY() - (textPaint.ascent() + textPaint.descent()) * 0.5f, textPaint);
    }

    private void drawShape(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec,
                           float scale) {
        TouchButtonShape.buildPath(shapePath, rect, spec.shape, spec.polygonType,
            spec.cornerDp * density() * scale);
        canvas.drawPath(shapePath, fillPaint);
        if (borderPaint.getStrokeWidth() > 0f && borderPaint.getAlpha() > 0) {
            canvas.drawPath(shapePath, borderPaint);
        }
    }

    private void drawLabel(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec,
                           float scale) {
        float size = dp(spec.label.length() > 5 ? 12f : 15f) * scale;
        textPaint.setTextSize(size);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = rect.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(spec.label, rect.centerX(), baseline, textPaint);
    }

    private static int lighten(int color, float amount) {
        int r = Math.min(255, (int) (Color.red(color) + 255 * amount));
        int g = Math.min(255, (int) (Color.green(color) + 255 * amount));
        int b = Math.min(255, (int) (Color.blue(color) + 255 * amount));
        return Color.argb(Color.alpha(color), r, g, b);
    }

    private float dp(float value) {
        return value * density();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editing) {
            return onEditorTouch(event);
        }
        return onRuntimeTouch(event);
    }

    /**
     * The runtime rule: a press on a button is ours, anything else belongs to the battlefield.
     * Returning false for an ACTION_DOWN we do not want is what lets the view below (SDL's
     * surface) receive the whole gesture.
     */
    private boolean onRuntimeTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                int hit = findButton(event.getX(index), event.getY(index));
                if (hit < 0) {
                    // Nothing of ours here. On the first finger that means the gesture is the
                    // game's, so refuse it outright and let SDL have the whole thing.
                    return event.getActionMasked() != MotionEvent.ACTION_DOWN
                        && activePointers.size() > 0;
                }
                int pointerId = event.getPointerId(index);
                activePointers.put(pointerId, hit);
                presser.press(liveIndices[hit], liveButtons[hit]);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    int pointerId = event.getPointerId(i);
                    int button = activePointers.get(pointerId, -1);
                    if (button < 0) {
                        continue;
                    }
                    // Dragged off the button before lifting: cancel it, so a swipe that merely
                    // started on a button is not a press.
                    if (!liveRects[button].contains(event.getX(i), event.getY(i))) {
                        presser.release(liveIndices[button], liveButtons[button]);
                        activePointers.delete(pointerId);
                        invalidate();
                    }
                }
                return activePointers.size() > 0;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                int pointerId = event.getPointerId(index);
                int button = activePointers.get(pointerId, -1);
                if (button >= 0) {
                    presser.release(liveIndices[button], liveButtons[button]);
                    activePointers.delete(pointerId);
                    invalidate();
                }
                return activePointers.size() > 0;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAllKeys();
                return true;
            default:
                return activePointers.size() > 0;
        }
    }

    /**
     * The editor owns every touch, including empty space: an edit gesture must never reach the
     * battlefield. Empty space clears the selection, which is also how a stray tap is dismissed.
     */
    private boolean onEditorTouch(MotionEvent event) {
        // The toolbar and the shape menu are laid out before a touch is resolved, so a tap can
        // never be tested against a stale rectangle from the previous frame.
        layoutToolbar();
        if (shapeMenuOpen) {
            layoutShapeMenu();
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                editorPointer = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                if (shapeMenuOpen && shapeMenuRect.contains(downX, downY)) {
                    // A tap inside the open menu is handled on release; consume it here so the
                    // press cannot fall through to a button underneath the menu.
                    return true;
                }
                if (toolbarRect.contains(downX, downY)) {
                    return true;
                }
                // Handle before button: the shipped layout's buttons touch, so a handle that
                // overlaps the neighbour must still be grabbable.
                if (editor.isOnResizeHandle(downX, downY)) {
                    resizing = true;
                    dragging = false;
                    editor.beginResize(downX, downY);
                    invalidate();
                    return true;
                }
                if (editor.selectAt(downX, downY)) {
                    editor.beginDrag(downX, downY);
                    dragging = false;
                    resizing = false;
                    invalidate();
                    return true;
                }
                editor.clearSelection();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(editorPointer);
                if (index < 0) {
                    return true;
                }
                float x = event.getX(index);
                float y = event.getY(index);
                if (resizing) {
                    editor.resizeTo(x, y);
                    invalidate();
                    return true;
                }
                if (editor.selectedButton() != null) {
                    // A drag only becomes a move once the finger passes the system slop, so a tap
                    // that wobbles does not nudge the button. The move itself is absolute (see
                    // TouchLayoutEditor), so passing the slop causes no jump.
                    if (!dragging) {
                        float dx = x - downX;
                        float dy = y - downY;
                        if (dx * dx + dy * dy < touchSlop * touchSlop) {
                            return true;
                        }
                        dragging = true;
                    }
                    editor.dragTo(x, y);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                if (event.getPointerId(index) != editorPointer) {
                    return true;
                }
                float x = event.getX(index);
                float y = event.getY(index);
                if (!dragging && !resizing) {
                    if (shapeMenuOpen && shapeMenuRect.contains(x, y)) {
                        for (int i = 0; i < SHAPE_COUNT; ++i) {
                            if (shapeCells[i].contains(x, y)) {
                                runShapeChoice(i);
                                break;
                            }
                        }
                        for (int i = 0; i < POLYGON_COUNT; ++i) {
                            if (polygonCells[i].contains(x, y)) {
                                runPolygonChoice(i);
                                break;
                            }
                        }
                    } else if (toolbarRect.contains(x, y)) {
                        for (int i = 0; i < TOOL_COUNT; ++i) {
                            if (toolbarCells[i].contains(x, y)) {
                                runTool(i);
                                break;
                            }
                        }
                    }
                }
                dragging = false;
                resizing = false;
                editorPointer = MotionEvent.INVALID_POINTER_ID;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                // The system took the pointer. Nothing is committed, and the gesture ends here.
                dragging = false;
                resizing = false;
                editorPointer = MotionEvent.INVALID_POINTER_ID;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    /** A short message over the game surface, for the cases the toolbar cannot show in place. */
    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private void runTool(int tool) {
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        switch (tool) {
            case TOOL_ADD:
                // The same model call the toolbox and the launcher page use. It selects the new
                // button, so it can be moved, resized and customised at once.
                if (!editor.addButton()) {
                    toast(activity.getString(R.string.touch_too_many_buttons));
                }
                break;
            case TOOL_SHAPE:
                if (spec == null) {
                    toast(activity.getString(R.string.touch_select_button_first));
                } else {
                    shapeMenuOpen = !shapeMenuOpen;
                }
                break;
            case TOOL_SIZE_DOWN:
                if (spec != null) {
                    editor.resizeSelectedByDp(-STEP_SIZE_DP);
                }
                break;
            case TOOL_SIZE_UP:
                if (spec != null) {
                    editor.resizeSelectedByDp(STEP_SIZE_DP);
                }
                break;
            case TOOL_ALPHA_DOWN:
                if (spec != null) {
                    editor.setSelectedOpacity(spec.opacity - STEP_ALPHA);
                }
                break;
            case TOOL_ALPHA_UP:
                if (spec != null) {
                    editor.setSelectedOpacity(spec.opacity + STEP_ALPHA);
                }
                break;
            case TOOL_CANCEL:
                leaveEditor();
                return;
            case TOOL_SAVE:
                saveEditor();
                return;
            default:
                break;
        }
        rebuildGeometry();
        invalidate();
    }

    /** A shape chosen from the menu. The editor keeps the key, position and size where it can. */
    private void runShapeChoice(int index) {
        int[] shapes = {TouchButtonShape.RECTANGLE, TouchButtonShape.ROUNDED,
            TouchButtonShape.SQUARE, TouchButtonShape.CIRCLE, TouchButtonShape.OVAL,
            TouchButtonShape.POLYGON};
        if (index >= 0 && index < shapes.length) {
            editor.setSelectedShape(shapes[index]);
        }
        shapeMenuOpen = false;
        rebuildGeometry();
        invalidate();
    }

    /** A polygon type chosen from the menu; it also switches the shape to Polygon. */
    private void runPolygonChoice(int index) {
        int[] types = {TouchButtonShape.POLY_TRIANGLE, TouchButtonShape.POLY_PENTAGON,
            TouchButtonShape.POLY_HEXAGON, TouchButtonShape.POLY_OCTAGON};
        if (index >= 0 && index < types.length) {
            editor.setSelectedShape(TouchButtonShape.POLYGON);
            editor.setSelectedPolygonType(types[index]);
        }
        shapeMenuOpen = false;
        rebuildGeometry();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        rebuildGeometry();
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ------------------------------------------------------------------ key sinks

    /** The real sink: SDL's own key entry points, which is how every button is the game's key. */
    private static final class SdlSink implements TouchKeyPresser.Sink {
        @Override
        public void keyDown(int keyCode) {
            SDLActivity.onNativeKeyDown(keyCode);
        }

        @Override
        public void keyUp(int keyCode) {
            SDLActivity.onNativeKeyUp(keyCode);
        }
    }

    private final class HandlerScheduler implements TouchKeyPresser.Scheduler {
        @Override
        public void postDelayed(Runnable task, long delayMs) {
            handler.postDelayed(task, delayMs);
        }

        @Override
        public void cancel(Runnable task) {
            handler.removeCallbacks(task);
        }
    }
}
