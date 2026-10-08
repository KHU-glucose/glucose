package com.glucoselog.report;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EducationCardRepository extends JpaRepository<EducationCard, UUID> {

    List<EducationCard> findByTrigger(EducationCardTrigger trigger);
}
