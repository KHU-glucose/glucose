package com.glucoselog.glucose;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/glucose-graphs")
public class GlucoseGraphController {

    private final GlucoseGraphUploadService uploadService;
    private final GlucoseGraphStatusService statusService;

    public GlucoseGraphController(GlucoseGraphUploadService uploadService, GlucoseGraphStatusService statusService) {
        this.uploadService = uploadService;
        this.statusService = statusService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GlucoseGraphUploadResponse startUpload(
            @Valid @RequestBody StartGlucoseGraphUploadRequest request, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return uploadService.startUpload(userId, request.contentType());
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<Void> completeUpload(@PathVariable UUID uploadId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        uploadService.completeUpload(userId, uploadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{uploadId}")
    public GlucoseGraphStatusResponse getStatus(@PathVariable UUID uploadId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return statusService.getStatus(userId, uploadId);
    }
}
