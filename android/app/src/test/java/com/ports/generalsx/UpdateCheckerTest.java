// GeneralsX @build Android port 10/11/2026
//
// UpdateChecker decides whether to tell the player about a newer release, and only after the
// metadata that describes it has been authenticated. Everything that can get that wrong is pure
// Java over bytes and a key and is checked here:
//
//   * the signature gate -- a tampered payload, a foreign key, an unsigned document, a malformed
//     signature must all be refused before any field is read;
//   * the field rules once the signature holds -- the repository, package name, https URL on this
//     repository, size and checksum shapes;
//   * the numeric version comparison (never the versionName);
//   * the "already told them" memory.
//
// Nothing here reaches the network: the tests sign their own payloads with a generated P-256 key
// and feed the bytes straight to parseVerified().

package com.ports.generalsx;

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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

@RunWith(RobolectricTestRunner.class)
public class UpdateCheckerTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static final String SHA =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String APK_URL =
        "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.2.9/app.apk";

    @Before
    public void setUp() {
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
    public void comparisonIsNumericNotLexicographic() {
        assertFalse(UpdateChecker.isNewer(update(999), 10402));
        assertTrue(UpdateChecker.isNewer(update(10402), 999));
    }

    @Test
    public void aNullUpdateIsNeverNewer() {
        assertFalse(UpdateChecker.isNewer(null, 0));
    }

    // ------------------------------------------------------------------ signature gate

    /** A correctly signed document parses and yields the fields it carries. */
    @Test
    public void aSignedDocumentParses() throws Exception {
        KeyPair pair = newEcKeyPair();
        byte[] payload = metadata(10500, "1.5.0", APK_URL, SHA, 123456789L).getBytes(StandardCharsets.UTF_8);
        UpdateChecker.Update update =
            UpdateChecker.parseVerified(payload, sign(pair, payload), pair.getPublic());
        assertNotNull(update);
        assertEquals(10500, update.versionCode);
        assertEquals("1.5.0", update.versionName);
        assertTrue(update.hasApk());
        assertEquals(123456789L, update.apkSizeBytes);
        assertEquals(SHA, update.apkSha256);
    }

    /** A signature from a key that is not the embedded one must not authenticate the document. */
    @Test
    public void aSignatureFromAnotherKeyIsRefused() throws Exception {
        KeyPair signer = newEcKeyPair();
        KeyPair other = newEcKeyPair();
        byte[] payload = metadata(10500, "1.5.0", APK_URL, SHA, 1L).getBytes(StandardCharsets.UTF_8);
        assertNull(UpdateChecker.parseVerified(payload, sign(signer, payload), other.getPublic()));
    }

    /** Any change to the payload after signing must be caught. */
    @Test
    public void aTamperedPayloadIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        byte[] signed = metadata(10500, "1.5.0", APK_URL, SHA, 1L).getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(pair, signed);
        byte[] tampered = metadata(99999, "9.9.9", APK_URL, SHA, 1L).getBytes(StandardCharsets.UTF_8);
        assertNull(UpdateChecker.parseVerified(tampered, signature, pair.getPublic()));
    }

    /** An unsigned document (empty or malformed signature) is refused. */
    @Test
    public void anUnsignedDocumentIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        byte[] payload = metadata(10500, "1.5.0", APK_URL, SHA, 1L).getBytes(StandardCharsets.UTF_8);
        assertNull(UpdateChecker.parseVerified(payload, new byte[0], pair.getPublic()));
        assertNull(UpdateChecker.parseVerified(payload,
            "not base64 !!!".getBytes(StandardCharsets.US_ASCII), pair.getPublic()));
    }

    @Test
    public void verifyRejectsNullOrEmptyInput() {
        assertFalse(UpdateChecker.verify(null, null));
        assertFalse(UpdateChecker.verify(new byte[0], new byte[0]));
    }

    // ------------------------------------------------------------------ field rules after the signature

    @Test
    public void aForeignRepositoryIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        String json = "{\"schemaVersion\":1,\"repository\":\"attacker/repo\",\"versionCode\":10500,"
            + "\"packageName\":\"com.ports.generalsx\",\"apk\":{\"downloadUrl\":\"" + APK_URL
            + "\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":1,"
            + "\"signingCertificateSha256\":\"" + SHA + "\"}}";
        assertNull(parseSigned(pair, json));
    }

    @Test
    public void aWrongPackageNameIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        String json = metadata(10500, "1.5.0", APK_URL, SHA, 1L)
            .replace("com.ports.generalsx", "com.evil.app");
        assertNull(parseSigned(pair, json));
    }

    @Test
    public void anApkUrlOffThisRepositoryIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        assertNull(parseSigned(pair, metadata(10500, "1.5.0",
            "https://evil.example/app.apk", SHA, 1L)));
        assertNull(parseSigned(pair, metadata(10500, "1.5.0",
            "http://github.com/redaf12-Decode/Generals-Android-Port/x", SHA, 1L)));
    }

    @Test
    public void aBadChecksumShapeIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        assertNull(parseSigned(pair, metadata(10500, "1.5.0", APK_URL, "abc", 1L)));
    }

    @Test
    public void aZeroOrNegativeSizeIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        assertNull(parseSigned(pair, metadata(10500, "1.5.0", APK_URL, SHA, 0L)));
    }

    @Test
    public void aBadSchemaVersionIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        assertNull(parseSigned(pair,
            metadata(10500, "1.5.0", APK_URL, SHA, 1L).replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
    }

    @Test
    public void aMissingApkObjectIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        String json = "{\"schemaVersion\":1,\"repository\":\"" + UpdateChecker.REPOSITORY
            + "\",\"versionCode\":10500,\"packageName\":\"com.ports.generalsx\"}";
        assertNull(parseSigned(pair, json));
    }

    @Test
    public void releaseNotesAreSplitIntoLines() throws Exception {
        KeyPair pair = newEcKeyPair();
        String json = metadata(10500, "1.5.0", APK_URL, SHA, 1L)
            .replace("\"releaseNotes\":\"\"", "\"releaseNotes\":\"Fixed bugs.\\nFaster startup.\\n\"");
        UpdateChecker.Update update = parseSigned(pair, json);
        assertNotNull(update);
        assertEquals(java.util.Arrays.asList("Fixed bugs.", "Faster startup."), update.releaseNotes);
    }

    // ------------------------------------------------------------------ URL + checksum shapes

    @Test
    public void onlyThisRepositorysHttpsUrlsAreAccepted() {
        assertTrue(UpdateChecker.isAcceptableApkUrl(APK_URL));
        assertFalse(UpdateChecker.isAcceptableApkUrl(null));
        assertFalse(UpdateChecker.isAcceptableApkUrl(""));
        assertFalse(UpdateChecker.isAcceptableApkUrl("https://github.com/other/repo/x"));
    }

    @Test
    public void sha256ShapeIsSixtyFourHexCharacters() {
        assertTrue(UpdateChecker.isSha256(SHA));
        assertTrue(UpdateChecker.isSha256(SHA.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(UpdateChecker.isSha256(null));
        assertFalse(UpdateChecker.isSha256("abc"));
        assertFalse(UpdateChecker.isSha256(SHA + "0"));
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

    // ------------------------------------------------------------------ installed version

    @Test
    public void installedVersionCodeComesFromThePackage() {
        assertTrue(UpdateChecker.installedVersionCode(app()) >= 0);
    }

    // ------------------------------------------------------------------ helpers

    private static UpdateChecker.Update parseSigned(KeyPair pair, String json) throws Exception {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return UpdateChecker.parseVerified(payload, sign(pair, payload), pair.getPublic());
    }

    private static String metadata(int code, String name, String url, String sha, long size) {
        return "{\"schemaVersion\":1,\"repository\":\"" + UpdateChecker.REPOSITORY + "\","
            + "\"releaseTag\":\"v1.5.0\",\"versionName\":\"" + name + "\","
            + "\"versionCode\":" + code + ",\"packageName\":\"com.ports.generalsx\","
            + "\"releaseUrl\":\"https://github.com/" + UpdateChecker.REPOSITORY + "/releases/tag/v1.5.0\","
            + "\"apk\":{\"fileName\":\"app.apk\",\"downloadUrl\":\"" + url + "\","
            + "\"sizeBytes\":" + size + ",\"sha256\":\"" + sha + "\","
            + "\"signingCertificateSha256\":\"" + SHA + "\"},"
            + "\"minSupportedVersionCode\":0,\"releaseNotes\":\"\"}";
    }

    private static KeyPair newEcKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] sign(KeyPair pair, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(data);
        return Base64.getEncoder().encode(signer.sign());
    }

    private static UpdateChecker.Update update(int versionCode) {
        return new UpdateChecker.Update(versionCode, "1.0.0", java.util.Collections.emptyList(),
            "com.ports.generalsx", "", "", 0L, "");
    }
}
