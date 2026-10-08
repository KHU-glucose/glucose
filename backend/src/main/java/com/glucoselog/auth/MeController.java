package com.glucoselog.auth;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.glucoselog.account.AccountDeletionService;

@RestController
@RequestMapping("/v1/me")
public class MeController {

    private final AccountDeletionService accountDeletionService;

    public MeController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteMe(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        accountDeletionService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
