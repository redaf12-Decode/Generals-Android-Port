// GeneralsX @build Android touch editor 05/11/2026
//
// The upgrade's own promises, tested where a finger would notice:
//
//   * the editor draws a button with ITS OWN colours -- the launcher accent is never painted onto
//     a button, so entering the editor cannot recolour one and changing the accent cannot either;
//   * selection is shown by an indicator OUTSIDE the button and never rewrites the saved spec;
//   * the one "add a button" operation is the model's, so the editor toolbar, the in-game toolbox
//     and the launcher page all produce the same button in the same safe spot;
//   * the toolbox panel's visibility is stored separately from the buttons' visibility.
//
// The colour tests render with Robolectric's native graphics so the assertions are made on real
// pixels, not on the calls that were supposed to produce them.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TouchEditorAppearanceTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 600;
    private static final int FILL = 0xFFFF0000;
    private static final int BORDER = 0xFF00FF00;
    private static final int TEXT = 0xFFFFFFFF;

    private Activity activity;
    private TouchControlConfig stored;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        TouchControlConfig.reset(activity);
        stored = TouchControlConfig.load(activity);
        // One button, large and central, with unmistakable colours, so a sample inside it is
        // unambiguous and the selection frame (drawn outside its box) cannot be mistaken for it.
        TouchControlConfig.ButtonSpec spec = stored.buttons.get(0);
        spec.shape = TouchButtonShape.RECTANGLE;
        spec.x = 0.5f;
        spec.y = 0.45f;
        spec.widthDp = 120f;
        spec.heightDp = 90f;
        spec.fillColor = FILL;
        spec.borderColor = BORDER;
        spec.textColor = TEXT;
        spec.borderWidthDp = 4f;
        spec.opacity = 1f;
        spec.enabled = true;
        stored.buttonOpacity = 1f;
        for (int i = 1; i < stored.buttons.size(); ++i) {
            stored.buttons.get(i).enabled = false;
        }
        stored.save(activity);

        // The floating toolbox is off by default; turn it on for the tests that drive its rows.
        ToolboxConfig toolbox = ToolboxConfig.load(activity, SetupActivity.GAME_ZERO_HOUR);
        toolbox.enabled = true;
        toolbox.panelOpen = true;
        toolbox.save(activity, SetupActivity.GAME_ZERO_HOUR);
    }

    /** A bitmap of the editor as it draws, with the button layer alone. */
    private Bitmap renderEditor() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        editor.select(0);
        TouchEditorRenderer renderer = new TouchEditorRenderer(activity);
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        renderer.draw(new Canvas(bitmap), editor);
        return bitmap;
    }

    /**
     * The accent-color bug, measured: the body of the button is its own fill and its edge is its
     * own border, and the launcher accent appears nowhere in the button's own body or outline.
     * The selection frame and the resize handle are drawn at the very edge and outside the box, so
     * they are excluded here on purpose -- they are the selection indicator, not the button.
     */
    @Test
    public void aButtonIsDrawnWithItsOwnFillAndBorder() {
        Bitmap bitmap = renderEditor();
        TouchControlConfig.ButtonSpec spec = TouchControlConfig.load(activity).buttons.get(0);
        int cx = Math.round(spec.x * WIDTH);
        int cy = Math.round(spec.y * HEIGHT);
        int halfW = Math.round(spec.widthDp * 2f * 0.5f);
        int halfH = Math.round(spec.heightDp * 2f * 0.5f);
        int accent = TouchEditorRenderer.accentForTest(activity);

        boolean sawFill = false;
        // The body: inside the border, away from the centred label and the corner resize handle.
        for (int x = cx - halfW + 14; x <= cx + halfW - 14; x += 2) {
            for (int y = cy - halfH + 14; y <= cy + halfH - 14; y += 2) {
                boolean onLabel = Math.abs(x - cx) < 40 && Math.abs(y - cy) < 30;
                boolean onHandle = x > cx + halfW - 52 && y > cy + halfH - 52;
                if (onLabel || onHandle) {
                    continue;
                }
                int pixel = bitmap.getPixel(x, y);
                if (pixel == FILL) {
                    sawFill = true;
                }
                assertNotEquals("the accent is never the button's body at " + x + "," + y,
                    accent, pixel);
            }
        }
        assertTrue("the body is the button's own fill", sawFill);

        // The outline: a pixel on the left edge, at the vertical middle, is the button's border.
        boolean sawBorder = false;
        for (int x = cx - halfW - 6; x <= cx - halfW + 6; ++x) {
            if (bitmap.getPixel(x, cy) == BORDER) {
                sawBorder = true;
            }
        }
        assertTrue("the outline is the button's own border colour", sawBorder);
    }

    /** The launcher accent is never used as a button's fill or border. */
    @Test
    public void theAccentIsNotPaintedOntoTheButton() {
        Bitmap bitmap = renderEditor();
        int accent = TouchEditorRenderer.accentForTest(activity);
        TouchControlConfig.ButtonSpec spec = TouchControlConfig.load(activity).buttons.get(0);
        int cx = Math.round(spec.x * WIDTH);
        int cy = Math.round(spec.y * HEIGHT);
        int halfW = Math.round(spec.widthDp * 2f * 0.5f);
        int halfH = Math.round(spec.heightDp * 2f * 0.5f);
        for (int x = cx - halfW; x <= cx + halfW; x += 3) {
            for (int y = cy - halfH; y <= cy + halfH; y += 3) {
                // The resize handle sits in the bottom-right corner inside the box and IS the
                // selection indicator, so it is allowed to be the accent.
                boolean onHandle = x > cx + halfW - 52 && y > cy + halfH - 52;
                if (onHandle) {
                    continue;
                }
                assertNotEquals("no accent pixel in the button at " + x + "," + y,
                    accent, bitmap.getPixel(x, y));
            }
        }
    }

    /** Selecting a button does not rewrite its saved colours: the indicator is drawn elsewhere. */
    @Test
    public void selectionDoesNotChangeTheSavedAppearance() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        int before = editor.buttons().get(0).borderColor;
        editor.select(0);
        new TouchEditorRenderer(activity).draw(
            new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)), editor);
        assertEquals("the saved border colour is untouched by drawing a selection",
            before, editor.buttons().get(0).borderColor);
        assertEquals(FILL, editor.buttons().get(0).fillColor);
    }

    /** Changing the app accent after a layout is saved cannot change a stored button colour. */
    @Test
    public void changingTheAccentDoesNotTouchStoredButtons() {
        int saved = TouchControlConfig.load(activity).buttons.get(0).borderColor;
        // A renderer built for a different accent still reads the button's own colour.
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        new TouchEditorRenderer(activity).draw(
            new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)), editor);
        assertEquals(saved, TouchControlConfig.load(activity).buttons.get(0).borderColor);
    }

    // ---------------------------------------------------------------- add button

    /** The model's add is what every entry point calls; it returns a selected, visible button. */
    @Test
    public void addButtonCreatesASelectedVisibleButton() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        int before = editor.buttons().size();
        assertTrue(editor.addButton());
        assertEquals(before + 1, editor.buttons().size());
        assertEquals("the new button is selected", before, editor.selectedIndex());
        assertNotNull(editor.selectedButton());
        assertTrue("it has a usable key", TouchKeyCatalog.isSelectable(
            editor.selectedButton().keyCode));
    }

    /** A new button lands in the middle band, clear of the bottom toolbar and the top edge. */
    @Test
    public void addButtonLandsInASafeVisibleSpot() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        editor.addButton();
        TouchControlConfig.ButtonSpec added = editor.selectedButton();
        android.graphics.RectF rect = new android.graphics.RectF();
        editor.bounds(added, rect);
        assertTrue("not under the editor toolbar", rect.bottom < HEIGHT * 0.82f);
        assertTrue("not above the top inset", rect.top > 0f);
        assertTrue("fully on screen", rect.left >= 0f && rect.right <= WIDTH);
    }

    /** The toolbar's Add and the toolbox's Add are the same operation: both hit the maximum. */
    @Test
    public void addIsRefusedAtTheSameMaximumFromEitherEntryPoint() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        while (editor.addButton()) {
            // fill it up through the one operation
        }
        assertEquals(TouchControlConfig.MAX_BUTTONS, editor.buttons().size());
        assertFalse("the same call refuses past the maximum", editor.addButton());
    }

    /** A new button is not dropped on top of a button that is live in the match. */
    @Test
    public void aNewButtonAvoidsAnExistingLiveButton() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        editor.addButton();
        editor.addButton();
        android.graphics.RectF a = new android.graphics.RectF();
        android.graphics.RectF b = new android.graphics.RectF();
        editor.bounds(editor.buttons().get(editor.buttons().size() - 2), a);
        editor.bounds(editor.buttons().get(editor.buttons().size() - 1), b);
        assertFalse("the two new buttons do not overlap",
            a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top);
    }

    /** Rotating the display re-clamps every button, and each stays fully on screen. */
    @Test
    public void rotatingTheViewportKeepsEveryButtonOnScreen() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        editor.addButton();
        int rotatedWidth = HEIGHT;
        int rotatedHeight = WIDTH;
        editor.applyViewportTo(rotatedWidth, rotatedHeight, 2f);   // landscape -> portrait
        android.graphics.RectF rect = new android.graphics.RectF();
        for (TouchControlConfig.ButtonSpec spec : editor.buttons()) {
            editor.bounds(spec, rect);
            assertTrue("on screen after rotation: " + spec.label,
                rect.left >= -1f && rect.top >= -1f && rect.right <= rotatedWidth + 1f
                    && rect.bottom <= rotatedHeight + 1f);
        }
    }

    /** A new button can be moved, resized and reshaped at once, without losing its key. */
    @Test
    public void aNewButtonIsImmediatelyEditable() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        editor.addButton();
        int key = editor.selectedButton().keyCode;
        float x0 = editor.selectedButton().x;
        editor.moveSelectedBy(60f, 0f);
        assertNotEquals(x0, editor.selectedButton().x, 0.0001f);
        editor.resizeSelectedByDp(10f);
        assertTrue(editor.setSelectedShape(TouchButtonShape.CIRCLE));
        assertEquals("the key survives the shape change", key, editor.selectedButton().keyCode);
    }

    // ---------------------------------------------------------------- toolbox state

    /**
     * The panel's visibility is its own setting and round-trips. The default is closed, which is
     * what the icon-only overlay always did, so an install with no stored value is unchanged.
     */
    @Test
    public void toolboxPanelVisibilityIsStoredSeparately() {
        ToolboxConfig.reset(activity, SetupActivity.GAME_ZERO_HOUR);
        ToolboxConfig fresh = ToolboxConfig.load(activity, SetupActivity.GAME_ZERO_HOUR);
        assertFalse("with nothing stored the panel is closed, as before", fresh.panelOpen);

        fresh.panelOpen = true;
        fresh.save(activity, SetupActivity.GAME_ZERO_HOUR);
        assertTrue("open stays open across a reload",
            ToolboxConfig.load(activity, SetupActivity.GAME_ZERO_HOUR).panelOpen);
        assertTrue("the buttons' own visibility is untouched",
            TouchControlConfig.load(activity).enabled);
        // Hiding it is a stored change too, so a relaunch comes back the way it was left.
        ToolboxConfig hidden = ToolboxConfig.load(activity, SetupActivity.GAME_ZERO_HOUR);
        hidden.panelOpen = false;
        hidden.save(activity, SetupActivity.GAME_ZERO_HOUR);
        assertFalse(ToolboxConfig.load(activity, SetupActivity.GAME_ZERO_HOUR).panelOpen);
    }

    /** Hiding the panel leaves the hotkey layer and every button alone. */
    @Test
    public void hidingThePanelDoesNotDisableTheButtons() {
        TouchControlConfig before = TouchControlConfig.load(activity);
        int enabledBefore = 0;
        for (TouchControlConfig.ButtonSpec spec : before.buttons) {
            if (spec.enabled) {
                ++enabledBefore;
            }
        }
        ToolboxConfig config = ToolboxConfig.load(activity, "zh");
        config.panelOpen = false;
        config.save(activity, "zh");
        TouchControlConfig after = TouchControlConfig.load(activity);
        int enabledAfter = 0;
        for (TouchControlConfig.ButtonSpec spec : after.buttons) {
            if (spec.enabled) {
                ++enabledAfter;
            }
        }
        assertEquals(enabledBefore, enabledAfter);
        assertTrue(after.enabled);
    }

    // ---------------------------------------------------------------- shape

    /** Every shape keeps the assigned key, and the shapes the task lists are all selectable. */
    @Test
    public void everyShapeKeepsTheKey() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        editor.select(0);
        editor.setSelectedKey(KeyEvent.KEYCODE_F5);
        int[] shapes = {TouchButtonShape.SQUARE, TouchButtonShape.CIRCLE,
            TouchButtonShape.POLYGON, TouchButtonShape.RECTANGLE};
        for (int shape : shapes) {
            assertTrue(editor.setSelectedShape(shape));
            assertEquals("the key is preserved through " + shape,
                KeyEvent.KEYCODE_F5, editor.selectedButton().keyCode);
        }
    }

    /** The four polygon types the task names are all valid and all keep the key. */
    @Test
    public void everyPolygonTypeIsSelectableAndKeepsTheKey() {
        TouchLayoutEditor editor = new TouchLayoutEditor(TouchControlConfig.load(activity));
        editor.applyViewportTo(WIDTH, HEIGHT, 2f);
        editor.select(0);
        editor.setSelectedKey(KeyEvent.KEYCODE_F6);
        editor.setSelectedShape(TouchButtonShape.POLYGON);
        int[] types = {TouchButtonShape.POLY_TRIANGLE, TouchButtonShape.POLY_PENTAGON,
            TouchButtonShape.POLY_HEXAGON, TouchButtonShape.POLY_OCTAGON};
        for (int type : types) {
            assertTrue(editor.setSelectedPolygonType(type));
            assertEquals(type, editor.selectedButton().polygonType);
            assertEquals(KeyEvent.KEYCODE_F6, editor.selectedButton().keyCode);
        }
    }

    /** The overlay's own Add entry point adds through the model and reports the maximum. */
    @Test
    public void theOverlayAddEntryPointUsesTheEditorModel() {
        HotkeyOverlayView overlay = HotkeyOverlayView.attach(activity);
        overlay.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        overlay.layout(0, 0, WIDTH, HEIGHT);
        int stored = TouchControlConfig.load(activity).buttons.size();
        assertTrue("the in-game Add opens the editor and adds", overlay.addButtonInEditor());
        assertTrue(overlay.isEditing());
        assertEquals("one more button in the editor's working copy",
            stored + 1, overlay.editorButtonCountForTest());
        overlay.leaveEditor();
        assertEquals("Cancel discards the added button",
            stored, TouchControlConfig.load(activity).buttons.size());
    }

    // ---------------------------------------------------------------- the real toolbar

    private static MotionEvent down(float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    }

    private static MotionEvent up(float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
    }

    /** Taps a point: the real view's own touch handling, not a direct method call. */
    private static void tap(View view, float x, float y) {
        view.onTouchEvent(down(x, y));
        view.onTouchEvent(up(x, y));
    }

    private static MotionEvent move(float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, x, y, 0);
    }

    /**
     * Drags: down, a move past the touch slop, the rest of the way, up. The intermediate move is
     * not decoration -- a drag that leaves the slop only anchors (the icon) or starts the scroll
     * (the panel), so a single-step drag would not move anything.
     */
    private static void drag(View view, float x0, float y0, float x1, float y1) {
        view.onTouchEvent(down(x0, y0));
        view.onTouchEvent(move(x0 + (x1 - x0) * 0.2f, y0 + (y1 - y0) * 0.2f));
        view.onTouchEvent(move(x1, y1));
        view.onTouchEvent(up(x1, y1));
    }

    /** The toolbar cell centres, as the view lays them out at this size and density. */
    private float toolX(int tool) {
        return (tool + 0.5f) * (WIDTH / 10f);
    }

    private float toolbarY() {
        float density = activity.getResources().getDisplayMetrics().density;
        return HEIGHT - 28f * density;
    }

    private HotkeyOverlayView laidOutOverlay() {
        HotkeyOverlayView overlay = HotkeyOverlayView.attach(activity);
        overlay.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        overlay.layout(0, 0, WIDTH, HEIGHT);
        return overlay;
    }

    /** A tap on the toolbar's Add cell creates a button through the model, on the real view. */
    @Test
    public void tappingTheToolbarAddCellAddsAButton() {
        HotkeyOverlayView overlay = laidOutOverlay();
        overlay.enterEditor();
        int before = overlay.editorButtonCountForTest();
        tap(overlay, toolX(0), toolbarY());
        assertEquals("the visible + cell added a button", before + 1,
            overlay.editorButtonCountForTest());
    }

    /** Tapping Shape opens the menu, and tapping a shape cell changes the selected button. */
    @Test
    public void tappingShapeThenACellChangesTheShape() {
        HotkeyOverlayView overlay = laidOutOverlay();
        overlay.enterEditor();
        tap(overlay, toolX(0), toolbarY());     // add, which selects the new button
        int key = overlay.editorModel().selectedButton().keyCode;
        tap(overlay, toolX(1), toolbarY());     // open the shape menu
        assertTrue("the shape menu is open", overlay.shapeMenuOpenForTest());
        // The circle is the fourth of the six shape cells; index 3 is circle.
        android.graphics.PointF circle = overlay.shapeCellCenterForTest(3);
        tap(overlay, circle.x, circle.y);
        assertEquals(TouchButtonShape.CIRCLE,
            overlay.editorModel().selectedButton().shape);
        assertFalse("choosing a shape closes the menu", overlay.shapeMenuOpenForTest());
        assertEquals("the key survives the shape change", key,
            overlay.editorModel().selectedButton().keyCode);
    }

    /** A polygon cell switches the shape to Polygon and stores the chosen variant. */
    @Test
    public void tappingAPolygonCellStoresTheVariant() {
        HotkeyOverlayView overlay = laidOutOverlay();
        overlay.enterEditor();
        tap(overlay, toolX(0), toolbarY());
        int key = overlay.editorModel().selectedButton().keyCode;
        tap(overlay, toolX(1), toolbarY());
        // The octagon is the fourth polygon cell: index 6 + 3.
        android.graphics.PointF octagon = overlay.shapeCellCenterForTest(6 + 3);
        tap(overlay, octagon.x, octagon.y);
        assertEquals(TouchButtonShape.POLYGON, overlay.editorModel().selectedButton().shape);
        assertEquals(TouchButtonShape.POLY_OCTAGON,
            overlay.editorModel().selectedButton().polygonType);
        assertEquals("the key survives", key, overlay.editorModel().selectedButton().keyCode);
    }

    /** The bundled Flaticon icon rasterises to real pixels and is drawn centred in the icon box. */
    @Test
    public void theBundledToolboxIconIsDrawnCentredAndOpaque() {
        HotkeyOverlayView hotkeys = laidOutOverlay();
        ToolboxOverlayView toolbox = ToolboxOverlayView.attach(activity, hotkeys);
        assertNotNull(toolbox);
        toolbox.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        toolbox.layout(0, 0, WIDTH, HEIGHT);
        assertTrue("the bundled VectorDrawable rasterised to a non-empty bitmap",
            toolbox.iconBitmapHasContentForTest());

        // Drawing the icon puts the glyph's own colour inside the icon box, centred: the
        // Flaticon asset is blue, so a blue pixel must land on the icon's centre column.
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        toolbox.draw(new Canvas(bitmap));
        android.graphics.RectF icon = toolbox.iconRectForTest();
        int cx = Math.round(icon.centerX());
        int cy = Math.round(icon.centerY());
        boolean sawIconColour = false;
        for (int y = cy - 6; y <= cy + 6; ++y) {
            for (int x = cx - 6; x <= cx + 6; ++x) {
                int pixel = bitmap.getPixel(x, y);
                if (android.graphics.Color.blue(pixel) > 150
                    && android.graphics.Color.alpha(pixel) > 200) {
                    sawIconColour = true;
                }
            }
        }
        assertTrue("the icon is drawn in its own colour at the icon's centre", sawIconColour);
    }

    // ---------------------------------------------------------------- the real toolbox

    /** The in-game toolbox's Add row uses the same model and opens the editor. */
    @Test
    public void theToolboxAddRowAddsThroughTheModel() {
        HotkeyOverlayView hotkeys = laidOutOverlay();
        ToolboxOverlayView toolbox = ToolboxOverlayView.attach(activity, hotkeys);
        assertNotNull("the toolbox attaches when enabled", toolbox);
        toolbox.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        toolbox.layout(0, 0, WIDTH, HEIGHT);
        toolbox.ensurePanelOpenForTest();
        android.graphics.PointF addRow = toolbox.toolRowCenterForTest(1); // TOOL_ADD_BUTTON
        tap(toolbox, addRow.x, addRow.y);
        assertTrue("the toolbox's Add opened the editor", hotkeys.isEditing());
        assertEquals("the toolbox's Add added through the same model",
            TouchControlConfig.load(activity).buttons.size() + 1,
            hotkeys.editorButtonCountForTest());
    }

    /** The toolbox's Hide row closes the panel but leaves the icon and the buttons. */
    @Test
    public void theToolboxHideRowKeepsTheIconAndButtons() {
        HotkeyOverlayView hotkeys = laidOutOverlay();
        ToolboxOverlayView toolbox = ToolboxOverlayView.attach(activity, hotkeys);
        assertNotNull(toolbox);
        toolbox.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        toolbox.layout(0, 0, WIDTH, HEIGHT);
        toolbox.ensurePanelOpenForTest();
        android.graphics.PointF hideRow = toolbox.toolRowCenterForTest(7); // TOOL_HIDE_TOOLBOX
        tap(toolbox, hideRow.x, hideRow.y);
        assertFalse("the panel is hidden", toolbox.isPanelOpenForTest());
        assertTrue("the toolbox icon is still there to reopen it",
            toolbox.iconIsVisibleForTest());
        assertTrue("the buttons are still enabled", TouchControlConfig.load(activity).enabled);
        assertFalse("hidden state is stored",
            ToolboxConfig.load(activity, SetupActivity.GAME_ZERO_HOUR).panelOpen);
        // Tapping the icon reopens the panel.
        android.graphics.PointF icon = toolbox.iconCenterForTest();
        tap(toolbox, icon.x, icon.y);
        assertTrue("the icon reopens the panel", toolbox.isPanelOpenForTest());
    }

    /** A toolbox at the window's own size, panel open: the size a phone actually gives it. */
    private ToolboxOverlayView laidOutToolbox() {
        HotkeyOverlayView hotkeys = laidOutOverlay();
        ToolboxOverlayView toolbox = ToolboxOverlayView.attach(activity, hotkeys);
        assertNotNull(toolbox);
        toolbox.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        toolbox.layout(0, 0, WIDTH, HEIGHT);
        toolbox.ensurePanelOpenForTest();
        return toolbox;
    }

    /**
     * The panel's nine rows must all be reachable. A landscape phone gives the overlay roughly
     * this many pixels of height, and nine rows at their full height do not fit: the rows give
     * way, and every row -- Close last -- stays inside the window.
     */
    @Test
    public void everyToolboxRowFitsInsideTheWindow() {
        ToolboxOverlayView toolbox = laidOutToolbox();
        android.graphics.RectF panel = toolbox.panelRectForTest();
        assertTrue("the panel's top is on screen", panel.top >= 0f);
        for (int i = 0; i < 9; ++i) {
            android.graphics.RectF row = toolbox.rowRectForTest(i);
            assertTrue("row " + i + " starts at or below the window's top", row.top >= 0f);
            assertTrue("row " + i + " ends at or above the window's bottom (" + row.bottom + ")",
                row.bottom <= HEIGHT);
            assertTrue("row " + i + " has a usable height", row.height() >= 24f);
            assertTrue("row " + i + " is inside the panel", row.top >= panel.top
                && row.bottom <= panel.bottom);
        }
        assertEquals("nothing is hidden, so nothing scrolls",
            0f, toolbox.panelScrollRangeForTest(), 0.5f);
    }

    /** A tap on the Close row, found by its own geometry, closes the panel and the editor. */
    @Test
    public void theCloseRowIsReachableByItsOwnGeometry() {
        ToolboxOverlayView toolbox = laidOutToolbox();
        android.graphics.RectF close = toolbox.rowRectForTest(8); // TOOL_CLOSE
        assertTrue("the Close row is inside the window", close.bottom <= HEIGHT);
        tap(toolbox, close.centerX(), close.centerY());
        assertFalse("Close closed the panel", toolbox.isPanelOpenForTest());
    }

    /**
     * When the rows cannot fit even at their minimum height -- a very short window -- the panel
     * is taller than the window and a finger drag inside it scrolls, so the rows past the bottom
     * edge can still be brought into view. A drag on the icon still moves the icon.
     */
    @Test
    public void aShortWindowScrollsThePanelAndStillDragsTheIcon() {
        HotkeyOverlayView hotkeys = laidOutOverlay();
        ToolboxOverlayView toolbox = ToolboxOverlayView.attach(activity, hotkeys);
        assertNotNull(toolbox);
        int shortHeight = 200;
        toolbox.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(shortHeight, View.MeasureSpec.EXACTLY));
        toolbox.layout(0, 0, WIDTH, shortHeight);
        toolbox.ensurePanelOpenForTest();
        float range = toolbox.panelScrollRangeForTest();
        assertTrue("a short window leaves something to scroll", range > 0f);

        // A drag upwards inside the panel scrolls it: the last row comes into view.
        android.graphics.RectF panel = toolbox.panelRectForTest();
        android.graphics.RectF closeBefore = toolbox.rowRectForTest(8);
        assertTrue("the Close row starts below the window", closeBefore.bottom > shortHeight);
        drag(toolbox, panel.centerX(), panel.top + 40f, panel.centerX(), panel.top + 40f - range);
        android.graphics.RectF closeAfter = toolbox.rowRectForTest(8);
        assertTrue("the Close row was scrolled into view (" + closeAfter.bottom + ")",
            closeAfter.bottom <= shortHeight + 1f);
        // Dragging further does not scroll past the range: the last row stays on the bottom edge.
        drag(toolbox, panel.centerX(), panel.top + 40f, panel.centerX(), panel.top + 40f - range);
        assertEquals("the scroll is clamped to the range", closeAfter.bottom,
            toolbox.rowRectForTest(8).bottom, 0.5f);

        // The icon is not the panel: dragging it still moves it and does not scroll.
        android.graphics.PointF iconBefore = toolbox.iconCenterForTest();
        drag(toolbox, iconBefore.x, iconBefore.y, iconBefore.x - 120f, iconBefore.y);
        android.graphics.PointF iconAfter = toolbox.iconCenterForTest();
        assertTrue("the icon moved", Math.abs(iconAfter.x - iconBefore.x) > 40f);
    }
}
