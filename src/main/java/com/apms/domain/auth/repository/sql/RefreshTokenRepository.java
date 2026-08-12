package com.apms.domain.auth.repository.sql;

import com.apms.domain.auth.RefreshToken;
import com.apms.domain.user.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    List<RefreshToken> findAllByAccount(Account account);

    @Modifying
    void deleteByAccount(Account account);
}
