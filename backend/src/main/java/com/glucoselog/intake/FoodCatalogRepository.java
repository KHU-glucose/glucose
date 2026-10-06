package com.glucoselog.intake;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodCatalogRepository extends JpaRepository<FoodCatalog, UUID> {

    Optional<FoodCatalog> findByNameIgnoreCase(String name);
}
