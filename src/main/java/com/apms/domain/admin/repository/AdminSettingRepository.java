package com.apms.domain.admin.repository;

import com.apms.domain.admin.entity.AdminSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdminSettingRepository extends JpaRepository<AdminSetting, Long> {

    Optional<AdminSetting> findBySettingKey(String settingKey);

    boolean existsBySettingKey(String settingKey);
}
