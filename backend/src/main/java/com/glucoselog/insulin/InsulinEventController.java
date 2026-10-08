package com.glucoselog.insulin;

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
@RequestMapping("/v1/insulin-events")
public class InsulinEventController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final InsulinEventService service;

    public InsulinEventController(InsulinEventService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InsulinEventResponse create(@Valid @RequestBody InsulinEventRequest request, Authentication authentication) {
        return service.create(userId(authentication), request);
    }

    @GetMapping
    public PageResponse<InsulinEventResponse> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        int effectiveLimit = Math.min(Math.max(limit != null ? limit : DEFAULT_LIMIT, 1), MAX_LIMIT);
        return service.list(userId(authentication), cursor, effectiveLimit);
    }

    @GetMapping("/{eventId}")
    public InsulinEventResponse get(@PathVariable UUID eventId, Authentication authentication) {
        return service.get(userId(authentication), eventId);
    }

    @PatchMapping("/{eventId}")
    public InsulinEventResponse update(
            @PathVariable UUID eventId, @Valid @RequestBody InsulinEventRequest request, Authentication authentication) {
        return service.update(userId(authentication), eventId, request);
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> delete(@PathVariable UUID eventId, Authentication authentication) {
        service.delete(userId(authentication), eventId);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}
