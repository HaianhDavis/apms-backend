package com.apms.domain.project.repository.sql;

import com.apms.domain.project.ProjectTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProjectTaskRepository extends JpaRepository<ProjectTask, Long>, JpaSpecificationExecutor<ProjectTask> {
    
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"project"})
    java.util.List<ProjectTask> findByAssignedToAccount_Id(Long accountId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"project", "assignedToAccount"})
    java.util.List<ProjectTask> findByProject_IdIn(java.util.List<Long> projectIds);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"project", "assignedToAccount"})
    java.util.List<ProjectTask> findByProject_Id(Long projectId);
    
    int countByProjectIdAndStatusIn(Long projectId, java.util.Collection<com.apms.common.enums.TaskStatus> statuses);
    java.util.List<ProjectTask> findByProjectIdAndTaskTypeAndTargetCompanyProfileIdIsNull(Long projectId, com.apms.common.enums.TaskType taskType);
    boolean existsByProjectIdAndAssignedToAccountIdAndStatusNotIn(Long projectId, Long assignedToAccountId, java.util.Collection<com.apms.common.enums.TaskStatus> statuses);

    void deleteByProjectId(Long projectId);

    public interface ProjectTaskStats {
        Long getProjectId();
        Long getTotalTasks();
        Long getCompletedTasks();
    }

    @org.springframework.data.jpa.repository.Query("SELECT t.project.id as projectId, COUNT(t) as totalTasks, " +
           "SUM(CASE WHEN t.status = :doneStatus THEN 1 ELSE 0 END) as completedTasks " +
           "FROM ProjectTask t WHERE t.project.id IN :projectIds AND t.status <> :cancelledStatus " +
           "GROUP BY t.project.id")
    java.util.List<ProjectTaskStats> getProjectTaskStatsIn(
           @org.springframework.data.repository.query.Param("projectIds") java.util.List<Long> projectIds,
           @org.springframework.data.repository.query.Param("doneStatus") com.apms.common.enums.TaskStatus doneStatus,
           @org.springframework.data.repository.query.Param("cancelledStatus") com.apms.common.enums.TaskStatus cancelledStatus);
}
