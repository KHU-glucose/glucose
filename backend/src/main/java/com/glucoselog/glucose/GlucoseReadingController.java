package com.glucoselog.glucose;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/glucose-readings")
public class GlucoseReadingController {

    private final GlucoseReadingService readingService;

    public GlucoseReadingController(GlucoseReadingService readingService) {
        this.readingService = readingService;
    }

    @GetMapping("/{date}")
    public GlucoseReadingsResponse getByDate(@PathVariable LocalDate date, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return readingService.getByDate(userId, date);
    }
}
