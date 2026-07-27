package com.apms.domain.rolemetric.repository;

import com.apms.domain.rolemetric.entity.RoleMetricRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoleMetricRecordRepository extends JpaRepository<RoleMetricRecord, Long> {
    List<RoleMetricRecord> findByProjectId(Long projectId);

    // Exact duplicate constraint mapping finder
    Optional<RoleMetricRecord> findByProjectIdAndCompanyIdAndRelationshipTypeAndMetricKeyAndPeriodKey(
            Long projectId, String companyId, String relationshipType, String metricKey, String periodKey
    );

    // PERIOD overlap search
    @Query("SELECT r FROM RoleMetricRecord r WHERE r.projectId = :projectId AND r.companyId = :companyId " +
           "AND r.relationshipType = :relationshipType AND r.metricKey = :metricKey " +
           "AND r.periodType = 'PERIOD' " +
           "AND ((r.periodStart <= :end AND r.periodEnd >= :start)) " +
           "AND (:excludeId IS NULL OR r.id != :excludeId)")
    List<RoleMetricRecord> findOverlappingPeriods(
            @Param("projectId") Long projectId,
            @Param("companyId") String companyId,
            @Param("relationshipType") String relationshipType,
            @Param("metricKey") String metricKey,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("excludeId") Long excludeId
    );
}
