package com.dinemaster.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EdgeHostActivationRequest(
        @NotBlank @Size(max = 100) String activationCode,
        @NotBlank @Size(max = 8192) String publicKeyPem,
        @NotBlank @Size(max = 200) String nonce,
        @NotBlank @Size(max = 1024) String proofSignatureBase64) { }
