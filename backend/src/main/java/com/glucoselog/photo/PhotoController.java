package com.glucoselog.photo;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/photos")
public class PhotoController {

    private final PhotoUploadService photoUploadService;

    public PhotoController(PhotoUploadService photoUploadService) {
        this.photoUploadService = photoUploadService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoUploadResponse startUpload(
            @Valid @RequestBody StartPhotoUploadRequest request, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return photoUploadService.startUpload(userId, request.contentType(), request.context());
    }

    @PostMapping("/{photoId}/complete")
    public ResponseEntity<Void> completeUpload(@PathVariable UUID photoId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        photoUploadService.completeUpload(userId, photoId);
        return ResponseEntity.noContent().build();
    }
}
