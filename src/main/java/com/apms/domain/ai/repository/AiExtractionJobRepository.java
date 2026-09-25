package com.apms.domain.ai.repository;

import com.apms.common.enums.AiExtractionJobStatus;
import com.apms.domain.ai.entity.AiExtractionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface AiExtractionJobRepository extends JpaRepository<AiExtractionJob, String> {
    Optional<AiExtractionJob> findFirstByTaskIdOrderByCreatedAtDesc(Long taskId);

    Optional<AiExtractionJob> findFirstByTaskIdAndStatusInOrderByCreatedAtDesc(
            Long taskId, Collection<AiExtractionJobStatus> statuses);
}
