package com.apms.domain.monitoring.repository;

import com.apms.domain.monitoring.model.CompanyMonitoringReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyMonitoringReviewRepository extends JpaRepository<CompanyMonitoringReview, Long> {

    Page<CompanyMonitoringReview> findByAssignmentIdOrderByReviewedAtDesc(Long assignmentId, Pageable pageable);

    Page<CompanyMonitoringReview> findByCompanyProfileIdOrderByReviewedAtDesc(String companyProfileId, Pageable pageable);
}
