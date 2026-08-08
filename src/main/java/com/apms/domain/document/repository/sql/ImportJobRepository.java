package com.apms.domain.document.repository.sql;

import com.apms.domain.document.ImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    Page<ImportJob> findByProject_Id(Long projectId, Pageable pageable);

    Page<ImportJob> findByProject_IdAndRawDocumentIdNotIn(Long projectId, java.util.Collection<String> rawDocumentIds, Pageable pageable);

    java.util.List<ImportJob> findByProject_IdAndRawDocumentIdIn(Long projectId, java.util.Collection<String> rawDocumentIds);

    void deleteByProject_Id(Long projectId);

    Page<ImportJob> findByProject_IdAndRawDocumentIdIn(Long projectId, java.util.Collection<String> rawDocumentIds, Pageable pageable);
}
