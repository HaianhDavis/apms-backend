package com.apms.domain.score.repository.sql;

import com.apms.domain.score.outbox.ProcessedOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedOutboxEventRepository extends JpaRepository<ProcessedOutboxEvent, Long> {
}
