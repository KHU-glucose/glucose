package com.glucoselog.auth;

import jakarta.validation.constraints.NotBlank;

public record AppleLoginRequest(@NotBlank String identityToken, String nonce) {
}
