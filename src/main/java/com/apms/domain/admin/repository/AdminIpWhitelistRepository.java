package com.apms.domain.admin.repository;

import com.apms.domain.admin.entity.AdminIpWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminIpWhitelistRepository extends JpaRepository<AdminIpWhitelist, Long> {

    List<AdminIpWhitelist> findAllByOrderByCreatedAtAsc();

    Optional<AdminIpWhitelist> findByIpAddress(String ipAddress);

    List<AdminIpWhitelist> findByEnabledTrue();

    boolean existsByIpAddress(String ipAddress);
}
