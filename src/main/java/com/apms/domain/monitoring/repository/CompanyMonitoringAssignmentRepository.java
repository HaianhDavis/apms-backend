package com.apms.domain.monitoring.repository;

import com.apms.common.enums.MonitoringStatus;
import com.apms.domain.monitoring.model.CompanyMonitoringAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CompanyMonitoringAssignmentRepository extends JpaRepository<CompanyMonitoringAssignment, Long> {

    Optional<CompanyMonitoringAssignment> findByCompanyProfileId(String companyProfileId);

    Page<CompanyMonitoringAssignment> findByAssignedStaffId(Long assignedStaffId, Pageable pageable);

    Page<CompanyMonitoringAssignment> findByStatus(MonitoringStatus status, Pageable pageable);

    @Query("SELECT c FROM CompanyMonitoringAssignment c WHERE c.status = 'ACTIVE' AND c.nextReviewAt <= :now")
    Page<CompanyMonitoringAssignment> findDueOrOverdueActiveAssignments(@Param("now") LocalDateTime now, Pageable pageable);

    Page<CompanyMonitoringAssignment> findByCompanyProfileIdIn(java.util.Collection<String> companyProfileIds, Pageable pageable);

    @Query("SELECT c FROM CompanyMonitoringAssignment c WHERE c.status = 'ACTIVE' AND c.nextReviewAt <= :now AND c.companyProfileId IN :companyProfileIds")
    Page<CompanyMonitoringAssignment> findDueOrOverdueActiveAssignmentsForCompanies(@Param("now") LocalDateTime now, @Param("companyProfileIds") java.util.Collection<String> companyProfileIds, Pageable pageable);

    boolean existsByCompanyProfileIdAndAssignedStaffId(String companyProfileId, Long assignedStaffId);
}
