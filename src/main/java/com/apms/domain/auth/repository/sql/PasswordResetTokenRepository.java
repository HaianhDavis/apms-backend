package com.apms.domain.auth.repository.sql;

import com.apms.domain.auth.PasswordResetToken;
import com.apms.domain.user.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    Optional<PasswordResetToken> findByAccount(Account account);
    void deleteByAccount(Account account);
}
