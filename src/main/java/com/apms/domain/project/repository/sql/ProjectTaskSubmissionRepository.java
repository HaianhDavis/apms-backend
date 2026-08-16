package com.apms.domain.project.repository.sql;

import com.apms.domain.project.ProjectTaskSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProjectTaskSubmissionRepository extends JpaRepository<ProjectTaskSubmission, Long>, JpaSpecificationExecutor<ProjectTaskSubmission> {
    java.util.List<ProjectTaskSubmission> findByProjectTask_Id(Long taskId);
    
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"projectTask", "projectTask.project"})
    java.util.List<ProjectTaskSubmission> findByProjectTask_IdIn(java.util.Collection<Long> taskIds);
    
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "project",
        "projectTask",
        "projectTask.project",
        "projectTask.assignedToAccount",
        "submittedByAccount"
    })
    java.util.List<ProjectTaskSubmission> findByProject_IdInAndStatus(
        java.util.List<Long> projectIds,
        com.apms.common.enums.SubmissionStatus status
    );

    java.util.List<ProjectTaskSubmission> findBySubmissionTypeAndStatus(
        com.apms.common.enums.SubmissionType type, 
        com.apms.common.enums.SubmissionStatus status
    );

    void deleteByProject_Id(Long projectId);
}
