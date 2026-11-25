package com.dinemaster.auth.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EdgeHostProofTest {
    @Test
    void acceptsSignatureBoundToActivationNonceAndPublicKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded()));
        String message = EdgeHostProof.proofMessage("activation-1", "nonce-1", fingerprint);
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        assertEquals(fingerprint, EdgeHostProof.verifyAndFingerprint("activation-1", "nonce-1", pem, signature));
        assertThrows(IllegalArgumentException.class, () -> EdgeHostProof.verifyAndFingerprint("activation-2", "nonce-1", pem, signature));
    }

    @Test
    void rejectsMalformedOrWeakKeysAndInvalidSignature() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> EdgeHostProof.verifyAndFingerprint("a", "n", "bad", "bad"));
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(1024);
        var weak = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(weak.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        assertThrows(IllegalArgumentException.class, () -> EdgeHostProof.verifyAndFingerprint("a", "n", pem, "AA=="));
    }
}
