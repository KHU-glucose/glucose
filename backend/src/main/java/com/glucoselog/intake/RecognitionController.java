package com.glucoselog.intake;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/photos/{photoId}/recognition")
public class RecognitionController {

    private final RecognitionService recognitionService;

    public RecognitionController(RecognitionService recognitionService) {
        this.recognitionService = recognitionService;
    }

    @GetMapping
    public RecognitionResponse get(@PathVariable UUID photoId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return recognitionService.getRecognition(userId, photoId);
    }
}
