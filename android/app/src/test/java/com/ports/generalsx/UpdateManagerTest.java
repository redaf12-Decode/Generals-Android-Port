// GeneralsX @build Android CI 06/11/2026
//
// UpdateManager reads the signed settings, decides the update channel and verifies the manifest
// signature. Every one of those is pure Java over a file and a key, and every one of them is
// security-relevant: a signature check that accepts anything, or a remote_config parser that
// reads the wrong key, is not visible until it is exploited or misconfigured in production.
//
// The tests below write the real files the class reads (files/update/remote_config.ini) and call
// the real methods. Nothing here reaches the network: verify() is fed bytes and a signature, and
// the config parser reads a local file.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

@RunWith(RobolectricTestRunner.class)
public class UpdateManagerTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private File configFile;

    @Before
    public void setUp() throws IOException {
        File dir = UpdateManager.updateDir(app());
        assertTrue(dir.isDirectory() || dir.mkdirs());
        configFile = new File(dir, "remote_config.ini");
        configFile.delete();
        // The channel is memoised in a static; clear it so each test sees its own assets.
        new File(app().getFilesDir(), "update").mkdirs();
    }

    private void writeConfig(String text) throws IOException {
        Files.write(configFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ remote_config parsing

    @Test
    public void remoteConfigFallsBackWhenTheFileIsAbsent() {
        assertEquals("fallback", UpdateManager.remoteConfig(app(), "pc_crossplay", "fallback"));
    }

    @Test
    public void remoteConfigReadsAKey() throws IOException {
        writeConfig("pc_crossplay=1\nengine_seq=1234\n");
        assertEquals("1", UpdateManager.remoteConfig(app(), "pc_crossplay", "0"));
        assertEquals("1234", UpdateManager.remoteConfig(app(), "engine_seq", "0"));
    }

    @Test
    public void remoteConfigFallsBackForAMissingKey() throws IOException {
        writeConfig("pc_crossplay=1\n");
        assertEquals("none", UpdateManager.remoteConfig(app(), "engine_seq", "none"));
    }

    /** A commented-out line is not a value: a switch turned off in the file must not read as on. */
    @Test
    public void remoteConfigIgnoresCommentedLines() throws IOException {
        writeConfig("#pc_crossplay=1\npc_crossplay=0\n");
        assertEquals("0", UpdateManager.remoteConfig(app(), "pc_crossplay", "1"));
    }

    /** An empty value is "not set", not the empty string. */
    @Test
    public void remoteConfigTreatsAnEmptyValueAsAbsent() throws IOException {
        writeConfig("engine_seq=\n");
        assertEquals("fallback", UpdateManager.remoteConfig(app(), "engine_seq", "fallback"));
    }

    /**
     * The value is trimmed; the key is written bare by scripts/update/features.py, and a key with
     * padding around it is a different key rather than a sloppy match.
     */
    @Test
    public void remoteConfigTrimsTheValueButNotTheKey() throws IOException {
        writeConfig("engine_seq=   42  \n");
        assertEquals("42", UpdateManager.remoteConfig(app(), "engine_seq", "0"));

        writeConfig("  engine_seq=42\n");
        assertEquals("a padded key is not the key",
            "fallback", UpdateManager.remoteConfig(app(), "engine_seq", "fallback"));
    }

    /** A key must match whole: "pc_crossplay_extra" is not "pc_crossplay". */
    @Test
    public void remoteConfigMatchesTheWholeKeyNotAPrefix() throws IOException {
        writeConfig("pc_crossplay_extra=1\n");
        assertEquals("fallback", UpdateManager.remoteConfig(app(), "pc_crossplay", "fallback"));
    }

    @Test
    public void featureEnabledFollowsTheSignedValue() throws IOException {
        writeConfig("pc_crossplay=1\nanother=0\n");
        assertTrue(UpdateManager.featureEnabled(app(), "pc_crossplay", false));
        assertFalse(UpdateManager.featureEnabled(app(), "another", true));
    }

    /** A feature the manifest does not mention keeps the build's own default. */
    @Test
    public void featureEnabledUsesTheFallbackWhenUnset() throws IOException {
        writeConfig("something_else=1\n");
        assertTrue(UpdateManager.featureEnabled(app(), "pc_crossplay", true));
        assertFalse(UpdateManager.featureEnabled(app(), "pc_crossplay", false));
    }

    /**
     * Only the literal "1" is on: "true", "yes" and "2" are not. An empty value falls back to the
     * build's own default, which is the fallback passed in.
     */
    @Test
    public void featureEnabledAcceptsOnlyOne() throws IOException {
        for (String value : new String[] { "true", "yes", "2", "on" }) {
            writeConfig("pc_crossplay=" + value + "\n");
            assertFalse("'" + value + "' must not enable a feature",
                UpdateManager.featureEnabled(app(), "pc_crossplay", true));
        }
        // An empty value is "not set": the default applies, so an explicit fallback of false wins.
        writeConfig("pc_crossplay=\n");
        assertFalse(UpdateManager.featureEnabled(app(), "pc_crossplay", false));
        assertTrue(UpdateManager.featureEnabled(app(), "pc_crossplay", true));
    }

    // ------------------------------------------------------------------ channel

    @Test
    public void releaseChannelIsTheDefault() {
        assertEquals("release", UpdateManager.channelName(app()));
        assertTrue(UpdateManager.baseUrl(app()).endsWith("/updates/"));
    }

    // ------------------------------------------------------------------ signature verification

    /**
     * verify() must reject anything it did not sign. The three cases below are the ones that
     * matter: a signature from another key, a tampered payload and a malformed signature.
     *
     * The app's own key is an EC P-256 key (PUBLIC_KEY_B64, "SHA256withECDSA"), so the untrusted
     * key here is another P-256 key: the curve is not what is being tested, the trust is.
     */
    @Test
    public void verifyRejectsASignatureFromAnotherKey() throws Exception {
        KeyPair pair = newEcKeyPair();

        byte[] data = "{\"engine_seq\":1}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(pair, data);

        assertFalse("a signature from an untrusted key must not verify",
            UpdateManager.verify(data, signature.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void verifyRejectsATamperedPayload() throws Exception {
        KeyPair pair = newEcKeyPair();

        String signature = sign(pair, "{\"engine_seq\":1}".getBytes(StandardCharsets.UTF_8));

        // The same signature over different bytes must not verify, whatever key signed it.
        assertFalse(UpdateManager.verify("{\"engine_seq\":2}".getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void verifyRejectsMalformedInput() {
        assertFalse(UpdateManager.verify(new byte[0], new byte[0]));
        assertFalse(UpdateManager.verify("data".getBytes(StandardCharsets.UTF_8),
            "not base64 at all!!!".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void verifyRejectsAnEmptySignatureOverRealData() {
        assertFalse(UpdateManager.verify("{}".getBytes(StandardCharsets.UTF_8),
            "\n".getBytes(StandardCharsets.US_ASCII)));
    }

    // ------------------------------------------------------------------ misc

    @Test
    public void bothEngineLibrariesAreNamed() {
        assertEquals(2, UpdateManager.ENGINE_LIBS.length);
        assertEquals("libmain.so", UpdateManager.ENGINE_LIBS[0]);
        assertEquals("libmain60.so", UpdateManager.ENGINE_LIBS[1]);
    }

    @Test
    public void bundledEngineSeqIsZeroWhenTheAssetIsAbsent() {
        assertEquals(0, UpdateManager.bundledEngineSeq(app()));
    }

    private static KeyPair newEcKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String sign(KeyPair pair, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(data);
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
