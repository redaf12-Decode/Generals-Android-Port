// GeneralsX @build Android port 09/11/2026
//
// The pieces of ApkInstaller that are pure enough to test without a device: the SHA-256 streaming
// digest (the check that catches a corrupted or tampered download), the hex encoder it compares
// with, the failure-to-message mapping every error screen goes through, and the two cheap guards
// at the top of verify() (an absent or empty file is never a valid APK). The download itself and
// the certificate comparison need a real network and a real signed APK, and are left to on-device
// checks; nothing here reaches the network.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
public class ApkInstallerTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    /** The published digest of "abc" -- a fixed vector, so the streaming path is checked, not just
     *  "it produced 64 characters". */
    @Test
    public void sha256MatchesAKnownVector() throws Exception {
        File f = File.createTempFile("gx-sha", ".bin", app().getCacheDir());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("abc".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkInstaller.sha256(f));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** A file larger than the 64 KiB read buffer, so the loop runs more than once. */
    @Test
    public void sha256HandlesMoreThanOneBuffer() throws Exception {
        byte[] big = new byte[200 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        File f = File.createTempFile("gx-sha-big", ".bin", app().getCacheDir());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(big);
        }
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        assertEquals(ApkInstaller.toHex(md.digest(big)), ApkInstaller.sha256(f));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Test
    public void toHexIsLowercaseAndPadded() {
        assertEquals("", ApkInstaller.toHex(new byte[0]));
        assertEquals("00", ApkInstaller.toHex(new byte[] { 0 }));
        assertEquals("0f", ApkInstaller.toHex(new byte[] { 15 }));
        assertEquals("ff", ApkInstaller.toHex(new byte[] { (byte) 0xFF }));
        assertEquals("000102ff", ApkInstaller.toHex(new byte[] { 0, 1, 2, (byte) 0xFF }));
    }

    /** An empty or absent file is rejected before any expensive work. */
    @Test
    public void verifyRejectsAnEmptyOrMissingFile() throws Exception {
        UpdateChecker.Update update = update(1, 0L);
        File empty = File.createTempFile("gx-empty", ".apk", app().getCacheDir());
        assertEquals(ApkInstaller.ERR_INVALID_APK, ApkInstaller.verify(app(), update, empty));
        assertEquals(ApkInstaller.ERR_INVALID_APK,
            ApkInstaller.verify(app(), update, new File(app().getCacheDir(), "does-not-exist.apk")));
        //noinspection ResultOfMethodCallIgnored
        empty.delete();
    }

    /** A wrong size is reported as a size failure, before the (also wrong) checksum is reached. */
    @Test
    public void verifyReportsASizeMismatch() throws Exception {
        UpdateChecker.Update update = update(1, 999999L);
        File f = File.createTempFile("gx-size", ".apk", app().getCacheDir());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[16]);
        }
        assertEquals(ApkInstaller.ERR_SIZE, ApkInstaller.verify(app(), update, f));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** An Update as the signed metadata would produce it, for the pure verify() checks. */
    private static UpdateChecker.Update update(int versionCode, long size) {
        return new UpdateChecker.Update(versionCode, "1.2.9", java.util.Collections.emptyList(),
            UpdateChecker.EXPECTED_PACKAGE,
            "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.2.9/app.apk",
            "0000000000000000000000000000000000000000000000000000000000000000",
            size, "644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e");
    }

    /** Every failure code must resolve to a non-empty, localized string. */
    @Test
    public void everyFailureCodeHasAMessage() {
        String[] codes = {
            ApkInstaller.ERR_NO_APK, ApkInstaller.ERR_URL, ApkInstaller.ERR_OFFLINE,
            ApkInstaller.ERR_HTTP, ApkInstaller.ERR_STORAGE, ApkInstaller.ERR_SIZE,
            ApkInstaller.ERR_CHECKSUM, ApkInstaller.ERR_INVALID_APK, ApkInstaller.ERR_PACKAGE,
            ApkInstaller.ERR_VERSION, ApkInstaller.ERR_SIGNATURE, "some-unknown-code",
        };
        for (String code : codes) {
            String message = ApkInstaller.message(app(), code);
            assertNotNull(message);
            assertTrue("no message for " + code, message.length() > 0);
        }
    }
}
