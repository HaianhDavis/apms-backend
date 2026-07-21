package com.apms.domain.rolemetric.repository;

import com.apms.domain.rolemetric.entity.RoleMetricEvidenceVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleMetricEvidenceVersionRepository extends JpaRepository<RoleMetricEvidenceVersion, Long> {
    List<RoleMetricEvidenceVersion> findByRoleMetricRecordVersionId(Long roleMetricRecordVersionId);
}
