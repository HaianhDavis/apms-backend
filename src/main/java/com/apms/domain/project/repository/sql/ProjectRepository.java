package com.apms.domain.project.repository.sql;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.domain.project.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Page<Project> findByStatus(ProjectStatus status, Pageable pageable);

    Page<Project> findByProjectType(ProjectType projectType, Pageable pageable);

    Page<Project> findByStatusAndProjectType(ProjectStatus status, ProjectType projectType, Pageable pageable);

    @Query("SELECT p FROM Project p JOIN p.members m WHERE m.account.id = :accountId")
    Page<Project> findByMemberAccountId(@Param("accountId") Long accountId, Pageable pageable);

    @Query("""
            SELECT DISTINCT p FROM Project p
            JOIN p.members m
            WHERE m.account.id = :accountId
              AND (:status IS NULL OR p.status = :status)
              AND (:projectType IS NULL OR p.projectType = :projectType)
            """)
    Page<Project> findVisibleProjectsForMember(
            @Param("accountId") Long accountId,
            @Param("status") ProjectStatus status,
            @Param("projectType") ProjectType projectType,
            Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM ProjectMember m WHERE m.project.id = :projectId AND m.account.id = :accountId")
    boolean existsByIdAndMembersAccountId(@Param("projectId") Long projectId, @Param("accountId") Long accountId);

    @Query("""
            SELECT DISTINCT p.targetCompanyProfileId
            FROM Project p
            JOIN p.members m
            WHERE m.account.id = :accountId
              AND p.targetCompanyProfileId IS NOT NULL
            """)
    List<String> findTargetCompanyProfileIdsByMemberAccountId(@Param("accountId") Long accountId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Project p JOIN p.members m WHERE p.targetCompanyProfileId = :targetCompanyProfileId AND m.account.id = :accountId AND p.status IN :allowedStatuses")
    boolean existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(@Param("targetCompanyProfileId") String targetCompanyProfileId, @Param("accountId") Long accountId, @Param("allowedStatuses") java.util.List<ProjectStatus> allowedStatuses);

    Optional<Project> findFirstByCreatedByAccountIdOrderByIdAsc(Long accountId);

    @Query("""
            SELECT p FROM Project p
            WHERE LOWER(p.targetCompanyName) LIKE LOWER(CONCAT('%', :companyName, '%'))
              AND p.status <> com.apms.common.enums.ProjectStatus.CANCELLED
              AND (:excludeId IS NULL OR p.id <> :excludeId)
            """)
    List<Project> findByTargetCompanyNameContainingIgnoreCase(
            @Param("companyName") String companyName,
            @Param("excludeId") Long excludeId);
}
