package com.apms.domain.admin.repository;

import com.apms.domain.admin.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findAllByOrderByModuleAscNameAsc();

    List<Permission> findByModule(String module);

    Optional<Permission> findByName(String name);

    boolean existsByName(String name);
}
