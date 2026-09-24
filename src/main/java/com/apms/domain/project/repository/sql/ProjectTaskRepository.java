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

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"project", "assignedToAccount", "keyResult"})
    java.util.List<ProjectTask> findByProject_IdAndAssignedToAccount_Id(Long projectId, Long accountId);
    
    int countByProjectIdAndStatusIn(Long projectId, java.util.Collection<com.apms.common.enums.TaskStatus> statuses);
    java.util.List<ProjectTask> findByProjectIdAndTaskTypeAndTargetCompanyProfileIdIsNull(Long projectId, com.apms.common.enums.TaskType taskType);
    
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"project"})
    java.util.Optional<ProjectTask> findWithProjectById(Long id);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"project"})
    java.util.List<ProjectTask> findByTargetCompanyProfileId(String targetCompanyProfileId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"project"})
    java.util.List<ProjectTask> findByProject_TargetCompanyProfileId(String targetCompanyProfileId);

    boolean existsByProjectIdAndAssignedToAccountIdAndStatusNotIn(Long projectId, Long assignedToAccountId, java.util.Collection<com.apms.common.enums.TaskStatus> statuses);

    void deleteByProjectId(Long projectId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE ProjectTask t SET t.assignedToAccount = :account, t.status = :newStatus WHERE t.id = :taskId AND t.project.id = :projectId AND t.status = :oldStatus AND t.assignedToAccount IS NULL")
    int claimTaskAtomically(@org.springframework.data.repository.query.Param("taskId") Long taskId, 
                            @org.springframework.data.repository.query.Param("projectId") Long projectId,
                            @org.springframework.data.repository.query.Param("account") com.apms.domain.user.Account account,
                            @org.springframework.data.repository.query.Param("oldStatus") com.apms.common.enums.TaskStatus oldStatus,
                            @org.springframework.data.repository.query.Param("newStatus") com.apms.common.enums.TaskStatus newStatus);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE ProjectTask t SET t.assignedToAccount = null, t.status = :newStatus WHERE t.id = :taskId AND t.project.id = :projectId AND t.status = :oldStatus AND t.assignedToAccount.id = :accountId")
    int releaseTaskAtomically(@org.springframework.data.repository.query.Param("taskId") Long taskId, 
                              @org.springframework.data.repository.query.Param("projectId") Long projectId,
                              @org.springframework.data.repository.query.Param("accountId") Long accountId,
                              @org.springframework.data.repository.query.Param("oldStatus") com.apms.common.enums.TaskStatus oldStatus,
                              @org.springframework.data.repository.query.Param("newStatus") com.apms.common.enums.TaskStatus newStatus);

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
