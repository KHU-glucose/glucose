package com.glucoselog.common;

import java.time.Instant;
import java.util.UUID;

public record Cursor(Instant time, UUID id) {
}
