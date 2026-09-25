package com.apms.domain.monitoring.repository;

import com.apms.domain.monitoring.model.CompanyMonitoringReview;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface CompanyMonitoringReviewRepository extends JpaRepository<CompanyMonitoringReview, Long> {

    @EntityGraph(attributePaths = {"assignment", "reviewedBy"})
    Page<CompanyMonitoringReview> findAll(Pageable pageable);

    Page<CompanyMonitoringReview> findByAssignmentIdOrderByReviewedAtDesc(Long assignmentId, Pageable pageable);

    java.util.Optional<CompanyMonitoringReview> findTopByAssignmentIdOrderByReviewedAtDesc(Long assignmentId);

    Page<CompanyMonitoringReview> findByCompanyProfileIdOrderByReviewedAtDesc(String companyProfileId, Pageable pageable);

    @EntityGraph(attributePaths = {"assignment", "reviewedBy"})
    Page<CompanyMonitoringReview> findByCompanyProfileIdIn(Collection<String> companyProfileIds, Pageable pageable);

    @EntityGraph(attributePaths = {"assignment", "reviewedBy"})
    Page<CompanyMonitoringReview> findByReviewedById(Long reviewedById, Pageable pageable);
}
