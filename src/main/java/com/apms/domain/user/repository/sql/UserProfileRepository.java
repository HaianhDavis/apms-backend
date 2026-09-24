package com.apms.domain.user.repository.sql;

import com.apms.domain.user.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findByAccountId(Long accountId);
    java.util.List<UserProfile> findAllByAccountIdIn(java.util.Collection<Long> accountIds);
}
