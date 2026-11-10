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

package com.ports.generalsx;

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
    private static final int TOOL_CLEAR_KEY = 2;
    private static final int TOOL_SIZE_DOWN = 3;
    private static final int TOOL_SIZE_VALUE = 4;
    private static final int TOOL_SIZE_UP = 5;
    private static final int TOOL_ALPHA_DOWN = 6;
    private static final int TOOL_ALPHA_VALUE = 7;
    private static final int TOOL_ALPHA_UP = 8;
    private static final int TOOL_CANCEL = 9;
    private static final int TOOL_SAVE = 10;
    private static final int TOOL_COUNT = 11;

    /** The shape menu's cells, in two rows: the six shapes, then the four polygon types. */
    private static final int SHAPE_COUNT = 6;
    private static final int POLYGON_COUNT = 4;

    private static final float TOOLBAR_HEIGHT_DP = 56f;
    private static final float SHAPE_CELL_HEIGHT_DP = 44f;
    private static final float KEY_CELL_HEIGHT_DP = 40f;
    private static final float KEY_HEADER_HEIGHT_DP = 44f;
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
     * GeneralsX @feature Android touch editor 05/11/2026 The key-selection menu Add (+) opens: a
     * scrolling grid above the toolbar, drawn from the shared TouchKeyPicker. While it is open it
     * owns the touch, so a tap in it can never become a gameplay command or a button press. The
     * modifier toggles in its header apply to the key chosen next.
     */
    private boolean keyMenuOpen;
    private int keyMenuModifiers;
    private final TouchKeyPicker keyPicker = new TouchKeyPicker();
    private final RectF keyMenuRect = new RectF();
    private final RectF keyHeaderRect = new RectF();
    private final RectF keyGridRect = new RectF();
    private final RectF[] keyModifierCells = new RectF[3];
    private boolean keyMenuDownInMenu;
    private boolean keyMenuScrolling;
    private float keyMenuDownY;
    private float keyMenuLastY;

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

    /**
     * The bottom system inset, which is all the overlay still needs: the toolbar is drawn above the
     * gesture bar, and a freshly added button must not be created underneath it. No other inset is
     * read, because placement is no longer restricted by the cutout or the side bars.
     */
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
        for (int i = 0; i < keyModifierCells.length; ++i) {
            keyModifierCells[i] = new RectF();
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
     *
     * GeneralsX @bugfix Android touch editor 05/11/2026 The edit session is re-seeded from the
     * SAVED layout every time it is opened (editor.cancel() is "throw the working copy away and
     * re-read the store"). The overlay's own copy is only refreshed by reload(), so without this a
     * layout changed elsewhere -- the toolbox's "Restore default buttons", a launcher edit made
     * while the match was paused, an import -- would open the editor on the stale copy and the
     * player would be editing buttons that no longer match what the match is drawing. Re-seeding
     * makes the editor show the same buttons the overlay draws, which is the whole requirement.
     */
    void enterEditor() {
        if (editing) {
            return;
        }
        editor.cancel(activity);
        presser.releaseAll();
        activePointers.clear();
        editorPointer = MotionEvent.INVALID_POINTER_ID;
        dragging = false;
        resizing = false;
        closeMenus();
        editing = true;
        setVisibility(VISIBLE);
        rebuildGeometry();
        invalidate();
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Opens the editor and its key-selection
     * menu at once -- the in-game toolbox's "Add button" entry point. It calls the same editor
     * model and the same key catalog the toolbar and the launcher page call, so there is one
     * implementation of "add", not three. Returns false at the model's maximum, so the caller can
     * report it; nothing is created until a key is chosen.
     */
    boolean openKeyPickerInEditor() {
        enterEditor();
        return openKeyMenu();
    }

    /**
     * Opens the key-selection menu over the editor, or reports false when the layout is already
     * full. The menu starts with no modifiers latched, so a player who just wants "A" gets "A".
     */
    private boolean openKeyMenu() {
        if (!editor.canAddButton()) {
            return false;
        }
        keyMenuOpen = true;
        shapeMenuOpen = false;
        keyMenuModifiers = 0;
        keyMenuDownInMenu = false;
        keyMenuScrolling = false;
        keyPicker.setScroll(0f, keyGridRect.height());
        rebuildGeometry();
        invalidate();
        return true;
    }

    /** Whether the key menu is open, for a test of the real touch path. */
    boolean keyMenuOpenForTest() {
        return keyMenuOpen;
    }

    /** A key-menu cell's centre in view coordinates, for a test that drives the real touch path. */
    android.graphics.PointF keyCellCenterForTest(int index) {
        layoutToolbar();
        layoutKeyMenu();
        RectF cell = new RectF();
        keyPicker.cellBounds(index, cell);
        return new android.graphics.PointF(cell.centerX(),
            keyGridRect.top + cell.centerY() - keyPicker.scroll());
    }

    /** A key-menu modifier cell's centre, for a test of the modifier latches. */
    android.graphics.PointF keyModifierCenterForTest(int index) {
        layoutToolbar();
        layoutKeyMenu();
        RectF cell = keyModifierCells[index];
        return new android.graphics.PointF(cell.centerX(), cell.centerY());
    }

    /** The index of a catalog entry in the picker grid, for a test that taps it. */
    int keyPickerIndexOf(int keyCode) {
        for (int i = 0; i < keyPicker.count(); ++i) {
            if (keyPicker.entry(i).keyCode == keyCode) {
                return i;
            }
        }
        return -1;
    }

    /** Chooses a catalog entry by index, exactly as tapping its cell does. */
    void chooseKeyForTest(int index) {
        if (index >= 0 && index < keyPicker.count()) {
            chooseKey(keyPicker.entry(index).keyCode);
        }
    }

    /** Latches a modifier through the menu's own toggle, for a test of the modifier path. */
    void toggleKeyModifierForTest(int index) {
        toggleKeyMenuModifier(index);
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
        closeMenus();
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
        closeMenus();
        editing = false;
        reload();
        return wrote;
    }

    /** Closes every editor menu and forgets any in-progress menu gesture. */
    private void closeMenus() {
        shapeMenuOpen = false;
        keyMenuOpen = false;
        keyMenuDownInMenu = false;
        keyMenuScrolling = false;
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

    /** A toolbar cell's centre, for a test that drives the real touch path. */
    android.graphics.PointF toolCellCenterForTest(int tool) {
        layoutToolbar();
        RectF cell = toolbarCells[tool];
        return new android.graphics.PointF(cell.centerX(), cell.centerY());
    }

    /** The toolbar cell index of the Clear Key action, for a test that taps it by name. */
    static int clearKeyToolIndexForTest() {
        return TOOL_CLEAR_KEY;
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
     * GeneralsX @bugfix Android touch editor 05/11/2026 Applies the layer's current size to the
     * edit session. The launcher's editor has no such view, so it calls this with its own measured
     * size; the in-game editor's size is the live window.
     */
    void applyViewport() {
        float scale = editing ? editor.working().buttonScale : config.buttonScale;
        editor.viewport().set(viewportWidth(), viewportHeight(), density(), scale);
        // The only thing the editor still reserves is the strip the toolbar itself occupies, so a
        // freshly added button is not created underneath it. This is not a placement restriction
        // on a button the player moves -- the player may drag one onto the toolbar if they want.
        editor.setReservedBottomPx(toolbarHeightPx());
    }

    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 The layer's width for geometry, falling
     * back to the display when the view has not been laid out yet.
     *
     * A GONE view is never measured, so getWidth() is 0 while the button layer is switched off.
     * The editor is opened by the toolbox even then, and the old 1x1 fallback collapsed every
     * button onto the top-left pixel: the editing surface looked empty because all ten buttons
     * were drawn inside one pixel. The display size is the correct answer for a full-screen
     * overlay and is what makes the editor draw the real layout before the first layout pass.
     */
    private int viewportWidth() {
        if (getWidth() > 0) {
            return getWidth();
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        return width > 0 ? width : 1;
    }

    private int viewportHeight() {
        if (getHeight() > 0) {
            return getHeight();
        }
        int height = getResources().getDisplayMetrics().heightPixels;
        return height > 0 ? height : 1;
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
        editor.viewport().set(viewportWidth(), viewportHeight(), density(), scale);
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

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The key menu fills the space between the
     * top of the screen and the toolbar, so its grid is as tall as there is room for and scrolls
     * when the catalog is longer than that. The header carries the three modifier latches; the
     * grid below it is where a key is tapped.
     */
    private void layoutKeyMenu() {
        float header = dp(KEY_HEADER_HEIGHT_DP);
        keyMenuRect.set(0, 0, getWidth(), toolbarRect.top);
        keyHeaderRect.set(0, 0, getWidth(), header);
        keyGridRect.set(0, header, getWidth(), keyMenuRect.bottom);
        // The three modifier latches sit on the right of the header, clear of the title. The cells
        // are placed here once -- drawing and hit testing both read these rects, so a latch cannot
        // be drawn in one place and tapped in another.
        float latchWidth = Math.min(dp(180f), getWidth() * 0.5f);
        float latchLeft = getWidth() - latchWidth;
        float third = latchWidth / 3f;
        for (int i = 0; i < keyModifierCells.length; ++i) {
            keyModifierCells[i].set(latchLeft + i * third, 0,
                latchLeft + (i + 1) * third, header);
        }
        keyPicker.layout(getWidth(), dp(KEY_CELL_HEIGHT_DP));
        keyPicker.setScroll(keyPicker.scroll(), keyGridRect.height());
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
        if (keyMenuOpen) {
            drawKeyMenu(canvas);
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
        String combination = TouchKeyCatalog.combinationName(activity, spec.keyCode, spec.modifiers);
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
            case TOOL_CLEAR_KEY:
                // GeneralsX @feature Android touch editor 06/11/2026 The cell reads "Clear" while
                // the button has a key and is dimmed to "-" once it does not, so the toolbar shows
                // at a glance that there is nothing left to clear.
                return spec == null || !spec.isAssigned()
                    ? "\u2013" : activity.getString(R.string.touch_clear_key);
            case TOOL_SIZE_DOWN: return "\u2212";
            case TOOL_SIZE_UP: return "+";
            case TOOL_ALPHA_DOWN: return "A\u2212";
            case TOOL_ALPHA_UP: return "A+";
            case TOOL_CANCEL: return activity.getString(android.R.string.cancel);
            case TOOL_SAVE: return activity.getString(R.string.touch_save_button);
            case TOOL_SIZE_VALUE:
                // GeneralsX @tweak Android touch editor 05/11/2026 The size cell shows the number
                // alone. The unit is dp -- the same unit the launcher's Width/Height sliders are
                // labelled in -- so the suffix only stole width from the cell; the value, the
                // density conversion and the resize behaviour are unchanged.
                return spec == null ? "\u2013" : String.valueOf(Math.round(spec.widthDp));
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

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Draws the key-selection menu: a header of
     * three modifier latches, then the catalog's grid grouped and scrolling. The buttons underneath
     * are still drawn by drawEditor before this, so the menu reads as a panel over the layout
     * rather than replacing it -- which is what "without hiding the existing buttons" asks for.
     */
    private void drawKeyMenu(Canvas canvas) {
        layoutKeyMenu();
        chromePaint.setColor(toolbarBackground);
        chromePaint.setAlpha(0xF4);
        canvas.drawRect(keyMenuRect, chromePaint);
        chromePaint.setColor(accentColor);
        chromePaint.setAlpha(180);
        canvas.drawRect(keyMenuRect.left, keyMenuRect.bottom - Math.max(1f, dp(1f)),
            keyMenuRect.right, keyMenuRect.bottom, chromePaint);

        // The header: a title and the three modifier latches, which apply to the key tapped next.
        textPaint.setTextSize(dp(13f));
        textPaint.setColor(0xFF9FD4D8);
        textPaint.setAlpha(255);
        canvas.drawText(activity.getString(R.string.touch_key_menu_title),
            dp(8f), keyHeaderRect.centerY()
                - (textPaint.ascent() + textPaint.descent()) * 0.5f, textPaint);

        String[] modifierNames = {
            activity.getString(R.string.touch_modifier_ctrl),
            activity.getString(R.string.touch_modifier_shift),
            activity.getString(R.string.touch_modifier_alt)};
        int[] modifierFlags = {TouchKeyCatalog.MOD_CTRL, TouchKeyCatalog.MOD_SHIFT,
            TouchKeyCatalog.MOD_ALT};
        for (int i = 0; i < keyModifierCells.length; ++i) {
            RectF cell = keyModifierCells[i];
            boolean on = (keyMenuModifiers & modifierFlags[i]) != 0;
            if (on) {
                chromePaint.setColor(accentColor);
                chromePaint.setAlpha(70);
                canvas.drawRoundRect(cell, dp(6f), dp(6f), chromePaint);
            }
            textPaint.setColor(on ? accentColor : 0xFFEDEDED);
            textPaint.setAlpha(255);
            drawFittedText(canvas, modifierNames[i], cell, dp(13f));
        }

        // The grid, clipped to its own area so a scrolled row never bleeds into the header or the
        // toolbar, with a caption on the first row of each group.
        int checkpoint = canvas.save();
        canvas.clipRect(keyGridRect);
        RectF cell = new RectF();
        int lastGroup = -1;
        for (int i = 0; i < keyPicker.count(); ++i) {
            keyPicker.cellBounds(i, cell);
            float top = keyGridRect.top + cell.top - keyPicker.scroll();
            float bottom = top + cell.height();
            if (bottom < keyGridRect.top || top > keyGridRect.bottom) {
                continue;
            }
            cell.set(cell.left, top, cell.right, bottom);
            int group = keyPicker.entry(i).group;
            if (group != lastGroup) {
                lastGroup = group;
                textPaint.setTextSize(dp(10f));
                textPaint.setColor(0x99FFB74D);
                textPaint.setAlpha(0x99);
                canvas.drawText(groupName(group), dp(6f), top - dp(2f), textPaint);
            }
            textPaint.setColor(0xFFEDEDED);
            textPaint.setAlpha(255);
            drawFittedText(canvas, keyPicker.entry(i).name, cell, dp(14f));
        }
        canvas.restoreToCount(checkpoint);
    }

    /** A picker group's caption, localised. */
    private String groupName(int group) {
        switch (group) {
            case TouchKeyCatalog.GROUP_LETTER:
                return activity.getString(R.string.touch_key_group_letters);
            case TouchKeyCatalog.GROUP_DIGIT:
                return activity.getString(R.string.touch_key_group_numbers);
            case TouchKeyCatalog.GROUP_MODIFIER:
                return activity.getString(R.string.touch_key_group_modifiers);
            case TouchKeyCatalog.GROUP_SPECIAL:
                return activity.getString(R.string.touch_key_group_special);
            default:
                return activity.getString(R.string.touch_key_group_function);
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
        // GeneralsX @feature Android touch editor 06/11/2026 A cleared button is drawn with the
        // localised "Unassigned" caption instead of a stored label, so the state reads on the button
        // itself in the match as well as in the editors. Read from resources at draw time.
        String text = spec.isAssigned() ? spec.label : TouchKeyCatalog.unassignedName(activity);
        float size = dp(text.length() > 5 ? 12f : 15f) * scale;
        textPaint.setTextSize(size);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = rect.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(text, rect.centerX(), baseline, textPaint);
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
        if (keyMenuOpen) {
            layoutKeyMenu();
            // GeneralsX @feature Android touch editor 05/11/2026 The key menu owns the touch while
            // it is open: a press inside it is tested before the toolbar and before any button, so
            // a tap in it can never press a game button underneath or fire a toolbar cell. A press
            // on the toolbar itself is left to the toolbar, so Cancel and Save still dismiss the
            // menu and no one can get stuck in it.
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (keyMenuRect.contains(event.getX(), event.getY())) {
                    return onKeyMenuTouch(event);
                }
                keyMenuDownInMenu = false;
            } else if (keyMenuDownInMenu) {
                return onKeyMenuTouch(event);
            }
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


    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The key menu's own touch handling. It is
     * reached before anything else in the editor, so a press here can neither reach a game button
     * nor the toolbar. A press that moves past the slop scrolls the grid; a press that lifts where
     * it started chooses a key (or toggles a modifier latch). ACTION_CANCEL ends the gesture with
     * nothing chosen, and the menu stays open so a stray cancel does not lose the player's place.
     */
    private boolean onKeyMenuTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                downY = event.getY();
                keyMenuDownY = downY;
                keyMenuLastY = downY;
                keyMenuDownInMenu = keyMenuRect.contains(downX, downY);
                keyMenuScrolling = false;
                return keyMenuDownInMenu;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!keyMenuDownInMenu) {
                    return true;
                }
                float y = event.getY();
                if (keyGridRect.contains(downX, keyMenuDownY)) {
                    if (!keyMenuScrolling
                            && Math.abs(y - keyMenuDownY) >= touchSlop) {
                        keyMenuScrolling = true;
                    }
                    if (keyMenuScrolling) {
                        keyPicker.scrollBy(keyMenuLastY - y, keyGridRect.height());
                        invalidate();
                    }
                }
                keyMenuLastY = y;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!keyMenuDownInMenu) {
                    return true;
                }
                float x = event.getX();
                float y = event.getY();
                if (!keyMenuScrolling) {
                    boolean consumed = false;
                    for (int i = 0; i < keyModifierCells.length; ++i) {
                        if (keyModifierCells[i].contains(x, y)) {
                            toggleKeyMenuModifier(i);
                            consumed = true;
                            break;
                        }
                    }
                    if (!consumed && keyGridRect.contains(x, y)) {
                        int index = keyPicker.hitTest(x, y - keyGridRect.top,
                            keyGridRect.height());
                        if (index >= 0) {
                            chooseKey(keyPicker.entry(index).keyCode);
                        }
                    }
                }
                keyMenuDownInMenu = false;
                keyMenuScrolling = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                keyMenuDownInMenu = false;
                keyMenuScrolling = false;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    /** Latches or unlatches one modifier, so the next key tapped carries it. */
    private void toggleKeyMenuModifier(int index) {
        int[] flags = {TouchKeyCatalog.MOD_CTRL, TouchKeyCatalog.MOD_SHIFT, TouchKeyCatalog.MOD_ALT};
        keyMenuModifiers = (keyMenuModifiers ^ flags[index]) & TouchKeyCatalog.MOD_MASK;
        invalidate();
    }

    /**
     * Creates the button for the chosen key and its latched modifiers, selects it, closes the menu
     * and redraws at once. Nothing is created when the layout is full, and the menu closes without
     * a button so the maximum is reported exactly as the other Add entry points do.
     */
    private void chooseKey(int keyCode) {
        boolean added = editor.addButton(keyCode, keyMenuModifiers);
        closeMenus();
        if (!added) {
            toast(activity.getString(R.string.touch_too_many_buttons));
        }
        rebuildGeometry();
        invalidate();
    }

    private void runTool(int tool) {
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        switch (tool) {
            case TOOL_ADD:
                // GeneralsX @feature Android touch editor 05/11/2026 Add opens the key-selection
                // menu; the button is created only once a key is chosen, so no placeholder SPACE
                // button is ever made. A full layout is reported here, before the menu opens.
                if (!openKeyMenu()) {
                    toast(activity.getString(R.string.touch_too_many_buttons));
                }
                break;
            case TOOL_SHAPE:
                if (spec == null) {
                    toast(activity.getString(R.string.touch_select_button_first));
                } else {
                    shapeMenuOpen = !shapeMenuOpen;
                    keyMenuOpen = false;
                }
                break;
            case TOOL_CLEAR_KEY:
                // GeneralsX @feature Android touch editor 06/11/2026 Clears the mapping and keeps
                // the button. It is the model's clearSelectedKey(), the same call the launcher page
                // makes, so "clear" has one implementation; Delete and the profile reset are
                // different calls and stay different operations.
                if (spec == null) {
                    toast(activity.getString(R.string.touch_select_button_first));
                } else if (editor.clearSelectedKey()) {
                    toast(activity.getString(R.string.touch_key_cleared));
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
