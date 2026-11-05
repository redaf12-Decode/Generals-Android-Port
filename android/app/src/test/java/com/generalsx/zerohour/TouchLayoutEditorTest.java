// GeneralsX @build Android touch editor 05/11/2026
//
// The edit session's arithmetic, without a screen. The properties here are the ones a finger
// tests on a device: a drag does not drift, a button cannot leave the screen, a resize keeps the
// opposite corner put, and Cancel leaves the stored layout exactly as it was.

package com.generalsx.zerohour;

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
        editor.addButton();
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

    /** A drag past the edge clamps the button, and it is still on the screen afterwards. */
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

    @Test
    public void addIsRefusedAtTheMaximum() {
        while (editor.buttons().size() < TouchControlConfig.MAX_BUTTONS) {
            assertTrue(editor.addButton());
        }
        assertFalse(editor.addButton());
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

    /** A scale change re-clamps every button, so none is pushed off the screen. */
    @Test
    public void scaleChangeKeepsEveryButtonOnScreen() {
        editor.setScale(TouchControlConfig.MAX_SCALE);
        for (TouchControlConfig.ButtonSpec spec : editor.buttons()) {
            assertTrue(spec.x >= TouchControlConfig.MIN_X && spec.x <= TouchControlConfig.MAX_X);
            assertTrue(spec.y >= TouchControlConfig.MIN_Y && spec.y <= TouchControlConfig.MAX_Y);
        }
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
