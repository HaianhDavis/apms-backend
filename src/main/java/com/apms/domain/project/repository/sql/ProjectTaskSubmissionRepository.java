package com.apms.domain.project.repository.sql;

import com.apms.domain.project.ProjectTaskSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProjectTaskSubmissionRepository extends JpaRepository<ProjectTaskSubmission, Long>, JpaSpecificationExecutor<ProjectTaskSubmission> {
    java.util.List<ProjectTaskSubmission> findByProjectTask_Id(Long taskId);
}
