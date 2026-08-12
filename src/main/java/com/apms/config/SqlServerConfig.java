package com.apms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;

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
        "com.apms.domain.contract.repository.sql",
        "com.apms.domain.rolemetric.repository",
        "com.apms.domain.security.repository",
        "com.apms.domain.profile.closeness",
        "com.apms.domain.listingdata.repository.sql",
        "com.apms.domain.admin.repository"
    },
    includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JpaRepository.class)
)
public class SqlServerConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
