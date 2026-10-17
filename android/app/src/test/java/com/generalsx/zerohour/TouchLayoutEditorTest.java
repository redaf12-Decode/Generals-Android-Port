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

// GeneralsX @feature Android floating toolbox 17/10/2026 The editor's model is where the promises
// are kept or broken: that moving or resizing a button never changes which key it presses, that
// nothing leaves the screen or exceeds the model's bounds, that nothing is stored until Save, and
// that a layout written before buttons could be switched off still loads with all of them on.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class TouchLayoutEditorTest {

    private static final float DENSITY = 3f;
    private static final float WIDTH = 1080f;
    private static final float HEIGHT = 2340f;

    private Context ctx;
    private TouchLayoutEditor editor;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        TouchControlConfig layout = new TouchControlConfig();
        layout.buttons.add(new TouchControlConfig.ButtonSpec(
            "E", KeyEvent.KEYCODE_E, 0, 0.2f, 0.5f));
        layout.buttons.add(new TouchControlConfig.ButtonSpec(
            "Q", KeyEvent.KEYCODE_Q, TouchControlConfig.MOD_SHIFT, 0.5f, 0.5f));
        layout.save(ctx);

        editor = new TouchLayoutEditor(TouchControlConfig.load(ctx));
        editor.setViewport(WIDTH, HEIGHT, DENSITY);
    }

    private TouchControlConfig stored() {
        return TouchControlConfig.load(ctx);
    }

    @Test
    public void aFreshEditorMatchesWhatIsStored() {
        assertEquals(2, editor.buttonCount());
        assertEquals(-1, editor.selectedIndex());
        assertNull(editor.selectedButton());
        assertFalse("nothing has been edited yet", editor.isDirty());
    }

    @Test
    public void hitTestingFindsTheTopmostButtonAndMissesEmptySpace() {
        RectF second = editor.rectOf(1);
        assertEquals(1, editor.hitTest(second.centerX(), second.centerY()));
        assertEquals(-1, editor.hitTest(5f, 5f));
    }

    @Test
    public void movingAButtonNeverChangesItsAction() {
        editor.select(1);
        editor.moveSelectedBy(-500f, -500f);

        TouchControlConfig.ButtonSpec moved = editor.selectedButton();
        assertEquals(KeyEvent.KEYCODE_Q, moved.keyCode);
        assertEquals(TouchControlConfig.MOD_SHIFT, moved.modifiers);
        assertEquals("E", editor.working.buttons.get(0).label);
    }

    @Test
    public void movingClampsToTheMarginsTheModelUses() {
        editor.select(1);
        editor.moveSelectedBy(-100000f, -100000f);
        assertEquals(TouchLayoutEditor.MIN_X, editor.selectedButton().x, 0.0001f);
        assertEquals(TouchLayoutEditor.MIN_Y, editor.selectedButton().y, 0.0001f);

        editor.moveSelectedBy(100000f, 100000f);
        assertEquals(TouchLayoutEditor.MAX_X, editor.selectedButton().x, 0.0001f);
        assertEquals(TouchLayoutEditor.MAX_Y, editor.selectedButton().y, 0.0001f);
    }

    @Test
    public void resizingClampsToTheModelsBounds() {
        editor.select(1);
        editor.resizeSelectedBy(100000f, 100000f);
        assertEquals(TouchLayoutEditor.MAX_SIZE_DP, editor.selectedButton().widthDp, 0.001f);
        assertEquals(TouchLayoutEditor.MAX_SIZE_DP, editor.selectedButton().heightDp, 0.001f);

        editor.resizeSelectedBy(-100000f, -100000f);
        assertEquals(TouchLayoutEditor.MIN_SIZE_DP, editor.selectedButton().widthDp, 0.001f);
        assertEquals(TouchLayoutEditor.MIN_SIZE_DP, editor.selectedButton().heightDp, 0.001f);
    }

    @Test
    public void aSquareButtonStaysSquareWhenItIsResized() {
        TouchControlConfig layout = stored();
        layout.buttons.add(new TouchControlConfig.ButtonSpec(
            "S", KeyEvent.KEYCODE_S, 0, 0.8f, 0.5f));
        layout.buttons.get(2).shape = TouchControlConfig.SHAPE_SQUARE;
        layout.save(ctx);
        editor = new TouchLayoutEditor(stored());
        editor.setViewport(WIDTH, HEIGHT, DENSITY);

        editor.select(2);
        editor.resizeSelectedBy(200f, -200f);
        assertEquals(editor.selectedButton().widthDp, editor.selectedButton().heightDp, 0.001f);
    }

    @Test
    public void theResizeHandleIsOnTheSelectedButtonsCorner() {
        editor.select(1);
        RectF rect = editor.rectOf(1);
        assertTrue("the corner of the selected button must resize",
            editor.isOnResizeHandle(rect.right - 2f, rect.bottom - 2f));
        assertFalse("the middle of the button must move it, not resize it",
            editor.isOnResizeHandle(rect.centerX(), rect.centerY()));
    }

    @Test
    public void addingSelectsTheNewButtonAndStopsAtTheMaximum() {
        assertTrue(editor.addButton());
        assertEquals(3, editor.buttonCount());
        assertEquals(2, editor.selectedIndex());

        while (editor.addButton()) {
            // Fill the layout up.
        }
        assertEquals("the model's own maximum must be respected",
            TouchControlConfig.MAX_BUTTONS, editor.buttonCount());
    }

    @Test
    public void deletingNeedsASelectionAndRemovesOnlyThatOne() {
        assertFalse("nothing is selected yet", editor.deleteSelected());
        editor.select(0);
        assertTrue(editor.deleteSelected());
        assertEquals(1, editor.buttonCount());
        assertEquals("Q", editor.working.buttons.get(0).label);
        assertEquals(-1, editor.selectedIndex());
    }

    @Test
    public void switchingAButtonOffKeepsItsPlaceAndItsAction() {
        editor.select(0);
        float x = editor.selectedButton().x;
        assertTrue(editor.toggleSelectedEnabled());
        assertFalse(editor.selectedButton().enabled);
        assertEquals(KeyEvent.KEYCODE_E, editor.selectedButton().keyCode);
        assertEquals(x, editor.selectedButton().x, 0.0001f);
    }

    @Test
    public void switchingAButtonOffSurvivesStorageAndOldLayoutsStayEnabled() {
        editor.select(0);
        editor.toggleSelectedEnabled();
        assertTrue(editor.save(ctx));
        assertFalse("the switched-off button must still be off after a reload",
            stored().buttons.get(0).enabled);

        // A layout written before the field existed: every button must come back usable.
        SharedPreferences prefs = ctx.getSharedPreferences(
            TouchControlConfig.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(TouchControlConfig.PREF_BUTTONS,
            "[{\"label\":\"E\",\"key\":" + KeyEvent.KEYCODE_E + ",\"x\":0.2,\"y\":0.5},"
                + "{\"label\":\"Q\",\"key\":" + KeyEvent.KEYCODE_Q + ",\"x\":0.5,\"y\":0.5}]")
            .commit();
        TouchControlConfig loaded = stored();
        assertEquals(2, loaded.buttons.size());
        assertTrue("an older layout must load with every button enabled", loaded.buttons.get(0).enabled);
        assertTrue(loaded.buttons.get(1).enabled);
    }

    @Test
    public void restoringDefaultsChangesTheWorkingCopyOnly() {
        editor.restoreDefaults();
        assertEquals(TouchControlConfig.defaults().buttons.size(), editor.buttonCount());
        assertTrue(editor.isDirty());
        assertEquals("nothing may be stored before Save", 2, stored().buttons.size());
    }

    @Test
    public void cancelPutsTheStoredLayoutBack() {
        editor.select(1);
        editor.moveSelectedBy(-200f, -200f);
        editor.addButton();
        assertTrue(editor.isDirty());

        editor.cancel(ctx);

        assertEquals(2, editor.buttonCount());
        assertEquals(0.5f, editor.working.buttons.get(1).x, 0.0001f);
        assertFalse("the stored layout was never touched", editor.isDirty());
        assertEquals(-1, editor.selectedIndex());
    }

    @Test
    public void saveWritesOnceAndOnlyWhenThereIsSomethingToWrite() {
        assertFalse("nothing changed, so nothing must be written", editor.save(ctx));

        editor.select(1);
        editor.moveSelectedBy(-200f, 0f);
        assertTrue(editor.save(ctx));
        assertNotEquals(0.5f, stored().buttons.get(1).x);
        assertEquals(editor.working.buttons.get(1).x, stored().buttons.get(1).x, 0.0001f);
        assertFalse("a second save with no further change must write nothing", editor.save(ctx));
    }
}
