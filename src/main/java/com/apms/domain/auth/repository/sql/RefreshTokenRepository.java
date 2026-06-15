package com.apms.domain.auth.repository.sql;

import com.apms.domain.auth.RefreshToken;
import com.apms.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByUser(User user);

    @Modifying
    void deleteByUser(User user);
}
