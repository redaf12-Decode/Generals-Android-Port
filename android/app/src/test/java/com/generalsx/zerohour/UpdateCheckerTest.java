// GeneralsX @build Android port 09/11/2026
//
// UpdateChecker decides whether to tell the player about a newer release. Everything that can get
// that wrong is pure Java over a string and an int and is checked here: the version comparison
// (numeric, never the versionName), the JSON parsing (untrusted input, so the version code must be
// a plain non-negative integer and malformed input must not throw), and the "already told them"
// memory in SharedPreferences that stops the dialog reappearing on every launch.
//
// Nothing here reaches the network: parse() is fed the JSON text directly, and the installed
// version code is read from the test application's own package in Robolectric.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class UpdateCheckerTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void setUp() {
        // The check runs at most once per process; the tests below never call check(), but keep the
        // stored notification memory clean so each test starts from "nothing shown yet".
        UpdateChecker.markNotified(app(), 0);
    }

    // ------------------------------------------------------------------ version comparison

    @Test
    public void remoteNewerThanInstalledOffersAnUpdate() {
        assertTrue(UpdateChecker.isNewer(update(10500), 10402));
    }

    @Test
    public void sameVersionOffersNothing() {
        assertFalse(UpdateChecker.isNewer(update(10500), 10500));
    }

    @Test
    public void olderRemoteOffersNothing() {
        assertFalse(UpdateChecker.isNewer(update(10402), 10500));
    }

    @Test
    public void theSmallestNewerVersionCounts() {
        assertTrue(UpdateChecker.isNewer(update(10403), 10402));
    }

    /** The comparison is numeric, so a string-tagged code must be compared as a number too. */
    @Test
    public void comparisonIsNumericNotLexicographic() {
        // "999" > "10402" lexicographically, but 999 < 10402 numerically: no update.
        assertFalse(UpdateChecker.isNewer(update(999), 10402));
        // And the reverse: a plain number larger than the installed code is an update.
        assertTrue(UpdateChecker.isNewer(update(10402), 999));
    }

    @Test
    public void aNullUpdateIsNeverNewer() {
        assertFalse(UpdateChecker.isNewer(null, 0));
    }

    // ------------------------------------------------------------------ parsing

    @Test
    public void parsesAnIntegerVersionCodeAndNotes() {
        UpdateChecker.Update update = UpdateChecker.parse(
            "{\"versionCode\":10500,\"versionName\":\"1.5.0\","
            + "\"releaseNotes\":[\"Fixed bugs.\",\"Faster startup.\"]}");
        assertNotNull(update);
        assertEquals(10500, update.versionCode);
        assertEquals("1.5.0", update.versionName);
        assertEquals(Arrays.asList("Fixed bugs.", "Faster startup."), update.releaseNotes);
    }

    /** The spec allows the version code as a numeric string as well as a JSON number. */
    @Test
    public void parsesAStringVersionCode() {
        UpdateChecker.Update update = UpdateChecker.parse("{\"versionCode\":\"10500\"}");
        assertNotNull(update);
        assertEquals(10500, update.versionCode);
    }

    @Test
    public void missingVersionNameIsEmptyNotAnError() {
        UpdateChecker.Update update = UpdateChecker.parse("{\"versionCode\":10500}");
        assertNotNull(update);
        assertEquals("", update.versionName);
        assertTrue(update.releaseNotes.isEmpty());
    }

    /** A single note is allowed; every string entry is kept in order. */
    @Test
    public void keepsEveryReleaseNoteInOrder() {
        UpdateChecker.Update update = UpdateChecker.parse(
            "{\"versionCode\":1,\"releaseNotes\":[\"one\",\"two\",\"three\"]}");
        assertEquals(Arrays.asList("one", "two", "three"), update.releaseNotes);
    }

    @Test
    public void missingVersionCodeIsRefused() {
        assertNull(UpdateChecker.parse("{\"versionName\":\"1.5.0\"}"));
    }

    @Test
    public void negativeVersionCodeIsRefused() {
        assertNull(UpdateChecker.parse("{\"versionCode\":-1}"));
        assertNull(UpdateChecker.parse("{\"versionCode\":\"-1\"}"));
    }

    /** Only a plain non-negative integer: a decimal, an exponent or a sign is not a version. */
    @Test
    public void nonIntegerVersionCodesAreRefused() {
        assertNull(UpdateChecker.parse("{\"versionCode\":1.5}"));
        assertNull(UpdateChecker.parse("{\"versionCode\":\"1.5.0\"}"));
        assertNull(UpdateChecker.parse("{\"versionCode\":\"1e3\"}"));
        assertNull(UpdateChecker.parse("{\"versionCode\":\"10abc\"}"));
        assertNull(UpdateChecker.parse("{\"versionCode\":\"\"}"));
    }

    @Test
    public void aVersionCodePastIntegerMaxIsRefused() {
        assertNull(UpdateChecker.parse("{\"versionCode\":99999999999999}"));
    }

    @Test
    public void malformedJsonIsRefusedWithoutThrowing() {
        assertNull(UpdateChecker.parse("not json at all"));
        assertNull(UpdateChecker.parse(""));
        assertNull(UpdateChecker.parse("[1,2,3]"));
        assertNull(UpdateChecker.parse(null));
    }

    /** A non-string entry in releaseNotes is skipped, and blank notes are dropped. */
    @Test
    public void releaseNotesIgnoreNonStringsAndBlanks() {
        UpdateChecker.Update update = UpdateChecker.parse(
            "{\"versionCode\":1,\"releaseNotes\":[\"kept\",5,\"  \",null,\"also kept\"]}");
        assertEquals(Arrays.asList("kept", "also kept"), update.releaseNotes);
    }

    @Test
    public void releaseNotesOfTheWrongTypeIsNotAnError() {
        UpdateChecker.Update update = UpdateChecker.parse(
            "{\"versionCode\":1,\"releaseNotes\":\"a string, not an array\"}");
        assertNotNull(update);
        assertTrue(update.releaseNotes.isEmpty());
    }

    // ------------------------------------------------------------------ notification memory

    @Test
    public void nothingHasBeenNotifiedByDefault() {
        assertEquals(0, UpdateChecker.notifiedVersionCode(app()));
        assertTrue(UpdateChecker.shouldNotify(app(), update(10500)));
    }

    @Test
    public void aNotifiedVersionIsNotShownAgain() {
        UpdateChecker.markNotified(app(), 10500);
        assertFalse(UpdateChecker.shouldNotify(app(), update(10500)));
    }

    @Test
    public void aNewerVersionNotifiesEvenAfterAnOlderOneWasShown() {
        UpdateChecker.markNotified(app(), 10500);
        assertTrue(UpdateChecker.shouldNotify(app(), update(10501)));
    }

    @Test
    public void shouldNotifyIsFalseForANullUpdate() {
        assertFalse(UpdateChecker.shouldNotify(app(), null));
    }

    // ------------------------------------------------------------------ installed version

    /** Read from the package, so it must at least resolve to a non-negative number here. */
    @Test
    public void installedVersionCodeComesFromThePackage() {
        assertTrue(UpdateChecker.installedVersionCode(app()) >= 0);
    }

    private static UpdateChecker.Update update(int versionCode) {
        return new UpdateChecker.Update(versionCode, "1.0.0", java.util.Collections.emptyList());
    }
}
