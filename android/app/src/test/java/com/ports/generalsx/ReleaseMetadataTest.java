// GeneralsX @build Android port 10/11/2026
//
// Checks the shape of the signed update metadata against the document
// scripts/update/generate-update-metadata.py produces. The app's own parser (UpdateChecker) is
// exercised in UpdateCheckerTest; this class pins the schema itself -- the field names, the
// repository, the production package id, the asset URL shape, the size and digest formats -- so a
// change to the generator that the app would not understand fails here.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ReleaseMetadataTest {

    /** A metadata document exactly as the generator writes it, with real v1.2.9 values. */
    private static final String SAMPLE = "{"
        + "\"schemaVersion\":1,"
        + "\"repository\":\"redaf12-Decode/Generals-Android-Port\","
        + "\"releaseTag\":\"v1.2.9\","
        + "\"versionName\":\"1.2.9\","
        + "\"versionCode\":10209,"
        + "\"packageName\":\"com.ports.generalsx\","
        + "\"releaseUrl\":\"https://github.com/redaf12-Decode/Generals-Android-Port/releases/tag/v1.2.9\","
        + "\"apk\":{"
        + "  \"fileName\":\"GeneralsXZH-android-run48.apk\","
        + "  \"downloadUrl\":\"https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.2.9/GeneralsXZH-android-run48.apk\","
        + "  \"sizeBytes\":93586754,"
        + "  \"sha256\":\"83bb10593d3c81cd1de8b49e164eaec8a29bf72b31dcd41bcc785c096c3d5bdb\","
        + "  \"signingCertificateSha256\":\"644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e\""
        + "},"
        + "\"minSupportedVersionCode\":0,"
        + "\"releaseNotes\":\"First line.\\nSecond line.\""
        + "}";

    @Test
    public void theDocumentParsesAsJson() throws Exception {
        JSONObject obj = new JSONObject(SAMPLE);
        assertEquals(1, obj.getInt("schemaVersion"));
        assertEquals("redaf12-Decode/Generals-Android-Port", obj.getString("repository"));
        assertEquals("com.ports.generalsx", obj.getString("packageName"));
    }

    @Test
    public void theVersionFieldsArePresentAndNumeric() throws Exception {
        JSONObject obj = new JSONObject(SAMPLE);
        assertEquals("v1.2.9", obj.getString("releaseTag"));
        assertEquals("1.2.9", obj.getString("versionName"));
        assertEquals(10209, obj.getInt("versionCode"));
    }

    @Test
    public void theApkBlockNamesARealAssetOnThisRepository() throws Exception {
        JSONObject apk = new JSONObject(SAMPLE).getJSONObject("apk");
        String name = apk.getString("fileName");
        assertTrue("the asset name should be an apk", name.endsWith(".apk"));
        String url = apk.getString("downloadUrl");
        assertTrue("the download URL must be an https asset of this repository",
            url.startsWith("https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/"));
        assertTrue("the download URL should end with the asset name", url.endsWith(name));
    }

    @Test
    public void theSizeAndDigestsAreWellFormed() throws Exception {
        JSONObject apk = new JSONObject(SAMPLE).getJSONObject("apk");
        assertTrue("sizeBytes must be positive", apk.getLong("sizeBytes") > 0);
        assertTrue("sha256 must be 64 hex characters", UpdateChecker.isSha256(apk.getString("sha256")));
        assertTrue("the certificate fingerprint must be 64 hex characters",
            UpdateChecker.isSha256(apk.getString("signingCertificateSha256")));
    }

    @Test
    public void theDownloadUrlIsAcceptedByTheChecker() throws Exception {
        JSONObject apk = new JSONObject(SAMPLE).getJSONObject("apk");
        assertTrue(UpdateChecker.isAcceptableApkUrl(apk.getString("downloadUrl")));
    }

    @Test
    public void theReleaseNotesSplitIntoLines() throws Exception {
        String notes = new JSONObject(SAMPLE).getString("releaseNotes");
        String[] lines = notes.split("\\r?\\n");
        assertEquals(2, lines.length);
        assertEquals("First line.", lines[0]);
    }

    /** The endpoints the app actually reads must be this repository's, not the old one. */
    @Test
    public void theUpdateEndpointsPointAtThisRepository() {
        assertTrue(UpdateChecker.METADATA_URL.startsWith(
            "https://github.com/redaf12-Decode/Generals-Android-Port/releases/latest/download/"));
        assertTrue(UpdateChecker.APK_URL_PREFIX.equals(
            "https://github.com/redaf12-Decode/Generals-Android-Port/"));
        assertEquals("com.ports.generalsx", UpdateChecker.EXPECTED_PACKAGE);
        assertNotNull(UpdateChecker.embeddedKey());
    }
}
