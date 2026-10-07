package com.glucoselog.report;

public record EducationCardResponse(EducationCardTrigger trigger, String title, String body) {

    static EducationCardResponse from(EducationCard card) {
        return new EducationCardResponse(card.getTrigger(), card.getTitle(), card.getBody());
    }
}
