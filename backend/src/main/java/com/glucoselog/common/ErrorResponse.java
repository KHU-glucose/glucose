package com.glucoselog.common;

public record ErrorResponse(String code, String message, String requestId) {
}
