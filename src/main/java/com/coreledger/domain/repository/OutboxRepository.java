package com.coreledger.domain.repository;

import com.coreledger.domain.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            select * from outbox_events where status = 'PENDING'
            order by created_at limit :batch for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("batch") int batch);

    long countByStatus(String status);
}
