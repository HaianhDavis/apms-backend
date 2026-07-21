package com.apms.domain.rolemetric.repository;

import com.apms.domain.rolemetric.entity.RoleMetricRecordVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleMetricRecordVersionRepository extends JpaRepository<RoleMetricRecordVersion, Long> {
    List<RoleMetricRecordVersion> findByRoleMetricRecordIdOrderByVersionNumberDesc(Long roleMetricRecordId);
    Optional<RoleMetricRecordVersion> findByRoleMetricRecordIdAndVersionNumber(Long roleMetricRecordId, Integer versionNumber);
    List<RoleMetricRecordVersion> findByProjectId(Long projectId);
}
