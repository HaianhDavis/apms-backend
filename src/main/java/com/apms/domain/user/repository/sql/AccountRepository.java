package com.apms.domain.user.repository.sql;

import com.apms.domain.user.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByEmail(String email);
    boolean existsByEmail(String email);

    /** Returns only non-deleted accounts. */
    List<Account> findAllByDeletedAtIsNull();

    /** Find active (non-deleted, non-locked) accounts. */
    List<Account> findAllByDeletedAtIsNullAndIsActiveTrue();
}
