package com.apms.domain.project.repository.sql;

import com.apms.domain.project.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    List<ProjectMember> findByProject_Id(Long projectId);

    Optional<ProjectMember> findByProject_IdAndAccount_Id(Long projectId, Long accountId);

    boolean existsByProject_IdAndAccount_Id(Long projectId, Long accountId);

    void deleteByProject_IdAndAccount_Id(Long projectId, Long accountId);
}
