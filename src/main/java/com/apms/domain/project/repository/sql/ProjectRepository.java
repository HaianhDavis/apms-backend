package com.apms.domain.project.repository.sql;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.domain.project.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Page<Project> findByStatus(ProjectStatus status, Pageable pageable);

    Page<Project> findByProjectType(ProjectType projectType, Pageable pageable);

    Page<Project> findByStatusAndProjectType(ProjectStatus status, ProjectType projectType, Pageable pageable);

    @Query("SELECT p FROM Project p JOIN p.members m WHERE m.account.id = :accountId")
    Page<Project> findByMemberAccountId(@Param("accountId") Long accountId, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM ProjectMember m WHERE m.project.id = :projectId AND m.account.id = :accountId")
    boolean existsByIdAndMembersAccountId(@Param("projectId") Long projectId, @Param("accountId") Long accountId);
}
