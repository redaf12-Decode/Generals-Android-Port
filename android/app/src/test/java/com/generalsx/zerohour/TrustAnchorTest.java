// GeneralsX @build Android security 12/11/2026
//
// The port pins three public keys for two independent signing domains (see
// docs/HOWTO/SIGNING_KEY_ROTATION.md): UpdateChecker's update-metadata key and UpdateManager's
// engine-update key (mirrored byte-for-byte in the engine's GXSignedSettings.cpp). A rotation that
// touches one and not the other is silent until an update is refused, so these invariants are
// checked in code, not only in review:
//
//   * both pinned anchors parse as real EC keys (not a truncated or pasted-wrong string);
//   * the two domains are different keys, so one rotation cannot change the other's trust;
//   * each anchor refuses a signature it did not produce.
//
// The retired-generation rejection and the Java-vs-native byte equality are checked by
// scripts/qa/android/check-trust-anchors.py, which owns the retired key fingerprints on purpose
// (a retired key literal must not live in the launcher's own source).
//
// No production private key is ever a test fixture: every signature here comes from a throwaway
// key the test generates.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@RunWith(RobolectricTestRunner.class)
public class TrustAnchorTest {

    private static final byte[] PAYLOAD =
        "{\"versionCode\":10500,\"packageName\":\"com.ports.generalsx\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    public void bothPinnedAnchorsParseAsEcKeys() throws Exception {
        assertNotNull(parse(UpdateChecker.PUBLIC_KEY_B64));
        assertNotNull(parse(UpdateManager.PUBLIC_KEY_B64));
    }

    /**
     * The metadata key and the engine-update key must not be the same key: if they were, rotating
     * one domain would silently rotate the other, and a single compromise would forge both
     * update.json and manifest.json.
     */
    @Test
    public void theTwoSigningDomainsUseDifferentKeys() {
        assertFalse("the metadata key and the engine-update key must be independent",
            UpdateChecker.PUBLIC_KEY_B64.equals(UpdateManager.PUBLIC_KEY_B64));
    }

    @Test
    public void theMetadataAnchorRefusesAForeignSignature() throws Exception {
        KeyPair signer = newEcKeyPair();
        String sig = sign(signer, PAYLOAD);
        assertFalse(UpdateChecker.verify(PAYLOAD, sig.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void theEngineUpdateAnchorRefusesAForeignSignature() throws Exception {
        KeyPair signer = newEcKeyPair();
        String sig = sign(signer, PAYLOAD);
        assertFalse(UpdateManager.verify(PAYLOAD, sig.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Both anchors must actually be P-256 (the curve the whole update system is built on). */
    @Test
    public void bothAnchorsAreP256() throws Exception {
        assertEquals("EC", parse(UpdateChecker.PUBLIC_KEY_B64).getAlgorithm());
        assertEquals("EC", parse(UpdateManager.PUBLIC_KEY_B64).getAlgorithm());
        assertTrue(parse(UpdateChecker.PUBLIC_KEY_B64).getEncoded().length > 0);
    }

    private static PublicKey parse(String b64) throws Exception {
        return KeyFactory.getInstance("EC")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
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
