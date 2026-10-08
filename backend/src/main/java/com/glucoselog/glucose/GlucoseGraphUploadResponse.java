package com.glucoselog.glucose;

import java.util.UUID;

public record GlucoseGraphUploadResponse(UUID uploadId, String uploadUrl, String objectKey, long expiresIn) {
}
