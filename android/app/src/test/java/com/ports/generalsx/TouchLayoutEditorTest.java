// GeneralsX @build Android touch editor 05/11/2026
//
// The edit session's arithmetic, without a screen. The properties here are the ones a finger
// tests on a device: a drag does not drift, a coordinate can never be stored non-finite or
// outside the screen fraction, a resize keeps the opposite corner put, and Cancel leaves the
// stored layout exactly as it was.
//
// Placement itself is free by design, so the tests below assert the OPPOSITE of a layout policy:
// a button reaches every corner exactly, two buttons may share a point, and a button may hang off
// the edge -- and in every case nothing is nudged back into a "safe" area.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TouchLayoutEditorTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 600;
    private static final float DENSITY = 2f;

    private Context context;
    private TouchControlConfig stored;
    private TouchLayoutEditor editor;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        TouchControlConfig.reset(context);
        stored = TouchControlConfig.defaults();
        stored.save(context);
        editor = new TouchLayoutEditor(TouchControlConfig.load(context));
        editor.applyViewportTo(WIDTH, HEIGHT, DENSITY);
    }

    @Test
    public void selectAtFindsTheTopmostButton() {
        // Two buttons on the same spot: the one drawn last wins the tap.
        editor.addButton(KeyEvent.KEYCODE_A, 0);
        editor.setSelectedKey(KeyEvent.KEYCODE_Z);
        TouchControlConfig.ButtonSpec added = editor.selectedButton();
        added.x = 0.5f;
        added.y = 0.5f;
        added.zOrder = 10;

        int hit = editor.hitTest(WIDTH * 0.5f, HEIGHT * 0.5f);
        assertEquals(editor.buttons().size() - 1, hit);
    }

    @Test
    public void aTapOutsideEveryButtonSelectsNothing() {
        assertFalse(editor.selectAt(5f, 5f));
        assertEquals(-1, editor.selectedIndex());
    }

    /** The core promise of absolute dragging: repeated moves do not accumulate an error. */
    @Test
    public void dragDoesNotDriftOverManySteps() {
        editor.select(0);
        float targetX = 600f;
        float targetY = 400f;
        editor.beginDrag(editor.centerX(editor.selectedButton()),
            editor.centerY(editor.selectedButton()));
        for (int i = 0; i < 500; ++i) {
            editor.dragTo(targetX, targetY);
        }
        assertEquals(targetX, editor.centerX(editor.selectedButton()), 0.5f);
        assertEquals(targetY, editor.centerY(editor.selectedButton()), 0.5f);
    }

    /** The grabbed point stays under the finger, which is what makes a drag feel direct. */
    @Test
    public void dragKeepsTheGrabOffset() {
        editor.select(0);
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        float grabX = editor.centerX(spec) + 10f;
        float grabY = editor.centerY(spec) - 8f;
        editor.beginDrag(grabX, grabY);
        editor.dragTo(700f, 300f);
        assertEquals("the same offset is kept", 700f - 10f, editor.centerX(spec), 0.5f);
        assertEquals(300f + 8f, editor.centerY(spec), 0.5f);
    }

    /** A drag past the edge clamps the stored fraction, and the button is still usable there. */
    @Test
    public void dragPastTheEdgeClampsAndKeepsTheButtonUsable() {
        editor.select(0);
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        editor.beginDrag(editor.centerX(spec), editor.centerY(spec));
        editor.dragTo(-5000f, -5000f);
        assertTrue(spec.x >= TouchControlConfig.MIN_X);
        assertTrue(spec.y >= TouchControlConfig.MIN_Y);
        editor.dragTo(9000f, 9000f);
        assertTrue(spec.x <= TouchControlConfig.MAX_X);
        assertTrue(spec.y <= TouchControlConfig.MAX_Y);
        // Still selectable where it now is.
        assertTrue(editor.hitTest(editor.centerX(spec), editor.centerY(spec)) >= 0);
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 A button reaches each corner exactly.
     * The four corners are the case a safe-area inset used to forbid, so they are asserted one by
     * one: the stored fraction is the corner itself, 0 or 1, and the button's centre lands on the
     * screen's own corner.
     */
    @Test
    public void aButtonReachesEveryScreenCorner() {
        float[][] corners = {{0f, 0f}, {1f, 0f}, {0f, 1f}, {1f, 1f}};
        for (float[] corner : corners) {
            editor.select(0);
            TouchControlConfig.ButtonSpec spec = editor.selectedButton();
            editor.beginDrag(editor.centerX(spec), editor.centerY(spec));
            editor.dragTo(corner[0] * WIDTH, corner[1] * HEIGHT);
            assertEquals("x at the corner", corner[0], spec.x, 0.0001f);
            assertEquals("y at the corner", corner[1], spec.y, 0.0001f);
            assertEquals("centre x lands on the screen corner",
                corner[0] * WIDTH, editor.centerX(spec), 0.5f);
            assertEquals("centre y lands on the screen corner",
                corner[1] * HEIGHT, editor.centerY(spec), 0.5f);
        }
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Two buttons may occupy the same point.
     * Nothing moves one away from the other, and both keep their own position when saved.
     */
    @Test
    public void twoButtonsMayShareOnePosition() {
        editor.select(0);
        editor.beginDrag(editor.centerX(editor.selectedButton()),
            editor.centerY(editor.selectedButton()));
        editor.dragTo(WIDTH * 0.3f, HEIGHT * 0.7f);
        editor.select(1);
        editor.beginDrag(editor.centerX(editor.selectedButton()),
            editor.centerY(editor.selectedButton()));
        editor.dragTo(WIDTH * 0.3f, HEIGHT * 0.7f);
        assertEquals("both at the same x", editor.buttons().get(0).x,
            editor.buttons().get(1).x, 0.0001f);
        assertEquals("both at the same y", editor.buttons().get(0).y,
            editor.buttons().get(1).y, 0.0001f);
        editor.save(context);
        TouchControlConfig reloaded = TouchControlConfig.load(context);
        assertEquals("and both survive a save", reloaded.buttons.get(0).x,
            reloaded.buttons.get(1).x, 0.0001f);
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 A button may be parked half past the
     * edge on purpose. The centre stays on screen (that is what makes it recoverable), the box is
     * allowed to hang outside, and nothing drags it back in.
     */
    @Test
    public void aButtonMayHangOffTheEdgeOnPurpose() {
        editor.select(0);
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        spec.setSize(200f, 200f);
        editor.beginDrag(editor.centerX(spec), editor.centerY(spec));
        editor.dragTo(0f, 0f);                       // centre on the very corner
        android.graphics.RectF rect = new android.graphics.RectF();
        editor.bounds(spec, rect);
        assertTrue("the box reaches outside the screen", rect.left < 0f && rect.top < 0f);
        assertEquals("and the stored position was not corrected back", 0f, spec.x, 0.0001f);
        // The centre is still findable, so the editor can pick the button up again.
        assertTrue(editor.hitTest(editor.centerX(spec), editor.centerY(spec)) >= 0);
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 A button placed in a corner survives a
     * save and reload at the same coordinate -- there is no migration step that re-homes it.
     */
    @Test
    public void aCornerPositionSurvivesSaveAndReload() {
        editor.select(0);
        editor.beginDrag(editor.centerX(editor.selectedButton()),
            editor.centerY(editor.selectedButton()));
        editor.dragTo(0f, HEIGHT);
        editor.save(context);
        TouchControlConfig reloaded = TouchControlConfig.load(context);
        assertEquals(0f, reloaded.buttons.get(0).x, 0.0001f);
        assertEquals(1f, reloaded.buttons.get(0).y, 0.0001f);
    }

    /** The corner opposite the handle does not move while the handle is dragged. */
    @Test
    public void resizeAnchorsTheOppositeCorner() {
        editor.select(0);
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        android.graphics.RectF before = new android.graphics.RectF();
        editor.bounds(spec, before);
        editor.beginResize(before.right, before.bottom);
        editor.resizeTo(before.right + 40f, before.bottom + 20f);
        android.graphics.RectF after = new android.graphics.RectF();
        editor.bounds(spec, after);
        assertEquals("the top-left corner stays put", before.left, after.left, 1f);
        assertEquals(before.top, after.top, 1f);
        assertTrue("and the box grew", after.width() > before.width());
    }

    @Test
    public void resizeIsClampedToTheModelLimits() {
        editor.select(0);
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        editor.beginResize(0f, 0f);
        editor.resizeTo(100000f, 100000f);
        assertEquals(TouchControlConfig.MAX_SIZE_DP, spec.widthDp, 0.001f);
        editor.beginResize(0f, 0f);
        editor.resizeTo(-100000f, -100000f);
        assertEquals(TouchControlConfig.MIN_SIZE_DP, spec.widthDp, 0.001f);
    }

    /** Changing the shape must not move the button or change its key. */
    @Test
    public void changingShapeKeepsKeyAndPosition() {
        editor.select(0);
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();
        int key = spec.keyCode;
        float x = spec.x;
        float y = spec.y;
        editor.setSelectedShape(TouchButtonShape.CIRCLE);
        assertEquals(key, spec.keyCode);
        assertEquals(x, spec.x, 0.0001f);
        assertEquals(y, spec.y, 0.0001f);
    }

    @Test
    public void shapeChangeDoesNotLoseTheModifiers() {
        editor.select(0);
        editor.toggleSelectedModifier(TouchKeyCatalog.MOD_CTRL);
        editor.setSelectedShape(TouchButtonShape.POLYGON);
        assertEquals(TouchKeyCatalog.MOD_CTRL, editor.selectedButton().modifiers);
    }

    /** Selecting a new key keeps a Ctrl+... combination the player already set. */
    @Test
    public void changingKeyKeepsModifiers() {
        editor.select(0);
        editor.setSelectedModifiers(TouchKeyCatalog.MOD_CTRL | TouchKeyCatalog.MOD_SHIFT);
        editor.setSelectedKey(KeyEvent.KEYCODE_G);
        assertEquals(KeyEvent.KEYCODE_G, editor.selectedButton().keyCode);
        assertEquals(TouchKeyCatalog.MOD_CTRL | TouchKeyCatalog.MOD_SHIFT,
            editor.selectedButton().modifiers);
    }

    /** A refused key is refused at the model too, not only in the picker. */
    @Test
    public void refusedKeyIsNotAssigned() {
        editor.select(0);
        int before = editor.selectedButton().keyCode;
        assertFalse(editor.setSelectedKey(KeyEvent.KEYCODE_HOME));
        assertEquals(before, editor.selectedButton().keyCode);
    }

    @Test
    public void deleteRemovesTheSelectedButton() {
        int before = editor.buttons().size();
        editor.select(0);
        editor.deleteSelected();
        assertEquals(before - 1, editor.buttons().size());
        assertEquals(-1, editor.selectedIndex());
    }

    // ---------------------------------------------------------------- Clear Key

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 Clearing removes the mapping and nothing
     * else: the button stays in the layout, in the same slot, with every visual property intact.
     * Delete and the profile reset are different operations and are asserted as such below.
     */
    @Test
    public void clearingTheKeyKeepsTheButtonAndItsAppearance() {
        editor.select(0);
        TouchControlConfig.ButtonSpec before = editor.selectedButton();
        float x = before.x;
        float y = before.y;
        float width = before.widthDp;
        float height = before.heightDp;
        int shape = before.shape;
        int polygon = before.polygonType;
        float opacity = before.opacity;
        float corner = before.cornerDp;
        float border = before.borderWidthDp;
        int fill = before.fillColor;
        String label = before.label;
        int count = editor.buttons().size();

        assertTrue(editor.clearSelectedKey());

        assertEquals("the button is still there", count, editor.buttons().size());
        assertFalse("its key is gone", before.isAssigned());
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, before.keyCode);
        assertEquals("the same slot is still selected", 0, editor.selectedIndex());
        assertEquals(x, before.x, 0.0001f);
        assertEquals(y, before.y, 0.0001f);
        assertEquals(width, before.widthDp, 0.001f);
        assertEquals(height, before.heightDp, 0.001f);
        assertEquals(shape, before.shape);
        assertEquals(polygon, before.polygonType);
        assertEquals(opacity, before.opacity, 0.001f);
        assertEquals(corner, before.cornerDp, 0.001f);
        assertEquals(border, before.borderWidthDp, 0.001f);
        assertEquals(fill, before.fillColor);
        assertEquals("the label is left alone, not replaced by a placeholder", label, before.label);
    }

    /** Clearing also drops the modifiers: a modifier with no primary key would still press Ctrl. */
    @Test
    public void clearingTheKeyAlsoDropsTheModifiers() {
        editor.select(0);
        editor.setSelectedModifiers(TouchKeyCatalog.MOD_CTRL | TouchKeyCatalog.MOD_SHIFT);
        assertTrue(editor.clearSelectedKey());
        assertEquals(0, editor.selectedButton().modifiers);
    }

    /** A cleared button can be given a new key later, through the same setter as any other. */
    @Test
    public void aClearedButtonCanBeReassigned() {
        editor.select(0);
        editor.clearSelectedKey();
        assertTrue(editor.setSelectedKey(KeyEvent.KEYCODE_Q));
        assertEquals(KeyEvent.KEYCODE_Q, editor.selectedButton().keyCode);
    }

    /**
     * GeneralsX @feature Android touch editor 07/11/2026 The caption follows the key when the
     * caption was the key's own name: choosing a new action in the popup must make the button read
     * the new key at once, which is what "reflected immediately in the editor preview" means.
     */
    @Test
    public void changingTheKeyUpdatesTheDefaultCaption() {
        editor.select(0);
        assertEquals("E", editor.selectedButton().label);
        editor.setSelectedKey(KeyEvent.KEYCODE_K);
        assertEquals("K", editor.selectedButton().label);
    }

    /** A caption the player customised is a deliberate choice and survives a key change. */
    @Test
    public void changingTheKeyKeepsACustomCaption() {
        editor.select(0);
        editor.setSelectedLabel("Mine");
        editor.setSelectedKey(KeyEvent.KEYCODE_K);
        assertEquals("Mine", editor.selectedButton().label);
    }

    /** Reassigning a cleared button gives it the new key's caption, not the stale old one. */
    @Test
    public void reassigningAClearedButtonUsesTheNewKeyCaption() {
        editor.select(0);
        editor.clearSelectedKey();
        editor.setSelectedKey(KeyEvent.KEYCODE_K);
        assertEquals("K", editor.selectedButton().label);
    }

    /** A second Clear changes nothing, so it does not mark an unedited session dirty. */
    @Test
    public void clearingTwiceIsNotAChange() {
        editor.select(0);
        assertTrue(editor.clearSelectedKey());
        assertFalse(editor.clearSelectedKey());
    }

    /** Clear with nothing selected is refused rather than clearing an arbitrary button. */
    @Test
    public void clearingWithNoSelectionIsRefused() {
        editor.clearSelection();
        assertFalse(editor.clearSelectedKey());
    }

    /** Clearing is not deleting: the other buttons keep their mappings, and this one keeps its
     *  position. */
    @Test
    public void clearingDoesNotTouchOtherButtons() {
        editor.select(0);
        editor.clearSelectedKey();
        assertTrue(editor.buttons().size() > 1);
        for (int i = 1; i < editor.buttons().size(); ++i) {
            assertTrue("button " + i + " is untouched",
                editor.buttons().get(i).isAssigned());
        }
    }

    /** A cleared mapping is persisted and reloaded as cleared, not silently re-defaulted. */
    @Test
    public void aClearedMappingSurvivesSaveAndReload() {
        editor.select(0);
        float x = editor.selectedButton().x;
        editor.clearSelectedKey();
        assertTrue(editor.save(context));
        TouchControlConfig reloaded = TouchControlConfig.load(context);
        TouchControlConfig.ButtonSpec back = reloaded.buttons.get(0);
        assertFalse("the cleared state survived the round trip", back.isAssigned());
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, back.keyCode);
        assertEquals("and its position came with it", x, back.x, 0.0001f);
        assertEquals("and it was not dropped from the layout",
            editor.buttons().size(), reloaded.buttons.size());
    }

    /** Delete is still Delete: it removes the button, which Clear never does. */
    @Test
    public void clearAndDeleteRemainDifferentOperations() {
        int before = editor.buttons().size();
        editor.select(0);
        editor.clearSelectedKey();
        assertEquals("Clear keeps the button", before, editor.buttons().size());
        editor.select(0);
        editor.deleteSelected();
        assertEquals("Delete removes it", before - 1, editor.buttons().size());
    }

    @Test
    public void addIsRefusedAtTheMaximum() {
        while (editor.buttons().size() < TouchControlConfig.MAX_BUTTONS) {
            assertTrue(editor.addButton(KeyEvent.KEYCODE_A, 0));
        }
        assertFalse(editor.addButton(KeyEvent.KEYCODE_A, 0));
        assertEquals(TouchControlConfig.MAX_BUTTONS, editor.buttons().size());
    }

    /** Save writes; a second Save with nothing changed writes nothing. */
    @Test
    public void saveWritesOnlyWhenSomethingChanged() {
        editor.select(0);
        editor.setSelectedKey(KeyEvent.KEYCODE_Z);
        assertTrue(editor.save(context));
        assertFalse("nothing changed the second time", editor.save(context));
        assertEquals(KeyEvent.KEYCODE_Z, TouchControlConfig.load(context).buttons.get(0).keyCode);
    }

    /** Cancel throws the edits away and leaves the stored layout exactly as it was. */
    @Test
    public void cancelLeavesTheStoredLayoutUntouched() {
        int originalKey = TouchControlConfig.load(context).buttons.get(0).keyCode;
        editor.select(0);
        editor.setSelectedKey(KeyEvent.KEYCODE_Z);
        editor.setSelectedShape(TouchButtonShape.POLYGON);
        editor.cancel(context);
        assertEquals(originalKey, TouchControlConfig.load(context).buttons.get(0).keyCode);
        assertFalse(editor.isDirty());
    }

    @Test
    public void restoreDefaultsReplacesTheWorkingCopy() {
        editor.select(0);
        editor.deleteSelected();
        assertTrue(editor.isDirty());
        editor.restoreDefaults();
        assertEquals(TouchControlConfig.defaults().buttons.size(), editor.buttons().size());
        // Restoring the shipped layout from the shipped layout is not a change: the stored
        // configuration already IS the default, so the session is clean again.
        assertFalse(editor.isDirty());
    }

    /** A scale change keeps every stored coordinate finite and inside the screen fraction. */
    @Test
    public void scaleChangeKeepsEveryButtonOnScreen() {
        editor.setScale(TouchControlConfig.MAX_SCALE);
        for (TouchControlConfig.ButtonSpec spec : editor.buttons()) {
            assertTrue(spec.x >= TouchControlConfig.MIN_X && spec.x <= TouchControlConfig.MAX_X);
            assertTrue(spec.y >= TouchControlConfig.MIN_Y && spec.y <= TouchControlConfig.MAX_Y);
        }
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The ceiling is exactly 40: 39 buttons can
     * be added, the 40th is accepted, the 41st is refused, and all 40 survive a save and reload
     * with their positions intact -- nothing is silently discarded on load.
     */
    @Test
    public void fortyButtonsFitAndTheFortyFirstIsRefused() {
        assertEquals(40, TouchControlConfig.MAX_BUTTONS);
        // The shipped layout starts with 10, so this lands exactly on 39 and then 40.
        while (editor.buttons().size() < 39) {
            assertTrue(editor.addButton(KeyEvent.KEYCODE_A, 0));
        }
        assertEquals(39, editor.buttons().size());
        assertTrue("the 40th button is accepted", editor.addButton(KeyEvent.KEYCODE_A, 0));
        assertEquals(40, editor.buttons().size());
        assertFalse("the 41st button is refused", editor.addButton(KeyEvent.KEYCODE_A, 0));
        assertEquals("and nothing was removed to make room", 40, editor.buttons().size());

        // Give the last one a corner so the reload has something distinctive to check.
        editor.select(39);
        editor.beginDrag(editor.centerX(editor.selectedButton()),
            editor.centerY(editor.selectedButton()));
        editor.dragTo(0f, 0f);
        assertTrue(editor.save(context));

        TouchControlConfig reloaded = TouchControlConfig.load(context);
        assertEquals("every button survives the reload", 40, reloaded.buttons.size());
        assertEquals("and the corner one kept its position",
            0f, reloaded.buttons.get(39).x, 0.0001f);
    }

    /** Import replaces the working copy; nothing is stored until save(). */
    @Test
    public void replaceWithTakesTheImportedLayout() throws Exception {
        TouchControlConfig imported = TouchControlConfig.fromJsonString(
            "{\"format\":\"GeneralsZHTouchControls\",\"version\":3,\"buttonScale\":1.25,"
            + "\"buttons\":[{\"label\":\"G\",\"key\":" + KeyEvent.KEYCODE_G + ",\"x\":0.4,\"y\":0.4,"
            + "\"widthDp\":60,\"heightDp\":60,\"shape\":3}]}");
        editor.replaceWith(imported);
        assertEquals(1, editor.buttons().size());
        assertEquals(KeyEvent.KEYCODE_G, editor.buttons().get(0).keyCode);
        assertEquals("still not written", TouchControlConfig.defaults().buttons.size(),
            TouchControlConfig.load(context).buttons.size());
    }

    @Test
    public void isDirtyIsFalseForAnUneditedSession() {
        assertFalse(new TouchLayoutEditor(TouchControlConfig.load(context)).isDirty());
    }
}
