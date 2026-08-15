package com.apms.config;

import com.apms.domain.admin.service.AdminSettingsService;
import com.apms.security.IpWhitelistFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IpWhitelistFilterConfig {

    @Bean
    public IpWhitelistFilter ipWhitelistFilter(AdminSettingsService adminSettingsService) {
        return new IpWhitelistFilter(adminSettingsService);
    }
}
