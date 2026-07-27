package com.apms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(
    basePackages = {
        "com.apms.domain.user.repository.sql",
        "com.apms.domain.auth.repository.sql",
        "com.apms.domain.audit.repository.sql",
        "com.apms.domain.project.repository.sql",
        "com.apms.domain.score.repository.sql",
        "com.apms.domain.document.repository.sql",
        "com.apms.domain.notification.repository.sql",
        "com.apms.domain.contract.repository.sql"
    }
)
public class SqlServerConfig {
}
