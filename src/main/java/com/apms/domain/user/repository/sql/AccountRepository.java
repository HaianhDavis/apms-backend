package com.apms.domain.user.repository.sql;

import com.apms.common.enums.SystemRole;
import com.apms.domain.user.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByEmail(String email);
    Optional<Account> findByEmailIgnoreCase(String email);
    List<Account> findTop10ByEmailContainingIgnoreCaseAndIsActiveTrue(String email);
    boolean existsByEmail(String email);

    List<Account> findByEmailVerifiedFalse();

    @Query("SELECT a FROM Account a JOIN a.roles r WHERE r = :role AND a.isActive = true")
    List<Account> findActiveAccountsByRole(@Param("role") SystemRole role);
}