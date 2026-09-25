package com.apms.domain.security.repository;

import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {
    Optional<OtpChallenge> findByIdAndAccountId(Long id, Long accountId);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM OtpChallenge c WHERE c.id = :id")
    Optional<OtpChallenge> findByIdForUpdate(@Param("id") Long id);
    
    Optional<OtpChallenge> findTopByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(Long accountId, StepUpPurpose purpose);
    
    int countByAccountIdAndPurposeAndCreatedAtAfter(Long accountId, StepUpPurpose purpose, LocalDateTime after);

    List<OtpChallenge> findByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(Long accountId, StepUpPurpose purpose);

    List<OtpChallenge> findByPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(StepUpPurpose purpose);
}
