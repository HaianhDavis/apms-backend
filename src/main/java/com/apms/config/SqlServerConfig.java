package com.apms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

@Configuration
@EnableJpaRepositories(
    basePackages = {
        "com.apms.domain.user.repository.sql",
        "com.apms.domain.auth.repository.sql",
        "com.apms.domain.audit.repository.sql",
        "com.apms.domain.project.repository.sql",
        "com.apms.domain.score.repository.sql",
        "com.apms.domain.document.repository.sql",
        "com.apms.domain.notification.repository.sql"
    },
    includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JpaRepository.class)
)
public class SqlServerConfig {
}
