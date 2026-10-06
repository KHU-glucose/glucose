package com.glucoselog.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.http.HttpStatus;

/** docs/backend-api.md의 cursor 페이지네이션 규약: 목록 마지막 항목의 (occurred_at, id)를 불투명 문자열로 인코딩한다. */
public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(Instant time, UUID id) {
        String raw = time.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "cursor 값이 올바르지 않습니다");
        }
    }
}
