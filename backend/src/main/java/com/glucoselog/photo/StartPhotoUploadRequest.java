package com.glucoselog.photo;

import jakarta.validation.constraints.NotBlank;

public record StartPhotoUploadRequest(@NotBlank String contentType, PhotoContext context) {
}
