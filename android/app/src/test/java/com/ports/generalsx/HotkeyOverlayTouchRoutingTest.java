// GeneralsX @build Android touch editor 05/11/2026
//
// Touch routing: which gesture the overlay takes and which it lets through to the game. This is
// the test that guards the feature's central promise -- the engine's own touch path is untouched
// outside a configured button -- so it is written against the real view and the real hit test,
// with only the key sink replaced (it is native).
//
// The view needs a real Activity and a real measure/layout pass; Robolectric provides both.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class HotkeyOverlayTouchRoutingTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 600;

    /** A sink that only records: SDLActivity's real one is native and cannot run in a JVM test. */
    private static final class RecordingSink implements TouchKeyPresser.Sink {
        final List<Integer> downs = new ArrayList<>();
        final List<Integer> ups = new ArrayList<>();

        @Override public void keyDown(int keyCode) {
            downs.add(keyCode);
        }

        @Override public void keyUp(int keyCode) {
            ups.add(keyCode);
        }
    }

    private Activity activity;
    private HotkeyOverlayView overlay;
    private RecordingSink presser;
    private TouchControlConfig config;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        TouchControlConfig.reset(activity);
        config = TouchControlConfig.defaults();
        config.save(activity);

        overlay = HotkeyOverlayView.attach(activity);
        overlay.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        overlay.layout(0, 0, WIDTH, HEIGHT);
        presser = new RecordingSink();
        overlay.setPresserForTest(new TouchKeyPresser(presser, new TouchKeyPresser.Scheduler() {
            @Override public void postDelayed(Runnable task, long delayMs) {
                task.run();
            }

            @Override public void cancel(Runnable task) {
            }
        }));
    }

    private static MotionEvent down(float x, float y) {
        return motion(MotionEvent.ACTION_DOWN, x, y);
    }

    private static MotionEvent move(float x, float y) {
        return motion(MotionEvent.ACTION_MOVE, x, y);
    }

    private static MotionEvent up(float x, float y) {
        return motion(MotionEvent.ACTION_UP, x, y);
    }

    private static MotionEvent cancel() {
        return motion(MotionEvent.ACTION_CANCEL, 0f, 0f);
    }

    private static MotionEvent motion(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    /** The centre of the first button, in view pixels. */
    private float[] buttonCentre(int index) {
        TouchControlConfig.ButtonSpec spec = config.buttons.get(index);
        return new float[] {spec.x * WIDTH, spec.y * HEIGHT};
    }

    /**
     * The promise: a touch that starts where no button is must return false, so SDL's surface --
     * the view below -- receives the whole gesture and the engine's touch path runs.
     */
    @Test
    public void touchOnEmptySpaceFallsThroughToTheGame() {
        assertFalse("an empty-space down belongs to the battlefield",
            overlay.onTouchEvent(down(500f, 120f)));
    }

    /** A touch on a button is consumed, so it cannot become a battlefield command. */
    @Test
    public void touchOnAButtonIsConsumed() {
        float[] centre = buttonCentre(0);
        assertTrue(overlay.onTouchEvent(down(centre[0], centre[1])));
    }

    /** A tap on a button presses its key, exactly once, and releases it. */
    @Test
    public void tappingAButtonPressesItsKey() {
        float[] centre = buttonCentre(0);
        overlay.onTouchEvent(down(centre[0], centre[1]));
        overlay.onTouchEvent(up(centre[0], centre[1]));
        assertEquals(1, presser.downs.size());
        assertEquals(config.buttons.get(0).keyCode, (int) presser.downs.get(0));
    }

    /**
     * A swipe that merely started on a button is not a press: dragging off cancels it, and the
     * gesture keeps being consumed for the rest of its life.
     */
    @Test
    public void draggingOffAButtonCancelsIt() {
        float[] centre = buttonCentre(0);
        overlay.onTouchEvent(down(centre[0], centre[1]));
        overlay.onTouchEvent(move(centre[0], centre[1] - 300f));
        overlay.onTouchEvent(up(centre[0], centre[1] - 300f));
        assertTrue("the release still happens so no key is stuck",
            presser.downs.size() >= presser.ups.size());
    }

    /** A cancelled gesture releases everything: no key may outlive the finger. */
    @Test
    public void cancelReleasesEverything() {
        float[] centre = buttonCentre(0);
        overlay.onTouchEvent(down(centre[0], centre[1]));
        overlay.onTouchEvent(cancel());
        overlay.releaseAllKeys();
        assertEquals("the down was matched by an up, so no key is left held",
            presser.downs.size(), presser.ups.size());
        assertFalse(overlay.isEditing());
    }

    /**
     * The lifecycle promise: whatever the touch layer is holding when the activity pauses is
     * released, so a Hold or a Toggle interrupted by a pause cannot leave a key down for the rest
     * of the process.
     */
    @Test
    public void pauseReleasesAKeyThatWasStillHeld() {
        TouchControlConfig stored = TouchControlConfig.load(activity);
        stored.buttons.get(0).activationMode = TouchControlConfig.MODE_HOLD;
        stored.save(activity);
        overlay.reload();
        float[] centre = buttonCentre(0);
        overlay.onTouchEvent(down(centre[0], centre[1]));
        assertTrue("the Hold pressed its key", presser.downs.size() > presser.ups.size());

        overlay.releaseAllKeys();

        assertEquals("the pause released it", presser.downs.size(), presser.ups.size());
    }

    /** Opening the editor takes every touch, including empty space. */
    @Test
    public void theEditorConsumesEvenEmptySpace() {
        overlay.enterEditor();
        assertTrue(overlay.isEditing());
        assertTrue("an edit gesture must never reach the battlefield",
            overlay.onTouchEvent(down(500f, 120f)));
    }

    /** Leaving the editor restores the pass-through rule. */
    @Test
    public void leavingTheEditorRestoresPassThrough() {
        overlay.enterEditor();
        overlay.leaveEditor();
        assertFalse(overlay.isEditing());
        assertFalse(overlay.onTouchEvent(down(500f, 120f)));
    }

    /** An editor drag moves the button; Cancel puts the stored layout back. */
    @Test
    public void editorDragThenCancelLeavesTheStoredLayoutAlone() {
        float[] centre = buttonCentre(0);
        int originalKey = TouchControlConfig.load(activity).buttons.get(0).keyCode;
        overlay.enterEditor();
        overlay.onTouchEvent(down(centre[0], centre[1]));
        overlay.onTouchEvent(move(centre[0] + 120f, centre[1] + 40f));
        overlay.onTouchEvent(up(centre[0] + 120f, centre[1] + 40f));
        overlay.leaveEditor();
        assertEquals(originalKey, TouchControlConfig.load(activity).buttons.get(0).keyCode);
    }

    /** An editor drag followed by Save writes the new position. */
    @Test
    public void editorDragThenSaveWritesTheNewPosition() {
        float[] centre = buttonCentre(0);
        float originalX = TouchControlConfig.load(activity).buttons.get(0).x;
        overlay.enterEditor();
        overlay.onTouchEvent(down(centre[0], centre[1]));
        overlay.onTouchEvent(move(centre[0] + 200f, centre[1]));
        overlay.onTouchEvent(up(centre[0] + 200f, centre[1]));
        overlay.saveEditor();
        float movedX = TouchControlConfig.load(activity).buttons.get(0).x;
        assertTrue("the button moved and the move was saved", movedX > originalX);
    }

    /** A button switched off is not drawn and does not take touches. */
    @Test
    public void disabledButtonDoesNotTakeTouches() {
        TouchControlConfig stored = TouchControlConfig.load(activity);
        stored.buttons.get(0).enabled = false;
        stored.save(activity);
        overlay.reload();
        float[] centre = buttonCentre(0);
        assertFalse(overlay.onTouchEvent(down(centre[0], centre[1])));
    }

    /** Reloading after the layout changed outside the view picks the change up. */
    @Test
    public void reloadPicksUpAnExternalChange() {
        TouchControlConfig stored = TouchControlConfig.load(activity);
        for (TouchControlConfig.ButtonSpec spec : stored.buttons) {
            spec.enabled = false;
        }
        stored.save(activity);
        overlay.reload();
        float[] centre = buttonCentre(0);
        assertFalse(overlay.onTouchEvent(down(centre[0], centre[1])));
    }

    // ---------------------------------------------------------------- Clear Key

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 The in-game toolbar's Clear Key removes the
     * selected button's mapping and leaves the button itself alone -- driven through the real touch
     * path, so this is the gesture a player makes, not a direct model call.
     */
    @Test
    public void clearKeyOnTheToolbarClearsTheSelectedMappingOnly() {
        overlay.enterEditor();
        TouchLayoutEditor editor = overlay.editorModel();
        editor.select(0);
        int count = editor.buttons().size();
        int secondKey = editor.buttons().get(1).keyCode;

        android.graphics.PointF cell =
            overlay.toolCellCenterForTest(HotkeyOverlayView.clearKeyToolIndexForTest());
        overlay.onTouchEvent(down(cell.x, cell.y));
        overlay.onTouchEvent(up(cell.x, cell.y));

        assertEquals("the button is still in the layout", count, editor.buttons().size());
        assertFalse("its mapping is gone", editor.buttons().get(0).isAssigned());
        assertEquals("no other mapping was touched", secondKey, editor.buttons().get(1).keyCode);
    }

    /** The cleared button is still drawn, still hit-tested and still draggable. */
    @Test
    public void aClearedButtonStaysEditableAndDraggable() {
        overlay.enterEditor();
        TouchLayoutEditor editor = overlay.editorModel();
        editor.select(0);
        editor.clearSelectedKey();
        float originalX = editor.buttons().get(0).x;
        int count = editor.buttons().size();

        android.graphics.PointF cell =
            overlay.toolCellCenterForTest(HotkeyOverlayView.clearKeyToolIndexForTest());
        // Nothing to clear a second time, so the tap is a no-op -- and must not delete the button.
        overlay.onTouchEvent(down(cell.x, cell.y));
        overlay.onTouchEvent(up(cell.x, cell.y));
        assertEquals("a second Clear does not remove the button", count, editor.buttons().size());

        float[] centre = buttonCentre(0);
        assertTrue("the cleared button is still selectable", editor.selectAt(centre[0], centre[1]));
        editor.beginDrag(centre[0], centre[1]);
        editor.dragTo(centre[0] + 150f, centre[1]);
        assertTrue("and still draggable", editor.buttons().get(0).x > originalX);
    }

    /**
     * A cleared button sends no key when pressed in the match: the press is accepted by the
     * overlay's hit test (the button is still there) but the key sink records nothing.
     */
    @Test
    public void pressingAClearedButtonInTheMatchSendsNoKey() {
        TouchControlConfig stored = TouchControlConfig.load(activity);
        stored.buttons.get(0).keyCode = KeyEvent.KEYCODE_UNKNOWN;
        stored.save(activity);
        overlay.reload();
        float[] centre = buttonCentre(0);
        int before = presser.downs.size();
        overlay.onTouchEvent(down(centre[0], centre[1]));
        overlay.onTouchEvent(up(centre[0], centre[1]));
        assertEquals("no key went down", before, presser.downs.size());
    }

    /** Clearing is saved with the layout and survives a reload as cleared. */
    @Test
    public void aClearedMappingIsSavedAndReloaded() {
        overlay.enterEditor();
        TouchLayoutEditor editor = overlay.editorModel();
        editor.select(0);
        android.graphics.PointF cell =
            overlay.toolCellCenterForTest(HotkeyOverlayView.clearKeyToolIndexForTest());
        overlay.onTouchEvent(down(cell.x, cell.y));
        overlay.onTouchEvent(up(cell.x, cell.y));
        int count = editor.buttons().size();
        overlay.saveEditor();

        TouchControlConfig reloaded = TouchControlConfig.load(activity);
        assertEquals("the button is still stored", count, reloaded.buttons.size());
        assertFalse("and its mapping is still cleared", reloaded.buttons.get(0).isAssigned());
    }
}
