package com.glucoselog.auth;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteMe(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        authService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
