package com.glucoselog.photo;

import java.util.UUID;

public record PhotoUploadResponse(UUID photoId, String uploadUrl, String objectKey, long expiresIn) {
}
