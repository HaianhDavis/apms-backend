package com.apms.domain.ai.repository;

import com.apms.domain.ai.entity.AiExtractionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiExtractionJobRepository extends JpaRepository<AiExtractionJob, String> {
}
