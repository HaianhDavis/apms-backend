package com.apms.domain.admin.repository.sql;

import com.apms.domain.admin.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
