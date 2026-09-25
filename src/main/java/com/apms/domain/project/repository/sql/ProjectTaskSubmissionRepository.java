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

    @org.springframework.data.jpa.repository.Query("SELECT s FROM ProjectTaskSubmission s " +
        "JOIN FETCH s.projectTask t " +
        "JOIN FETCH s.project p " +
        "LEFT JOIN FETCH s.submittedByAccount sub " +
        "LEFT JOIN FETCH s.reviewedByAccount rev " +
        "WHERE s.reviewedAt IS NOT NULL " +
        "  AND (rev.id = :managerId " +
        "       OR p.createdByAccount.id = :managerId " +
        "       OR EXISTS (SELECT 1 FROM ProjectMember pm WHERE pm.project.id = p.id AND pm.account.id = :managerId)) " +
        "ORDER BY s.reviewedAt DESC")
    java.util.List<ProjectTaskSubmission> findReviewHistoryByManagerId(@org.springframework.data.repository.query.Param("managerId") Long managerId);

    @org.springframework.data.jpa.repository.Query("SELECT s FROM ProjectTaskSubmission s " +
        "JOIN FETCH s.projectTask t " +
        "JOIN FETCH s.project p " +
        "LEFT JOIN FETCH s.submittedByAccount sub " +
        "LEFT JOIN FETCH s.reviewedByAccount rev " +
        "WHERE p.id = :projectId " +
        "ORDER BY coalesce(s.reviewedAt, s.submittedAt, s.createdAt) DESC")
    java.util.List<ProjectTaskSubmission> findReviewHistoryByProjectId(@org.springframework.data.repository.query.Param("projectId") Long projectId);

    void deleteByProject_Id(Long projectId);
}
