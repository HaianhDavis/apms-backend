package com.apms.domain.project.repository.sql;

import com.apms.domain.project.ProjectTaskDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectTaskDraftRepository extends JpaRepository<ProjectTaskDraft, Long> {
    Optional<ProjectTaskDraft> findByProjectTask_IdAndStaffAccount_Id(Long taskId, Long staffAccountId);
    void deleteByProjectTask_IdAndStaffAccount_Id(Long taskId, Long staffAccountId);
}
