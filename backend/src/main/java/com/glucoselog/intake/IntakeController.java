package com.glucoselog.intake;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.glucoselog.common.PageResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/intakes")
public class IntakeController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final IntakeService intakeService;

    public IntakeController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IntakeResponse create(@Valid @RequestBody IntakeRequest request, Authentication authentication) {
        return intakeService.create(userId(authentication), request);
    }

    @GetMapping
    public PageResponse<IntakeResponse> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        int effectiveLimit = Math.min(Math.max(limit != null ? limit : DEFAULT_LIMIT, 1), MAX_LIMIT);
        return intakeService.list(userId(authentication), cursor, effectiveLimit);
    }

    @GetMapping("/{intakeId}")
    public IntakeResponse get(@PathVariable UUID intakeId, Authentication authentication) {
        return intakeService.get(userId(authentication), intakeId);
    }

    @PatchMapping("/{intakeId}")
    public IntakeResponse update(
            @PathVariable UUID intakeId, @Valid @RequestBody IntakeRequest request, Authentication authentication) {
        return intakeService.update(userId(authentication), intakeId, request);
    }

    @DeleteMapping("/{intakeId}")
    public ResponseEntity<Void> delete(@PathVariable UUID intakeId, Authentication authentication) {
        intakeService.delete(userId(authentication), intakeId);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}
