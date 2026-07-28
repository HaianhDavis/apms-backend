package com.apms.domain.user.repository.sql;

import com.apms.domain.user.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByEmail(String email);
    Optional<Account> findByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
    long countByRolesContainingAndIsActiveTrue(com.apms.common.enums.SystemRole role);
    List<Account> findByEmailContainingIgnoreCase(String email);
}
