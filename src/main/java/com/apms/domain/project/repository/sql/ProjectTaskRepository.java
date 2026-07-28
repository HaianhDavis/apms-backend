package com.apms.domain.project.repository.sql;

import com.apms.domain.project.ProjectTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProjectTaskRepository extends JpaRepository<ProjectTask, Long>, JpaSpecificationExecutor<ProjectTask> {
    int countByProjectIdAndStatusIn(Long projectId, java.util.Collection<com.apms.common.enums.TaskStatus> statuses);

    boolean existsByProject_IdAndTitle(Long projectId, String title);
}
