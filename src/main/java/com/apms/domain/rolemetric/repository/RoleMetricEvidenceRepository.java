package com.apms.domain.rolemetric.repository;

import com.apms.domain.rolemetric.entity.RoleMetricEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleMetricEvidenceRepository extends JpaRepository<RoleMetricEvidence, Long> {
    List<RoleMetricEvidence> findByRoleMetricRecordId(Long roleMetricRecordId);
}
