// GeneralsX @build Android port 07/11/2026
//
// The mouse_mode marker is the only channel between the launcher's mouse settings and the engine
// (GXMouseMode.h reads it at startup), and its second line -- the physical-mouse capture flag -- has
// to stay readable by a marker written before that line existed. These are the real SetupActivity
// helpers over a real file, so the compatibility rule and the round trip are checked without a
// device and without a native library.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class MouseCaptureMarkerTest {

    private static File newMarker() throws IOException {
        Context app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "mouse-marker-" + System.nanoTime());
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return new File(dir, SetupActivity.MOUSE_MODE_MARKER);
    }

    private static void write(File marker, String content) throws IOException {
        Files.write(marker.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void captureIsOnWhenTheMarkerIsWrittenWithItOn() throws IOException {
        File marker = newMarker();
        SetupActivity.writeMouseMode(marker, true, 120, true);

        assertTrue(marker.isFile());
        assertEquals(120, SetupActivity.readMouseCursorPercent(marker));
        assertTrue(SetupActivity.readMouseCaptureEnabled(marker));
    }

    @Test
    public void captureIsOffWhenTheMarkerIsWrittenWithItOff() throws IOException {
        File marker = newMarker();
        SetupActivity.writeMouseMode(marker, true, 100, false);

        assertTrue(marker.isFile());
        assertFalse(SetupActivity.readMouseCaptureEnabled(marker));
    }

    @Test
    public void cursorSizeSurvivesTogglingCapture() throws IOException {
        File marker = newMarker();
        SetupActivity.writeMouseMode(marker, true, 70, true);
        // What the capture switch's listener writes: the slider's value, the new flag.
        SetupActivity.writeMouseMode(marker, true, SetupActivity.readMouseCursorPercent(marker), false);

        assertEquals(70, SetupActivity.readMouseCursorPercent(marker));
        assertFalse(SetupActivity.readMouseCaptureEnabled(marker));
    }

    @Test
    public void aMarkerFromBeforeTheFlagReadsAsCaptureOn() throws IOException {
        // A marker written by an older launcher carries only the cursor percent.
        File marker = newMarker();
        write(marker, "150");

        assertEquals(150, SetupActivity.readMouseCursorPercent(marker));
        assertTrue("a connected mouse should work without any setup", SetupActivity.readMouseCaptureEnabled(marker));
    }

    @Test
    public void captureOffMarkerKeepsTheFlagOff() throws IOException {
        File marker = newMarker();
        write(marker, "100\n0\n");

        assertFalse(SetupActivity.readMouseCaptureEnabled(marker));
    }

    @Test
    public void disablingTheModeDeletesTheMarker() throws IOException {
        File marker = newMarker();
        SetupActivity.writeMouseMode(marker, true, 100, true);
        assertTrue(marker.isFile());

        SetupActivity.writeMouseMode(marker, false, 100, true);
        assertFalse(marker.isFile());
    }

    @Test
    public void cursorSizeIsClampedAndSnappedWhenRead() throws IOException {
        File marker = newMarker();
        write(marker, "999\n1\n");
        assertEquals(200, SetupActivity.readMouseCursorPercent(marker));

        write(marker, "10\n1\n");
        assertEquals(50, SetupActivity.readMouseCursorPercent(marker));

        write(marker, "104\n1\n");
        assertEquals(100, SetupActivity.readMouseCursorPercent(marker));
    }

    @Test
    public void aMissingMarkerReadsAsTheDefaults() {
        File marker = new File(RuntimeEnvironment.getApplication().getCacheDir(), "no-such-mouse-marker");

        assertEquals(100, SetupActivity.readMouseCursorPercent(marker));
        assertTrue(SetupActivity.readMouseCaptureEnabled(marker));
    }
}
