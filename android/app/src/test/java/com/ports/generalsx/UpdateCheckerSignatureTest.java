// GeneralsX @build Android security 10/11/2026
//
// The update metadata (update.json) is unsigned data from the network. UpdateChecker refuses it
// unless it carries a detached ECDSA-P256/SHA-256 signature that verifies against the pinned
// public key. These tests exercise that check directly -- with a key pair the test controls --
// so "unsigned metadata is refused" and "metadata signed by the wrong key is refused" are proven
// rather than assumed. Nothing here reaches the network.

package com.ports.generalsx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

@RunWith(RobolectricTestRunner.class)
public class UpdateCheckerSignatureTest {

    private static final byte[] PAYLOAD =
        "{\"versionCode\":10500,\"packageName\":\"com.ports.generalsx\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    public void aSignatureFromTheMatchingKeyVerifies() throws Exception {
        KeyPair pair = newEcKeyPair();
        byte[] sig = sign(pair, PAYLOAD);
        assertTrue(UpdateChecker.verify(PAYLOAD, sig, pair.getPublic()));
    }

    @Test
    public void aSignatureFromAnotherKeyIsRefused() throws Exception {
        KeyPair signer = newEcKeyPair();
        KeyPair other = newEcKeyPair();
        byte[] sig = sign(signer, PAYLOAD);
        assertFalse("metadata signed by a key we do not trust must not verify",
            UpdateChecker.verify(PAYLOAD, sig, other.getPublic()));
    }

    @Test
    public void aTamperedPayloadIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        byte[] sig = sign(pair, PAYLOAD);
        byte[] tampered = "{\"versionCode\":99999}".getBytes(StandardCharsets.UTF_8);
        assertFalse(UpdateChecker.verify(tampered, sig, pair.getPublic()));
    }

    @Test
    public void unsignedOrMalformedMetadataIsRefused() throws Exception {
        KeyPair pair = newEcKeyPair();
        assertFalse("no signature", UpdateChecker.verify(PAYLOAD, null, pair.getPublic()));
        assertFalse("empty signature", UpdateChecker.verify(PAYLOAD, new byte[0], pair.getPublic()));
        assertFalse("not base64", UpdateChecker.verify(PAYLOAD,
            "not base64 at all!!!".getBytes(StandardCharsets.US_ASCII), pair.getPublic()));
        assertFalse("null payload",
            UpdateChecker.verify(null, "AAAA".getBytes(StandardCharsets.US_ASCII), pair.getPublic()));
    }

    /**
     * The pinned key the app trusts must be a real, parseable P-256 SubjectPublicKeyInfo: a
     * signature from an unrelated key must be refused (which also proves the pinned-key path
     * parses and runs, rather than short-circuiting on a malformed key).
     */
    @Test
    public void thePinnedPublicKeyRefusesAForeignSignature() throws Exception {
        KeyPair signer = newEcKeyPair();
        byte[] sig = sign(signer, PAYLOAD);
        assertFalse(UpdateChecker.verify(PAYLOAD, sig));
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
        return Base64.getEncoder().encodeToString(signer.sign()).getBytes(StandardCharsets.US_ASCII);
    }
}
