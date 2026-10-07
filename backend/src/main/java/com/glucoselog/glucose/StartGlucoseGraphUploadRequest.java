package com.glucoselog.glucose;

import jakarta.validation.constraints.NotBlank;

public record StartGlucoseGraphUploadRequest(@NotBlank String contentType) {
}
