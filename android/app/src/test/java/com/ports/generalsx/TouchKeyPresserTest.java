// GeneralsX @build Android touch editor 05/11/2026
//
// The key state machine: the one place that decides when a key goes down and when it comes back
// up. A stuck key is the worst failure this feature can have -- it holds a game binding for the
// rest of the session -- so "every press has a matching release" is tested on every path out,
// including the ones a finger never takes on purpose: a second press, an interrupted gesture and
// the activity pausing mid-hold.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TouchKeyPresserTest {

    /** Records every key event, in order, and lets a test run the deferred Tap releases. */
    private static final class Recorder implements TouchKeyPresser.Sink, TouchKeyPresser.Scheduler {
        final List<String> events = new ArrayList<>();
        final List<Runnable> pending = new ArrayList<>();

        @Override public void keyDown(int keyCode) {
            events.add("D" + keyCode);
        }

        @Override public void keyUp(int keyCode) {
            events.add("U" + keyCode);
        }

        @Override public void postDelayed(Runnable task, long delayMs) {
            pending.add(task);
        }

        @Override public void cancel(Runnable task) {
            pending.remove(task);
        }

        void runPending() {
            List<Runnable> copy = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : copy) {
                task.run();
            }
        }

        int downs(int keyCode) {
            return count("D" + keyCode);
        }

        int ups(int keyCode) {
            return count("U" + keyCode);
        }

        private int count(String event) {
            int total = 0;
            for (String value : events) {
                if (value.equals(event)) {
                    total++;
                }
            }
            return total;
        }
    }

    private Recorder recorder;
    private TouchKeyPresser presser;

    @Before
    public void setUp() {
        recorder = new Recorder();
        presser = new TouchKeyPresser(recorder, recorder);
    }

    private static TouchControlConfig.ButtonSpec button(int keyCode, int mode) {
        TouchControlConfig.ButtonSpec spec = new TouchControlConfig.ButtonSpec(
            TouchKeyCatalog.displayName(keyCode), keyCode, 0, 0.5f, 0.5f);
        spec.activationMode = mode;
        return spec;
    }

    /** Tap presses and releases once; the release is deferred to the engine's next frame. */
    @Test
    public void tapPressesAndSchedulesOneRelease() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_A, TouchControlConfig.MODE_TAP);
        presser.press(0, spec);
        assertEquals(1, recorder.downs(KeyEvent.KEYCODE_A));
        assertEquals("not released until the timer fires", 0, recorder.ups(KeyEvent.KEYCODE_A));
        recorder.runPending();
        assertEquals(1, recorder.ups(KeyEvent.KEYCODE_A));
        assertFalse(presser.isButtonDown(0));
    }

    /** A second tap while the first is still held must not stack two unmatched downs. */
    @Test
    public void repeatedTapDoesNotStackDowns() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_A, TouchControlConfig.MODE_TAP);
        presser.press(0, spec);
        presser.press(0, spec);
        assertEquals(1, recorder.downs(KeyEvent.KEYCODE_A));
        recorder.runPending();
        // The second press replaced the first's timer, so exactly one release is outstanding.
        assertEquals(1, recorder.ups(KeyEvent.KEYCODE_A));
        assertFalse(presser.isButtonDown(0));
    }

    /** Hold keeps the key down until the finger lifts -- nothing is scheduled. */
    @Test
    public void holdReleasesOnFingerUp() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_A, TouchControlConfig.MODE_HOLD);
        presser.press(0, spec);
        assertEquals(1, recorder.downs(KeyEvent.KEYCODE_A));
        assertTrue(presser.isButtonDown(0));
        assertEquals("Hold schedules nothing", 0, recorder.pending.size());
        presser.release(0, spec);
        assertEquals(1, recorder.ups(KeyEvent.KEYCODE_A));
        assertFalse(presser.isButtonDown(0));
    }

    /** Toggle goes down on the first press and up on the next, and stays down in between. */
    @Test
    public void toggleLatchesUntilPressedAgain() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_A,
            TouchControlConfig.MODE_TOGGLE);
        presser.press(0, spec);
        presser.release(0, spec);
        assertEquals(1, recorder.downs(KeyEvent.KEYCODE_A));
        assertEquals("still latched on", 0, recorder.ups(KeyEvent.KEYCODE_A));
        assertTrue(presser.isButtonDown(0));
        presser.press(0, spec);
        assertEquals(1, recorder.ups(KeyEvent.KEYCODE_A));
        assertFalse(presser.isButtonDown(0));
    }

    /** Modifiers go down before the key and come up after it, in reverse order. */
    @Test
    public void modifiersSurroundThePrimaryKey() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_A, TouchControlConfig.MODE_HOLD);
        spec.modifiers = TouchKeyCatalog.MOD_CTRL | TouchKeyCatalog.MOD_SHIFT;
        presser.press(0, spec);
        assertEquals("D" + KeyEvent.KEYCODE_CTRL_LEFT + ",D" + KeyEvent.KEYCODE_SHIFT_LEFT
            + ",D" + KeyEvent.KEYCODE_A, String.join(",", recorder.events));
        recorder.events.clear();
        presser.release(0, spec);
        assertEquals("U" + KeyEvent.KEYCODE_A + ",U" + KeyEvent.KEYCODE_SHIFT_LEFT
            + ",U" + KeyEvent.KEYCODE_CTRL_LEFT, String.join(",", recorder.events));
    }

    /** Two buttons sharing Ctrl send one down and one up for it, not two of each. */
    @Test
    public void aSharedModifierIsPressedOnce() {
        TouchControlConfig.ButtonSpec first = button(KeyEvent.KEYCODE_A, TouchControlConfig.MODE_HOLD);
        first.modifiers = TouchKeyCatalog.MOD_CTRL;
        TouchControlConfig.ButtonSpec second = button(KeyEvent.KEYCODE_B,
            TouchControlConfig.MODE_HOLD);
        second.modifiers = TouchKeyCatalog.MOD_CTRL;

        presser.press(0, first);
        presser.press(1, second);
        assertEquals(1, recorder.downs(KeyEvent.KEYCODE_CTRL_LEFT));
        presser.release(0, first);
        assertEquals("still held by the other button", 0, recorder.ups(KeyEvent.KEYCODE_CTRL_LEFT));
        presser.release(1, second);
        assertEquals(1, recorder.ups(KeyEvent.KEYCODE_CTRL_LEFT));
    }

    /** A Toggle latched on is released by releaseAll, which is what an interruption calls. */
    @Test
    public void releaseAllReleasesLatchedAndHeldKeys() {
        TouchControlConfig.ButtonSpec hold = button(KeyEvent.KEYCODE_A, TouchControlConfig.MODE_HOLD);
        TouchControlConfig.ButtonSpec toggle = button(KeyEvent.KEYCODE_B,
            TouchControlConfig.MODE_TOGGLE);
        TouchControlConfig.ButtonSpec tap = button(KeyEvent.KEYCODE_C, TouchControlConfig.MODE_TAP);
        presser.press(0, hold);
        presser.press(1, toggle);
        presser.press(2, tap);

        presser.releaseAll();
        assertEquals(1, recorder.ups(KeyEvent.KEYCODE_A));
        assertEquals(1, recorder.ups(KeyEvent.KEYCODE_B));
        assertEquals("the pending tap release ran too", 1, recorder.ups(KeyEvent.KEYCODE_C));
        assertFalse(presser.isButtonDown(0));
        assertFalse(presser.isButtonDown(1));
        assertFalse(presser.isButtonDown(2));
    }

    /** After releaseAll a later Tap still works -- releaseAll leaves nothing latched behind. */
    @Test
    public void presserIsReusableAfterReleaseAll() {
        TouchControlConfig.ButtonSpec toggle = button(KeyEvent.KEYCODE_B,
            TouchControlConfig.MODE_TOGGLE);
        presser.press(0, toggle);
        presser.releaseAll();
        presser.press(0, toggle);
        assertEquals("pressed again, not toggled off", 2, recorder.downs(KeyEvent.KEYCODE_B));
    }

    /** A standalone modifier button presses exactly the key it names. */
    @Test
    public void standaloneModifierButtonPressesItsOwnKey() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_CTRL_RIGHT,
            TouchControlConfig.MODE_HOLD);
        presser.press(0, spec);
        assertEquals(1, recorder.downs(KeyEvent.KEYCODE_CTRL_RIGHT));
        assertEquals("no synthetic left-Ctrl", 0, recorder.downs(KeyEvent.KEYCODE_CTRL_LEFT));
    }

    @Test
    public void keysForBuildsModifiersThenKey() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_A, TouchControlConfig.MODE_TAP);
        spec.modifiers = TouchKeyCatalog.MOD_ALT | TouchKeyCatalog.MOD_CTRL;
        int[] keys = TouchKeyPresser.keysFor(spec);
        assertEquals(3, keys.length);
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, keys[0]);
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, keys[1]);
        assertEquals(KeyEvent.KEYCODE_A, keys[2]);
    }

    /** Every mode, driven hard, ends with no key down. */
    @Test
    public void noModeLeavesAKeyDownAfterReleaseAll() {
        int[] modes = {TouchControlConfig.MODE_TAP, TouchControlConfig.MODE_HOLD,
            TouchControlConfig.MODE_TOGGLE};
        for (int mode : modes) {
            Recorder local = new Recorder();
            TouchKeyPresser localPresser = new TouchKeyPresser(local, local);
            TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_A, mode);
            for (int i = 0; i < 5; ++i) {
                localPresser.press(0, spec);
                localPresser.release(0, spec);
            }
            local.runPending();
            localPresser.releaseAll();
            assertEquals("mode " + mode + " balances every down with an up",
                local.downs(KeyEvent.KEYCODE_A), local.ups(KeyEvent.KEYCODE_A));
        }
    }

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 A cleared button presses nothing at all:
     * no primary key, no modifiers, and no substitute. keysFor() is empty, so pressKeys() sends no
     * keyDown however the button is driven -- which is what "does not send unintended keyboard
     * input and does not trigger a default key" means at the only place that matters.
     */
    @Test
    public void aClearedButtonPressesNothing() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_UNKNOWN, TouchControlConfig.MODE_TAP);
        assertEquals(0, TouchKeyPresser.keysFor(spec).length);

        Recorder local = new Recorder();
        TouchKeyPresser localPresser = new TouchKeyPresser(local, local);
        localPresser.press(0, spec);
        local.runPending();
        localPresser.release(0, spec);
        local.runPending();
        localPresser.releaseAll();
        assertTrue("nothing was ever sent: " + local.events, local.events.isEmpty());
    }

    /** A cleared button that kept stale modifier bits from a hand-edited file is still inert. */
    @Test
    public void aClearedButtonWithStaleModifiersIsStillInert() {
        TouchControlConfig.ButtonSpec spec = button(KeyEvent.KEYCODE_UNKNOWN, TouchControlConfig.MODE_HOLD);
        spec.modifiers = TouchKeyCatalog.MOD_CTRL | TouchKeyCatalog.MOD_ALT;
        assertEquals("no modifier leaks through", 0, TouchKeyPresser.keysFor(spec).length);
    }
}
