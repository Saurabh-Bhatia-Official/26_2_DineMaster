package com.dinemaster.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** Cryptographic proof helper for the isolated Edge host enrollment flow. */
public final class EdgeHostProof {
    private EdgeHostProof() { }

    public static String verifyAndFingerprint(String activationId, String nonce, String publicKeyPem,
            String proofSignatureBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyPem.replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "").replaceAll("\\s", ""));
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048)
                throw new IllegalArgumentException("RSA public key must be at least 2048 bits");
            String thumbprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
            String message = "dinemaster-edge-host-activation:v1\n" + activationId + "\n" + nonce + "\n" + thumbprint;
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(proofSignatureBase64)))
                throw new IllegalArgumentException("Invalid key proof");
            return thumbprint;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid key proof", e); }
    }

    public static String proofMessage(String activationId, String nonce, String publicKeySha256) {
        return "dinemaster-edge-host-activation:v1\n" + activationId + "\n" + nonce + "\n" + publicKeySha256;
    }
}
