package com.apms.domain.project.repository.sql;

import com.apms.domain.project.ProjectKeyResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProjectKeyResultRepository extends JpaRepository<ProjectKeyResult, Long> {
    List<ProjectKeyResult> findByProject_Id(Long projectId);
}
