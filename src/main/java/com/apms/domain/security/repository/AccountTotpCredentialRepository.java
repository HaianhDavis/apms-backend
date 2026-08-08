package com.apms.domain.security.repository;

import com.apms.domain.security.entity.AccountTotpCredential;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountTotpCredentialRepository extends JpaRepository<AccountTotpCredential, Long> {

    Optional<AccountTotpCredential> findByAccountId(Long accountId);

    Optional<AccountTotpCredential> findByEnrollmentId(UUID enrollmentId);

    @Lock(LockModeType.OPTIMISTIC)
    @Query("SELECT c FROM AccountTotpCredential c WHERE c.accountId = :accountId")
    Optional<AccountTotpCredential> findByAccountIdWithLock(@Param("accountId") Long accountId);
}
