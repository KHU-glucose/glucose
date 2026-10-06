package com.glucoselog.job;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Query(
            value = "SELECT * FROM job WHERE status = 'PENDING' AND available_at <= now() "
                    + "ORDER BY available_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Job> findDueForProcessing(@Param("limit") int limit);
}
