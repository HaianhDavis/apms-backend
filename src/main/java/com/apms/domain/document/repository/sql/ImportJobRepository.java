package com.apms.domain.document.repository.sql;

import com.apms.domain.document.ImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    Page<ImportJob> findByProject_Id(Long projectId, Pageable pageable);
}
