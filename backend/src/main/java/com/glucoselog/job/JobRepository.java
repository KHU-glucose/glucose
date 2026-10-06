package com.glucoselog.job;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID> {

    // DB 서버의 now()가 아니라 애플리케이션이 넘긴 시각(cutoff)과 비교한다. available_at도 애플리케이션
    // 시계(Instant.now())로 쓰기 때문에, DB 서버 시계로 비교하면 두 시계가 아주 조금만 어긋나도
    // 방금 넣은 job이 아직 안 된 것으로 보여 안 잡히는 경우가 생긴다.
    @Query(
            value = "SELECT * FROM job WHERE status = 'PENDING' AND available_at <= :cutoff "
                    + "ORDER BY available_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Job> findDueForProcessing(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
