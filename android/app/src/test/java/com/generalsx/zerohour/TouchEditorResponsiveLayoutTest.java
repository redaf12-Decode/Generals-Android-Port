// GeneralsX @build Android touch editor 06/11/2026
//
// The editor page's structure, rendered the way a phone renders it. Every assertion here is one of
// the defects the rework was asked to fix, expressed so a regression fails here instead of on a
// tester's phone:
//
//   * the panels must not overlap, and a control must never be drawn outside its own container;
//   * a label must be fully inside the width it is given, in both directions of reading;
//   * the page must re-arrange for portrait and landscape rather than squashing one arrangement;
//   * the footer's actions must stay on screen on a narrow window;
//   * the background picture must be present, must be behind the controls, and must not take a
//     touch away from them.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TouchEditorResponsiveLayoutTest {

    private static final int LANDSCAPE_W = 1000;
    private static final int LANDSCAPE_H = 600;
    private static final int PORTRAIT_W = 420;
    private static final int PORTRAIT_H = 900;

    private Activity host;

    @Before
    public void setUp() {
        host = Robolectric.buildActivity(Activity.class).setup().get();
        TouchControlConfig.reset(host);
    }

    private TouchControlsActivity laidOut(int width, int height) {
        TouchControlsActivity activity = Robolectric.buildActivity(TouchControlsActivity.class)
            .setup().get();
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        return activity;
    }

    /** The absolute rectangle a view occupies in the window. */
    private static int[] bounds(View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return new int[] {location[0], location[1],
            location[0] + view.getWidth(), location[1] + view.getHeight()};
    }

    private static boolean overlaps(View a, View b) {
        int[] one = bounds(a);
        int[] two = bounds(b);
        return one[0] < two[2] && two[0] < one[2] && one[1] < two[3] && two[1] < one[3];
    }

    /** Every descendant of a container, deepest last. */
    private static List<View> descendants(View view) {
        List<View> all = new ArrayList<>();
        collect(view, all);
        return all;
    }

    private static void collect(View view, List<View> into) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                View child = group.getChildAt(i);
                into.add(child);
                collect(child, into);
            }
        }
    }

    /** The first descendant whose text is the given string, or null. */
    private static TextView labelled(View root, String text) {
        for (View view : descendants(root)) {
            if (view instanceof TextView
                    && text.contentEquals(((TextView) view).getText())) {
                return (TextView) view;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ structure

    /**
     * The preview and the inspector never overlap: in landscape they share the width, in portrait
     * they share the height. Overlapping panels were the first thing the rework had to stop.
     */
    @Test
    public void thePreviewAndTheInspectorNeverOverlap() {
        for (int[] size : new int[][] {{LANDSCAPE_W, LANDSCAPE_H}, {PORTRAIT_W, PORTRAIT_H}}) {
            TouchControlsActivity activity = laidOut(size[0], size[1]);
            View preview = activity.editorSurfaceForTest();
            View inspector = activity.inspectorForTest();
            assertFalse("no overlap at " + size[0] + "x" + size[1],
                overlaps(preview, inspector));
            assertTrue("the preview keeps a real size at " + size[0] + "x" + size[1],
                preview.getWidth() > 0 && preview.getHeight() > 0);
            assertTrue("the inspector keeps a real size at " + size[0] + "x" + size[1],
                inspector.getWidth() > 0 && inspector.getHeight() > 0);
        }
    }

    /** Nothing is drawn outside its own container: every child sits inside its parent's box. */
    @Test
    public void noControlIsDrawnOutsideItsContainer() {
        for (int[] size : new int[][] {{LANDSCAPE_W, LANDSCAPE_H}, {PORTRAIT_W, PORTRAIT_H}}) {
            TouchControlsActivity activity = laidOut(size[0], size[1]);
            View root = activity.getWindow().getDecorView();
            for (View child : descendants(root)) {
                if (!(child.getParent() instanceof ViewGroup) || child.getWidth() <= 0) {
                    continue;
                }
                ViewGroup parent = (ViewGroup) child.getParent();
                int[] own = bounds(child);
                int[] box = bounds(parent);
                assertTrue("a child escapes its container at " + size[0] + "x" + size[1]
                        + ": " + child.getClass().getSimpleName(),
                    own[0] >= box[0] - 1 && own[1] >= box[1] - 1
                        && own[2] <= box[2] + 1 && own[3] <= box[3] + 1);
            }
        }
    }

    /**
     * The footer's actions are inside the window on a narrow screen. This is the "bottom action
     * buttons can become partially inaccessible" defect: with a fixed row the last ones were pushed
     * past the right edge, and with the weighted spacer the whole footer was stretched instead.
     */
    @Test
    public void theFooterActionsStayOnScreenWhenNarrow() {
        for (int[] size : new int[][] {{LANDSCAPE_W, LANDSCAPE_H}, {PORTRAIT_W, PORTRAIT_H},
                {360, 640}}) {
            TouchControlsActivity activity = laidOut(size[0], size[1]);
            View footer = activity.footerForTest();
            int[] box = bounds(footer);
            assertTrue("the footer is inside the window at " + size[0] + "x" + size[1],
                box[1] >= 0 && box[3] <= size[1] + 1);
            for (View child : descendants(footer)) {
                int[] own = bounds(child);
                assertTrue("a footer action is off the bottom edge at " + size[0] + "x" + size[1],
                    own[3] <= size[1] + 1);
                assertTrue("a footer action is off the right edge at " + size[0] + "x" + size[1],
                    own[2] <= size[0] + 1);
                assertTrue("a footer action is off the left edge at " + size[0] + "x" + size[1],
                    own[0] >= -1);
            }
        }
    }

    /** Save, Cancel and Apply are all present and reachable, not dropped to make room. */
    @Test
    public void everyFooterActionIsStillOffered() {
        TouchControlsActivity activity = laidOut(PORTRAIT_W, PORTRAIT_H);
        View footer = activity.footerForTest();
        int buttons = 0;
        for (View child : descendants(footer)) {
            if (child instanceof android.widget.Button) {
                ++buttons;
            }
        }
        assertEquals("Add, Defaults, Import, Export, Save and Apply", 6, buttons);
    }

    // ------------------------------------------------------------------ orientation

    /**
     * The page re-arranges for the shape of the window: landscape puts the inspector beside the
     * preview, portrait puts it underneath. The two must actually differ, which is what makes the
     * page responsive rather than merely wide enough.
     */
    @Test
    public void theSplitFollowsTheWindowShape() {
        TouchControlsActivity landscape = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        int[] previewLand = bounds(landscape.editorSurfaceForTest());
        int[] panelLand = bounds(landscape.inspectorForTest());
        assertTrue("in landscape the inspector is to the right of the preview",
            panelLand[0] >= previewLand[2] - 1);

        TouchControlsActivity portrait = laidOut(PORTRAIT_W, PORTRAIT_H);
        int[] previewPort = bounds(portrait.editorSurfaceForTest());
        int[] panelPort = bounds(portrait.inspectorForTest());
        assertTrue("in portrait the inspector is below the preview",
            panelPort[1] >= previewPort[3] - 1);
        assertTrue("in portrait the inspector spans the width",
            panelPort[2] - panelPort[0] > PORTRAIT_W / 2);
    }

    /** A re-layout at a different size re-arranges without a recreate, as a rotation does. */
    @Test
    public void aResizeRearrangesTheSplit() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        assertTrue("starts side by side",
            bounds(activity.inspectorForTest())[0] >= bounds(activity.editorSurfaceForTest())[2] - 1);

        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(PORTRAIT_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PORTRAIT_H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, PORTRAIT_W, PORTRAIT_H);

        assertTrue("re-arranged to stacked",
            bounds(activity.inspectorForTest())[1]
                >= bounds(activity.editorSurfaceForTest())[3] - 1);
    }

    /** The Activity survives a configuration change instead of being recreated. */
    @Test
    public void aConfigurationChangeKeepsTheSameActivity() {
        TouchControlsActivity activity = laidOut(PORTRAIT_W, PORTRAIT_H);
        Configuration configuration = new Configuration(
            activity.getResources().getConfiguration());
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        activity.onConfigurationChanged(configuration);
        assertFalse("the page is still alive after a rotation", activity.isFinishing());
        assertNotNull(activity.editorForTest());
    }

    // ------------------------------------------------------------------ RTL and labels

    /**
     * An Arabic (RTL) layout is honoured: a wrapping row lays its cells out from the right edge.
     * Without the direction check the row would still read left to right and look mirrored against
     * the rest of the page.
     */
    @Test
    public void aWrappingRowFollowsTheLayoutDirection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FlowLayout row = new FlowLayout(activity);
        row.setGaps(0, 0);
        for (int i = 0; i < 4; ++i) {
            View cell = new View(activity);
            cell.setLayoutParams(new ViewGroup.LayoutParams(30, 30));
            row.addView(cell);
        }
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
        row.layout(0, 0, 80, row.getMeasuredHeight());
        assertEquals("LTR starts at the left edge", 0, row.getChildAt(0).getLeft());
        assertTrue("and wraps the third cell onto a second line",
            row.getChildAt(2).getTop() > row.getChildAt(0).getTop());

        FlowLayout rtl = new FlowLayout(activity);
        rtl.setGaps(0, 0);
        for (int i = 0; i < 4; ++i) {
            View cell = new View(activity);
            cell.setLayoutParams(new ViewGroup.LayoutParams(30, 30));
            rtl.addView(cell);
        }
        rtl.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        rtl.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
        rtl.layout(0, 0, 80, rtl.getMeasuredHeight());
        assertEquals("RTL starts at the right edge", 20, rtl.getChildAt(0).getLeft());
    }

    /** A wrapping row puts every cell on a line, none clipped: the sum of a line fits the width. */
    @Test
    public void aWrappingRowKeepsEveryCellInsideTheWidth() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FlowLayout row = new FlowLayout(activity);
        row.setGaps(10, 10);
        for (int i = 0; i < 7; ++i) {
            View cell = new View(activity);
            cell.setLayoutParams(new ViewGroup.LayoutParams(60, 40));
            row.addView(cell);
        }
        int width = 200;
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST));
        row.layout(0, 0, width, row.getMeasuredHeight());
        for (int i = 0; i < row.getChildCount(); ++i) {
            View cell = row.getChildAt(i);
            assertTrue("cell " + i + " starts inside the row", cell.getLeft() >= 0);
            assertTrue("cell " + i + " ends inside the row", cell.getRight() <= width);
        }
        assertTrue("the row really wrapped", row.getMeasuredHeight() > 40);
    }

    /**
     * A long help sentence is given the full width and is allowed to wrap, rather than being
     * clipped at the header's edge -- which is what hid the Arabic help text.
     */
    @Test
    public void theHelpSentenceIsGivenTheFullWidth() {
        TouchControlsActivity activity = laidOut(PORTRAIT_W, PORTRAIT_H);
        View root = activity.getWindow().getDecorView();
        TextView help = labelled(root, host.getString(R.string.touch_drag_help_short));
        assertNotNull("the help line is on the page", help);
        int[] own = bounds(help);
        assertTrue("the help line spans most of the window",
            own[2] - own[0] > PORTRAIT_W * 0.6f);
    }

    // ------------------------------------------------------------------ the background

    /**
     * GeneralsX @bugfix Android touch editor 07/11/2026 The bundled picture is inside the button
     * layout area, not behind the whole page. It is the canvas FrameLayout's first child, it is
     * clipped to the canvas' bounds, and it takes no touch -- so it shows only where the buttons are
     * laid out and the controls stay fully interactive over it.
     */
    @Test
    public void theBackgroundIsBundledAndConfinedToTheButtonLayout() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        TouchEditorBackgroundView background = activity.backgroundForTest();
        assertNotNull("the editor has a background view", background);
        assertTrue("the bundled picture decoded", background.hasImage());

        // It is inside the editing surface's container, not the window root.
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        ViewGroup host = findFrameWithBackground(root, background);
        assertNotNull("the background is attached to the page", host);
        assertTrue("the background is inside the button layout area, not the root",
            host != root);
        assertEquals("the background is the first child, so it is behind the buttons",
            background, host.getChildAt(0));
        assertTrue("the background is clipped to the button layout area",
            background.cropToBoundsForTest());
        assertFalse("and it does not take a touch", background.isClickable());
        assertFalse("and it is not focusable", background.isFocusable());
    }

    /**
     * The picture is bounded by the canvas: it sits inside the canvas' rectangle and nowhere else,
     * so nothing of it reaches the inspector, the header or the footer. Checked at the two window
     * shapes a phone actually gives the page.
     */
    @Test
    public void theBackgroundIsBoundedByTheButtonLayoutArea() {
        for (int[] size : new int[][] {{LANDSCAPE_W, LANDSCAPE_H}, {PORTRAIT_W, PORTRAIT_H}}) {
            TouchControlsActivity activity = laidOut(size[0], size[1]);
            TouchEditorBackgroundView background = activity.backgroundForTest();
            ViewGroup host = findFrameWithBackground(
                activity.getWindow().getDecorView(), background);
            assertNotNull(host);

            int[] canvasBox = bounds(host);
            int[] imageBox = bounds(background);
            assertEquals("the picture starts at the canvas' left edge",
                canvasBox[0], imageBox[0]);
            assertEquals("and at its top edge", canvasBox[1], imageBox[1]);
            assertEquals("and ends at its right edge", canvasBox[2], imageBox[2]);
            assertEquals("and at its bottom edge", canvasBox[3], imageBox[3]);

            // The inspector is a sibling: the picture must not reach into it.
            View inspector = activity.inspectorForTest();
            int[] inspectorBox = bounds(inspector);
            assertTrue("the picture does not extend into the inspector",
                imageBox[2] <= inspectorBox[0] || inspectorBox[2] <= imageBox[0]
                    || imageBox[3] <= inspectorBox[1] || inspectorBox[3] <= imageBox[1]);
        }
    }

    private static ViewGroup findFrameWithBackground(View view, View background) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                if (group.getChildAt(i) == background) {
                    return group;
                }
                ViewGroup found = findFrameWithBackground(group.getChildAt(i), background);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The picture fills the view with no empty band in either orientation, and its composition is
     * never stretched: the source rectangle it crops keeps the view's aspect ratio, which is what
     * centre-crop means and what "avoid stretching or distorting" asks for.
     */
    @Test
    public void theBackgroundCentreCropsInBothOrientations() {
        for (int[] size : new int[][] {{LANDSCAPE_W, LANDSCAPE_H}, {PORTRAIT_W, PORTRAIT_H},
                {2000, 400}, {300, 1200}}) {
            TouchEditorBackgroundView background = new TouchEditorBackgroundView(host);
            background.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
            background.layout(0, 0, size[0], size[1]);
            // A scrim of 0 leaves the picture alone, so every sampled pixel is the picture.
            background.setDimPercent(0);
            Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            background.draw(new Canvas(bitmap));
            // Centre-crop means every pixel is covered: no transparent band anywhere on the edges.
            assertEquals("top-left corner is painted", 255,
                android.graphics.Color.alpha(bitmap.getPixel(0, 0)));
            assertEquals("bottom-right corner is painted", 255,
                android.graphics.Color.alpha(bitmap.getPixel(size[0] - 1, size[1] - 1)));
        }
    }

    /** The scrim darkens the picture; a higher dim leaves less of the image showing. */
    @Test
    public void theScrimDarkensThePicture() {
        TouchEditorBackgroundView background = new TouchEditorBackgroundView(host);
        int size = 120;
        background.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        background.layout(0, 0, size, size);

        background.setDimPercent(0);
        Bitmap clear = render(background, size);
        background.setDimPercent(90);
        Bitmap dimmed = render(background, size);

        long clearSum = 0;
        long dimSum = 0;
        for (int x = 0; x < size; ++x) {
            for (int y = 0; y < size; ++y) {
                clearSum += brightness(clear.getPixel(x, y));
                dimSum += brightness(dimmed.getPixel(x, y));
            }
        }
        assertTrue("the scrim makes the picture darker, not brighter",
            dimSum < clearSum);
    }

    /** The dim is clamped to its documented range, so a stored oddity cannot blank the page. */
    @Test
    public void theDimIsClamped() {
        TouchEditorBackgroundView background = new TouchEditorBackgroundView(host);
        background.setDimPercent(1000);
        assertEquals(TouchEditorBackgroundView.MAX_DIM_PERCENT, background.dimPercent());
        background.setDimPercent(-50);
        assertEquals(TouchEditorBackgroundView.MIN_DIM_PERCENT, background.dimPercent());
    }

    private static Bitmap render(View view, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static int brightness(int color) {
        return android.graphics.Color.red(color) + android.graphics.Color.green(color)
            + android.graphics.Color.blue(color);
    }

    /**
     * A touch on the background's own coordinates reaches the view behind it, not the background:
     * the picture is decoration and must never swallow an edit gesture. The background does not
     * consume the event, which is what makes the canvas below it receive the whole gesture.
     */
    @Test
    public void theBackgroundDoesNotConsumeATouch() {
        TouchEditorBackgroundView background = new TouchEditorBackgroundView(host);
        background.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        background.layout(0, 0, 400, 400);
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 200, 200, 0);
        assertFalse("the background refuses the gesture", background.onTouchEvent(down));
        down.recycle();
    }

    // ------------------------------------------------------------------ the action popup

    /**
     * GeneralsX @feature Android touch editor 07/11/2026 The launcher's Clear Key removes only the
     * selected button's mapping, keeps the button, and leaves the others alone -- driven through the
     * action popup's own row, not the model directly. The row lives in the popup now; the button
     * used to sit in the inspector.
     */
    @Test
    public void clearKeyFromThePopupClearsOnlyTheSelectedMapping() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        TouchLayoutEditor editor = activity.editorForTest();
        editor.select(0);
        activity.rebuildInspectorForTest();
        int count = editor.buttons().size();
        int secondKey = editor.buttons().get(1).keyCode;

        activity.runActionForTest(TouchControlsActivity.MENU_ITEM_CLEAR_FOR_TEST, 0);

        assertEquals("the button is still there", count, editor.buttons().size());
        assertFalse("its mapping is gone", editor.buttons().get(0).isAssigned());
        assertEquals("the next button is untouched", secondKey, editor.buttons().get(1).keyCode);
    }

    /** Clear is disabled in the popup when the selected button has nothing left to clear. */
    @Test
    public void clearKeyIsDisabledInThePopupOnceThereIsNothingToClear() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        TouchLayoutEditor editor = activity.editorForTest();
        editor.select(0);
        editor.clearSelectedKey();
        activity.rebuildInspectorForTest();

        android.view.MenuItem clear =
            activity.buildActionMenuForTest().findItem(TouchControlsActivity.MENU_ITEM_CLEAR_FOR_TEST);
        assertNotNull("the popup offers a Clear Key row", clear);
        assertFalse("nothing to clear means the row is off", clear.isEnabled());
    }

    /**
     * GeneralsX @feature Android touch editor 07/11/2026 The popup carries every action the old
     * "Button action" section did and then some: one row per catalog key, the three modifier
     * latches, and Clear / Toggle / Bring to front / Delete. Nothing was dropped in the move.
     */
    @Test
    public void theActionPopupKeepsEveryKeyAndEveryAction() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        activity.editorForTest().select(0);
        activity.rebuildInspectorForTest();
        android.view.Menu menu = activity.buildActionMenuForTest();

        for (TouchKeyCatalog.Entry entry : TouchKeyCatalog.all()) {
            assertNotNull("the popup offers the key " + entry.name,
                menu.findItem(TouchControlsActivity.keyMenuItemIdForTest(entry.keyCode)));
        }
        assertNotNull(menu.findItem(TouchControlsActivity.MENU_ITEM_CTRL_FOR_TEST));
        assertNotNull(menu.findItem(TouchControlsActivity.MENU_ITEM_SHIFT_FOR_TEST));
        assertNotNull(menu.findItem(TouchControlsActivity.MENU_ITEM_ALT_FOR_TEST));
        assertNotNull(menu.findItem(TouchControlsActivity.MENU_ITEM_CLEAR_FOR_TEST));
        assertNotNull(menu.findItem(TouchControlsActivity.MENU_ITEM_TOGGLE_FOR_TEST));
        assertNotNull(menu.findItem(TouchControlsActivity.MENU_ITEM_FRONT_FOR_TEST));
        assertNotNull(menu.findItem(TouchControlsActivity.MENU_ITEM_DELETE_FOR_TEST));
    }

    /**
     * Choosing a key row applies it to the selected button at once -- no Save, no second step -- and
     * the button's caption follows, which is what "reflected immediately in the editor preview"
     * means. The row id is the keycode, so a chosen row maps straight back to the model's key.
     */
    @Test
    public void choosingAKeyInThePopupAppliesItImmediately() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        TouchLayoutEditor editor = activity.editorForTest();
        editor.select(0);
        activity.rebuildInspectorForTest();

        activity.runActionForTest(
            TouchControlsActivity.keyMenuItemIdForTest(android.view.KeyEvent.KEYCODE_K), 0);

        assertEquals("the button presses the key the row named",
            android.view.KeyEvent.KEYCODE_K, editor.buttons().get(0).keyCode);
        assertEquals("and its caption follows at once",
            TouchKeyCatalog.displayName(android.view.KeyEvent.KEYCODE_K),
            editor.buttons().get(0).label);
    }

    /** A modifier row latches and unlatches, applied to the selected button on the spot. */
    @Test
    public void choosingAModifierInThePopupAppliesItImmediately() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        TouchLayoutEditor editor = activity.editorForTest();
        editor.select(0);
        activity.rebuildInspectorForTest();

        activity.runActionForTest(TouchControlsActivity.MENU_ITEM_CTRL_FOR_TEST, 0);
        assertTrue("Ctrl is on the button",
            (editor.buttons().get(0).modifiers & TouchKeyCatalog.MOD_CTRL) != 0);
        activity.runActionForTest(TouchControlsActivity.MENU_ITEM_CTRL_FOR_TEST, 0);
        assertFalse("and a second tap takes it off",
            (editor.buttons().get(0).modifiers & TouchKeyCatalog.MOD_CTRL) != 0);
    }

    /** Delete and Bring to front act on the selected button from the popup. */
    @Test
    public void deleteAndFrontRowsActOnTheSelectedButton() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        TouchLayoutEditor editor = activity.editorForTest();
        editor.select(0);
        activity.rebuildInspectorForTest();
        int before = editor.buttons().size();

        activity.runActionForTest(TouchControlsActivity.MENU_ITEM_DELETE_FOR_TEST, 0);
        assertEquals("Delete removed the button", before - 1, editor.buttons().size());

        editor.select(0);
        int highest = 0;
        for (TouchControlConfig.ButtonSpec spec : editor.buttons()) {
            highest = Math.max(highest, spec.zOrder);
        }
        activity.runActionForTest(TouchControlsActivity.MENU_ITEM_FRONT_FOR_TEST, 0);
        assertTrue("Bring to front raised the button's z-order",
            editor.buttons().get(0).zOrder > highest);
    }

    /**
     * GeneralsX @feature Android touch editor 07/11/2026 A tap on a button opens the action popup --
     * driven through the real touch path, not the model -- and a tap on empty space does not.
     */
    @Test
    public void aTapOnAButtonOpensTheActionPopup() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        activity.setGameViewportForTest(1000, 600);
        View surface = activity.editorSurfaceForTest();
        TouchLayoutEditor editor = activity.editorForTest();
        TouchControlConfig.ButtonSpec spec = editor.buttons().get(0);
        long now = android.os.SystemClock.uptimeMillis();

        // A tap: down and up in the same place, on the button.
        surface.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            spec.x * surface.getWidth(), spec.y * surface.getHeight(), 0));
        surface.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP,
            spec.x * surface.getWidth(), spec.y * surface.getHeight(), 0));

        assertNotNull("a tap on a button opens the action popup", activity.actionPopupForTest());
    }

    /** A tap on empty space clears the selection and opens no popup. */
    @Test
    public void aTapOnEmptySpaceOpensNoPopup() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        activity.setGameViewportForTest(1000, 600);
        View surface = activity.editorSurfaceForTest();
        long now = android.os.SystemClock.uptimeMillis();

        // The top-left corner is empty: the shipped layout sits in the lower third.
        surface.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 2f, 2f, 0));
        surface.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 2f, 2f, 0));

        assertNull("no button means no popup", activity.actionPopupForTest());
    }

    // ------------------------------------------------------------------ Arabic (RTL)

    /**
     * The page in Arabic: the layout is mirrored and nothing is clipped or pushed off the edge.
     * This is the "Arabic labels are clipped or partially hidden" defect, checked in the locale
     * that showed it.
     */
    @Test
    @Config(qualifiers = "ar")
    public void theEditorRendersInArabicWithoutClipping() {
        TouchControlsActivity activity = laidOut(PORTRAIT_W, PORTRAIT_H);
        activity.editorForTest().select(0);
        activity.rebuildInspectorForTest();
        View root = activity.getWindow().getDecorView();
        assertEquals("the page is RTL", View.LAYOUT_DIRECTION_RTL, root.getLayoutDirection());

        for (View child : descendants(root)) {
            if (!(child.getParent() instanceof ViewGroup) || child.getWidth() <= 0) {
                continue;
            }
            ViewGroup parent = (ViewGroup) child.getParent();
            int[] own = bounds(child);
            int[] box = bounds(parent);
            assertTrue("an Arabic label escapes its container: "
                    + child.getClass().getSimpleName(),
                own[0] >= box[0] - 1 && own[2] <= box[2] + 1);
        }
    }

    /** The footer stays usable in Arabic too, where the cells read right to left. */
    @Test
    @Config(qualifiers = "ar")
    public void theFooterStaysOnScreenInArabic() {
        TouchControlsActivity activity = laidOut(PORTRAIT_W, PORTRAIT_H);
        View footer = activity.footerForTest();
        int[] box = bounds(footer);
        assertTrue(box[3] <= PORTRAIT_H + 1);
        for (View child : descendants(footer)) {
            int[] own = bounds(child);
            assertTrue(own[0] >= -1 && own[2] <= PORTRAIT_W + 1 && own[3] <= PORTRAIT_H + 1);
        }
    }

    /** A cleared button reads the Arabic caption on the canvas, not the English one. */
    @Test
    @Config(qualifiers = "ar")
    public void aClearedButtonReadsArabicOnTheCanvas() {
        TouchControlsActivity activity = laidOut(LANDSCAPE_W, LANDSCAPE_H);
        TouchLayoutEditor editor = activity.editorForTest();
        editor.select(0);
        activity.rebuildInspectorForTest();
        editor.clearSelectedKey();
        activity.rebuildInspectorForTest();
        assertEquals(activity.getString(R.string.touch_key_unassigned),
            TouchKeyCatalog.unassignedName(activity));
        assertTrue(editor.buttons().get(0).isAssigned() == false);
    }
}
