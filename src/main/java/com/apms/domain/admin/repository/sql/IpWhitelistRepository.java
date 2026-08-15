package com.apms.domain.admin.repository.sql;

import com.apms.domain.admin.IpWhitelistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IpWhitelistRepository extends JpaRepository<IpWhitelistEntry, Long> {
    List<IpWhitelistEntry> findAllByOrderByIdAsc();
    List<IpWhitelistEntry> findAllByEnabledTrue();
    Optional<IpWhitelistEntry> findByIpAddress(String ipAddress);
}
