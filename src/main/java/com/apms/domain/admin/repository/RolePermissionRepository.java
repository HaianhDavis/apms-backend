package com.apms.domain.admin.repository;

import com.apms.domain.admin.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByRoleKey(String roleKey);

    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.roleKey = :roleKey")
    void deleteByRoleKey(@Param("roleKey") String roleKey);

    boolean existsByRoleKeyAndPermissionName(String roleKey, String permissionName);
}
