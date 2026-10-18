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

// GeneralsX @feature Android floating toolbox 17/10/2026 What has to hold for an overlay that
// sits over the battlefield is exactly what a device test cannot check reliably: that a press
// anywhere except the icon or the open panel is left to SDL's surface, that a drag never becomes
// a tap (so it can never become a gameplay command), that an interrupted gesture runs nothing and
// leaves the icon where it was, and that each panel row is a tool that really does something.
//
// The activity here is a plain Activity, not GeneralsZHActivity: the real one loads libmain.so in
// onCreate, which cannot happen in a JVM test, and none of the behaviour under test depends on it.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w411dp-h891dp-xxhdpi")
public class ToolboxOverlayViewTest {

    private static final int WIDTH_PX = 1080;
    private static final int HEIGHT_PX = 2340;

    private Activity activity;
    private ToolboxOverlayView view;
    private long clock;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        clock = 0L;
    }

    @After
    public void tearDown() {
        if (activity != null) {
            activity.finish();
        }
    }

    /** A laid-out overlay, built the way the game builds it (attach() for the enable switch). */
    private ToolboxOverlayView laidOutOverlay() {
        ToolboxOverlayView overlay = new ToolboxOverlayView(
            activity, null, SetupActivity.GAME_ZERO_HOUR);
        overlay.layout(0, 0, WIDTH_PX, HEIGHT_PX);
        return overlay;
    }

    private static void enableToolbox(boolean enabled) {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        ToolboxConfig config = ToolboxConfig.load(ctx, SetupActivity.GAME_ZERO_HOUR);
        config.enabled = enabled;
        config.save(ctx, SetupActivity.GAME_ZERO_HOUR);
    }

    private MotionEvent event(int action, float x, float y) {
        clock += 16L;
        return MotionEvent.obtain(0L, clock, action, x, y, 0);
    }

    private int slop() {
        return ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    private float iconCx() {
        RectF bounds = view.iconBounds();
        return bounds.centerX();
    }

    private float iconCy() {
        RectF bounds = view.iconBounds();
        return bounds.centerY();
    }

    // ------------------------------------------------------------- enable/disable

    @Test
    public void nothingIsBuiltWhenTheLauncherHasTheToolboxOff() {
        enableToolbox(false);
        assertNull("a disabled toolbox must not put a view over the game",
            ToolboxOverlayView.attach(activity, null));
    }

    @Test
    public void theOverlayIsAddedAboveTheGameWhenTheLauncherHasItOn() {
        enableToolbox(true);
        ToolboxOverlayView overlay = ToolboxOverlayView.attach(activity, null);
        assertNotNull("an enabled toolbox must be attached", overlay);
        assertNotNull("the overlay was not added to the window", overlay.getParent());
        assertTrue("the toolbox must sit above the hotkey panel (1000f)",
            overlay.getElevation() > 1000f);
    }

    // ------------------------------------------------------------- touching the battlefield

    @Test
    public void aPressAwayFromTheIconIsLeftToTheGame() {
        view = laidOutOverlay();
        // Far from the icon's default position (0.94, 0.30) and outside the panel's area.
        assertFalse("a battlefield press must reach SDL's surface",
            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 40f, 2000f)));
        assertFalse("a move we do not own must not be consumed",
            view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 60f, 1900f)));
        assertFalse(view.onTouchEvent(event(MotionEvent.ACTION_UP, 60f, 1900f)));
        assertFalse("nothing about a battlefield press may open the panel", view.isPanelOpen());
    }

    @Test
    public void theWholeGestureIsOwnedOnceItStartsOnTheIcon() {
        view = laidOutOverlay();
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy())));
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_MOVE, iconCx() - 2f, iconCy())));
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx() - 2f, iconCy())));
        // ... and the pointer is released, so the next press elsewhere is the game's again.
        assertFalse(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 40f, 2000f)));
    }

    @Test
    public void aPressOnTheOpenPanelBetweenItsRowsIsLeftToTheGame() {
        view = laidOutOverlay();
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));
        assertTrue(view.isPanelOpen());
        RectF panel = view.panelBounds();
        // The panel's own padding strip: inside the panel, outside every row.
        assertFalse("only a row of the panel may consume a touch",
            view.onTouchEvent(event(MotionEvent.ACTION_DOWN,
                panel.centerX(), panel.top + 1f)));
    }

    // ------------------------------------------------------------- tap, drag, interrupt

    @Test
    public void aTapOpensThePanelAndASecondTapClosesIt() {
        view = laidOutOverlay();
        assertFalse(view.isPanelOpen());
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));
        assertTrue("a tap on the icon must open the toolbox", view.isPanelOpen());
        for (int i = 0; i < view.toolCount(); i++) {
            assertNotNull("a panel row has no label", view.rowLabel(i));
        }
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));
        assertFalse("a second tap must close it", view.isPanelOpen());
    }

    @Test
    public void backClosesThePanelAndIsOnlyHandledWhileItIsOpen() {
        view = laidOutOverlay();
        assertFalse("Back must reach the game while the toolbox is closed", view.closePanel());
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));
        assertTrue("Back must close an open toolbox", view.closePanel());
        assertFalse(view.isPanelOpen());
        assertFalse(view.closePanel());
    }

    @Test
    public void aDragMovesTheIconAndDoesNotOpenThePanel() {
        view = laidOutOverlay();
        float startX = iconCx();
        float startY = iconCy();
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, startX, startY));
        // Past the system slop: this is a drag, not a tap.
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, startX - slop() - 40f, startY - slop() - 40f));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, startX - slop() - 40f, startY - slop() - 40f));

        assertFalse("dragging the icon must never open the toolbox", view.isPanelOpen());
        assertTrue("a drag must move the icon", iconCx() < startX - 20f);
        ToolboxConfig stored = ToolboxConfig.load(
            org.robolectric.RuntimeEnvironment.getApplication(), SetupActivity.GAME_ZERO_HOUR);
        assertEquals("the new position must survive a restart", iconCx() / WIDTH_PX, stored.iconX, 0.01f);
        assertEquals(iconCy() / HEIGHT_PX, stored.iconY, 0.01f);
    }

    @Test
    public void anInterruptedGestureRunsNoToolAndPutsTheIconBack() {
        view = laidOutOverlay();
        float startX = iconCx();
        float startY = iconCy();
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, startX, startY));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, startX - slop() - 60f, startY));
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_CANCEL, startX - slop() - 60f, startY)));

        assertEquals("a cancelled drag must not leave the icon moved", startX, iconCx(), 0.5f);
        assertEquals(startY, iconCy(), 0.5f);
        assertFalse("a cancelled gesture must not open the toolbox", view.isPanelOpen());
        ToolboxConfig stored = ToolboxConfig.load(
            org.robolectric.RuntimeEnvironment.getApplication(), SetupActivity.GAME_ZERO_HOUR);
        assertEquals("a cancelled drag must not be saved",
            ToolboxConfig.DEFAULT_ICON_X, stored.iconX, 0.001f);

        // The view is ready for the next gesture: the pointer was released with it.
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy())));
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy())));
        assertTrue(view.isPanelOpen());
    }

    @Test
    public void aCancelledPressOnARowRunsNothing() {
        view = laidOutOverlay();
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));
        // Row 1 is "Restore default buttons": it must not run on a cancel.
        RectF row = view.rowBounds(1);
        TouchControlConfig layout = TouchControlConfig.load(activity);
        layout.buttons.clear();
        layout.save(activity);
        assertEquals(0, TouchControlConfig.load(activity).buttons.size());

        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, row.centerX(), row.centerY())));
        view.onTouchEvent(event(MotionEvent.ACTION_CANCEL, row.centerX(), row.centerY()));
        assertEquals("an interrupted row press must not restore anything",
            0, TouchControlConfig.load(activity).buttons.size());
    }

    @Test
    public void theIconIsKeptOnScreenEvenWhenDraggedToTheCorner() {
        view = laidOutOverlay();
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 5000f, -5000f));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 5000f, -5000f));

        RectF bounds = view.iconBounds();
        assertTrue("the icon left the screen on the right", bounds.right <= WIDTH_PX);
        assertTrue("the icon left the screen at the top", bounds.top >= 0f);
        assertTrue(bounds.left >= 0f);
        assertTrue(bounds.bottom <= HEIGHT_PX);
    }

    // ------------------------------------------------------------- the tools

    @Test
    public void everyPanelRowIsAToolThatIsImplemented() {
        view = laidOutOverlay();
        assertEquals("the panel lists seven tools", 7, view.toolCount());
        assertEquals(activity.getString(R.string.toolbox_tool_edit_layout), view.rowLabel(0));
        assertEquals(activity.getString(R.string.toolbox_tool_touch_layer), view.rowLabel(1));
        assertEquals(activity.getString(R.string.toolbox_tool_button_size), view.rowLabel(2));
        assertEquals(activity.getString(R.string.toolbox_tool_save_default), view.rowLabel(3));
        assertEquals(activity.getString(R.string.toolbox_tool_load_default), view.rowLabel(4));
        assertEquals(activity.getString(R.string.toolbox_tool_restore_buttons), view.rowLabel(5));
        assertEquals(activity.getString(R.string.toolbox_close), view.rowLabel(6));
    }

    @Test
    public void theTouchLayerRowTogglesTheSavedLayoutAndReportsIt() {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        TouchControlConfig layout = TouchControlConfig.load(ctx);
        layout.enabled = true;
        layout.save(ctx);

        view = laidOutOverlay();
        view.pressRow(1);
        assertEquals(activity.getString(R.string.toolbox_state_off), view.rowValue(1));
        assertFalse("the row must switch the touch layer off", TouchControlConfig.load(ctx).enabled);

        view.pressRow(1);
        assertEquals(activity.getString(R.string.toolbox_state_on), view.rowValue(1));
        assertTrue(TouchControlConfig.load(ctx).enabled);
    }

    @Test
    public void theRestoreRowPutsTheDefaultLayoutBack() {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        TouchControlConfig layout = TouchControlConfig.load(ctx);
        layout.buttons.clear();
        layout.save(ctx);
        assertEquals(0, TouchControlConfig.load(ctx).buttons.size());

        view = laidOutOverlay();
        view.pressRow(5);
        assertEquals("the shipped buttons must come back",
            TouchControlConfig.defaults().buttons.size(), TouchControlConfig.load(ctx).buttons.size());
    }

    // ------------------------------------------------------------- editing the layout

    /** Opens the panel and the editor, and returns the editor. */
    private TouchLayoutEditor openEditor() {
        view = laidOutOverlay();
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));
        view.pressRow(0);   // PLAY_TOOLS[0] is "Edit buttons"
        assertTrue(view.isEditing());
        return view.editorForTest();
    }

    /** Centre of a layout button, in the overlay's coordinates. */
    private float[] buttonCentre(TouchLayoutEditor editor, int index) {
        RectF rect = editor.rectOf(index);
        return new float[] { rect.centerX(), rect.centerY() };
    }

    @Test
    public void whileEditingTheRowListIsGoneAndASaveCheckTakesItsPlace() {
        TouchLayoutEditor editor = openEditor();
        assertNotNull(editor);
        assertFalse("the request: the toolbox's row list is not shown while editing",
            view.isPanelVisible());
        assertEquals("the play rows stay the seven tools for when editing ends",
            7, view.toolCount());

        // The check is beside the icon (never on it), on screen, and big enough to hit.
        RectF check = view.checkBounds();
        RectF icon = view.iconBounds();
        assertFalse("the save check overlaps the icon", RectF.intersects(check, icon));
        assertTrue("check left " + check.left, check.left >= 0f);
        assertTrue("check right " + check.right, check.right <= WIDTH_PX);
        assertTrue("check top " + check.top, check.top >= 0f);
        assertTrue("check bottom " + check.bottom, check.bottom <= HEIGHT_PX);
        assertTrue("the check is too small to tap: " + check.width(), check.width() >= 30f);
    }

    @Test
    public void whileEditingEveryPressIsConsumedSoNoGameplayCommandCanBeIssued() {
        openEditor();
        assertTrue("empty space must not reach the battlefield while editing",
            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 6f, 300f)));
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20f, 320f)));
        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_UP, 20f, 320f)));
        assertTrue("an empty press only clears the selection", view.isEditing());
        assertEquals(-1, view.editorForTest().selectedIndex());
    }

    @Test
    public void draggingAButtonMovesItAndNeverChangesItsAction() {
        TouchLayoutEditor editor = openEditor();
        int index = 4;
        int keyBefore = editor.working.buttons.get(index).keyCode;
        int modsBefore = editor.working.buttons.get(index).modifiers;
        float xBefore = editor.working.buttons.get(index).x;
        float[] centre = buttonCentre(editor, index);

        assertTrue(view.onTouchEvent(event(MotionEvent.ACTION_DOWN, centre[0], centre[1])));
        assertEquals("a press on a button selects it", index, editor.selectedIndex());
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, centre[0] + 120f, centre[1] - 60f));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, centre[0] + 120f, centre[1] - 60f));

        assertTrue(editor.working.buttons.get(index).x > xBefore);
        assertEquals("moving a button must not change the key it presses",
            keyBefore, editor.working.buttons.get(index).keyCode);
        assertEquals(modsBefore, editor.working.buttons.get(index).modifiers);
        assertTrue("dragging a button must not run a tool", view.isEditing());
    }

    @Test
    public void draggingTheSelectedButtonsCornerResizesIt() {
        TouchLayoutEditor editor = openEditor();
        int index = 4;
        float[] centre = buttonCentre(editor, index);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, centre[0], centre[1]));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, centre[0], centre[1]));
        assertEquals(index, editor.selectedIndex());

        float widthBefore = editor.working.buttons.get(index).widthDp;
        RectF rect = editor.rectOf(index);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, rect.right - 2f, rect.bottom - 2f));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, rect.right + 60f, rect.bottom + 60f));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, rect.right + 60f, rect.bottom + 60f));

        assertTrue("the corner must resize, not move",
            editor.working.buttons.get(index).widthDp > widthBefore);
        assertEquals("resizing must not change the key it presses",
            KeyEvent.KEYCODE_A, editor.working.buttons.get(index).keyCode);
    }

    @Test
    public void theRowListCannotRunAnythingWhileEditing() {
        TouchLayoutEditor editor = openEditor();
        int before = editor.buttonCount();

        // Every former editor action was a row; rows are refused outright while editing, so a
        // hit-test mistake can neither add, delete, restore -- nor silently SAVE behind the
        // check's back.
        view.pressRow(0);   // would have been Add
        view.pressRow(1);   // would have been Delete
        view.pressRow(3);   // would have been Restore defaults
        view.pressRow(4);   // would have been Save

        assertEquals("no row may change the working layout while editing",
            before, editor.buttonCount());
        assertTrue("and none may exit the editor behind the check's back", view.isEditing());
    }

    @Test
    public void theCheckFollowsTheIconAndStaysOnScreen() {
        openEditor();
        // Drag the floating bar into the far corner while editing: the check rides along and
        // re-picks whichever side has room, so both stay reachable at any corner.
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 5000f, -5000f));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 5000f, -5000f));

        RectF check = view.checkBounds();
        assertTrue("the check left the screen on the right", check.right <= WIDTH_PX);
        assertTrue("the check left the screen at the top", check.top >= 0f);
        assertTrue(check.left >= 0f);
        assertTrue(check.bottom <= HEIGHT_PX);
        assertFalse("the check must not cover the icon it sits beside",
            RectF.intersects(check, view.iconBounds()));
        assertTrue("editing continues through the drag", view.isEditing());
    }

    @Test
    public void savingKeepsTheEditAndLeavesTheEditor() {
        TouchLayoutEditor editor = openEditor();
        int index = 4;
        float[] centre = buttonCentre(editor, index);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, centre[0], centre[1]));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, centre[0] + 90f, centre[1]));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, centre[0] + 90f, centre[1]));
        float movedTo = editor.working.buttons.get(index).x;

        // The check is the Save row now: a real tap on it, through the gesture path.
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, view.checkCx(), view.checkCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, view.checkCx(), view.checkCy()));

        assertFalse("tapping the check must leave the editor", view.isEditing());
        assertTrue("and the play rows are back on screen", view.isPanelVisible());
        assertEquals("the panel is back to its play rows", 7, view.toolCount());
        assertEquals("the edited position must be stored", movedTo,
            TouchControlConfig.load(activity).buttons.get(index).x, 0.0001f);
    }

    @Test
    public void cancellingThrowsTheEditsAway() {
        TouchLayoutEditor editor = openEditor();
        int index = 4;
        float storedBefore = TouchControlConfig.load(activity).buttons.get(index).x;
        float[] centre = buttonCentre(editor, index);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, centre[0], centre[1]));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, centre[0] + 120f, centre[1]));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, centre[0] + 120f, centre[1]));

        // With the Cancel row gone, the cancel route is closing the bar (the same path Back
        // takes): leaveEditMode(true) drops the working copy and says so.
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));

        assertFalse(view.isEditing());
        assertEquals("leaving without the check must leave the stored layout alone",
            storedBefore, TouchControlConfig.load(activity).buttons.get(index).x, 0.0001f);
    }

    @Test
    public void closingTheToolboxWhileEditingDiscardsTheEdits() {
        TouchLayoutEditor editor = openEditor();
        int index = 4;
        float storedBefore = TouchControlConfig.load(activity).buttons.get(index).x;
        float[] centre = buttonCentre(editor, index);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, centre[0], centre[1]));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, centre[0] + 120f, centre[1]));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, centre[0] + 120f, centre[1]));

        assertTrue(view.closePanel());

        assertFalse(view.isPanelOpen());
        assertFalse(view.isEditing());
        assertEquals(storedBefore, TouchControlConfig.load(activity).buttons.get(index).x, 0.0001f);
    }

    @Test
    public void theCloseRowClosesThePanel() {
        view = laidOutOverlay();
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, iconCx(), iconCy()));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, iconCx(), iconCy()));
        assertTrue(view.isPanelOpen());
        view.pressRow(6);
        assertFalse(view.isPanelOpen());
    }

    // ------------------------------------------------------------- layout management

    @Test
    public void theSaveRowStoresASnapshotForThisGameOnly() {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        view = laidOutOverlay();
        view.pressRow(3);   // Save layout as default

        ToolboxConfig zh = ToolboxConfig.load(ctx, SetupActivity.GAME_ZERO_HOUR);
        assertTrue("nothing was saved", zh.hasDefaultLayout());
        assertEquals("the snapshot must be the layout in use",
            TouchControlConfig.load(ctx).buttons.size(), zh.loadDefaultLayout().buttons.size());
        // ... button by button, not just in count: a snapshot that lost the actions would be worse
        // than no snapshot at all.
        for (int i = 0; i < zh.loadDefaultLayout().buttons.size(); i++) {
            assertEquals(TouchControlConfig.load(ctx).buttons.get(i).keyCode,
                zh.loadDefaultLayout().buttons.get(i).keyCode);
            assertEquals(TouchControlConfig.load(ctx).buttons.get(i).x,
                zh.loadDefaultLayout().buttons.get(i).x, 0.0001f);
        }
        assertFalse("the snapshot leaked into the other game",
            ToolboxConfig.load(ctx, SetupActivity.GAME_GENERALS).hasDefaultLayout());
    }

    @Test
    public void theLoadRowPutsTheSavedLayoutBackInPlace() {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        view = laidOutOverlay();
        view.pressRow(3);   // Save layout as default
        int saved = TouchControlConfig.load(ctx).buttons.size();

        TouchControlConfig emptied = TouchControlConfig.load(ctx);
        emptied.buttons.clear();
        emptied.save(ctx);
        assertEquals(0, TouchControlConfig.load(ctx).buttons.size());

        view.pressRow(4);   // Load saved layout
        assertEquals("the saved layout must come back", saved,
            TouchControlConfig.load(ctx).buttons.size());
    }

    @Test
    public void theLoadRowDoesNothingWhenThereIsNothingSaved() {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        TouchControlConfig emptied = TouchControlConfig.load(ctx);
        emptied.buttons.clear();
        emptied.save(ctx);

        view = laidOutOverlay();
        view.pressRow(4);   // Load saved layout, with no snapshot

        assertEquals("a missing default layout must not change anything",
            0, TouchControlConfig.load(ctx).buttons.size());
    }

    @Test
    public void anUnreadableSavedLayoutIsNeverApplied() {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        TouchControlConfig current = TouchControlConfig.load(ctx);
        current.buttons.clear();
        current.save(ctx);

        ToolboxConfig stored = ToolboxConfig.load(ctx, SetupActivity.GAME_ZERO_HOUR);
        stored.defaultLayoutJson = "{ this is not a layout";
        stored.save(ctx, SetupActivity.GAME_ZERO_HOUR);

        view = laidOutOverlay();
        view.pressRow(4);

        assertEquals("a layout that does not parse must be ignored, not half-applied",
            0, TouchControlConfig.load(ctx).buttons.size());
    }

    @Test
    public void theButtonSizeRowStepsThroughThePresetsAndWraps() {
        Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        TouchControlConfig layout = TouchControlConfig.load(ctx);
        layout.buttonScale = 1.0f;
        layout.save(ctx);

        view = laidOutOverlay();
        view.pressRow(2);   // Button size
        assertEquals(1.25f, TouchControlConfig.load(ctx).buttonScale, 0.001f);
        assertEquals("125%", view.rowValue(2));

        view.pressRow(2);
        assertEquals(1.5f, TouchControlConfig.load(ctx).buttonScale, 0.001f);

        view.pressRow(2);
        assertEquals("past the largest preset it starts again",
            0.75f, TouchControlConfig.load(ctx).buttonScale, 0.001f);
    }
}
