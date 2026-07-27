package com.apms.domain.notification.repository.sql;

import com.apms.domain.notification.FcmDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FcmDeviceTokenRepository extends JpaRepository<FcmDeviceToken, Long> {
    Optional<FcmDeviceToken> findByToken(String token);
    List<FcmDeviceToken> findByAccount_IdAndIsActiveTrue(Long accountId);
}
