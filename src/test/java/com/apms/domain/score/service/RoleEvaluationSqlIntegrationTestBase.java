package com.apms.domain.score.service;

import com.apms.domain.score.outbox.SqlIdempotencyService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RoleEvaluationSqlIntegrationTestBase.MinimalSqlTestConfig.class)
public abstract class RoleEvaluationSqlIntegrationTestBase {

    @Configuration
@org.springframework.transaction.annotation.EnableTransactionManagement
    public static class MinimalSqlTestConfig {

        @Bean
        public DataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getJdbcUrl());
            dataSource.setUsername(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getUsername());
            dataSource.setPassword(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getPassword());
            dataSource.setDriverClassName(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getDriverClassName());
            dataSource.setMaximumPoolSize(5); // At least two connections

            // Initialize schema
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new org.springframework.core.io.FileSystemResource("docs/sql-migrations/V1__Create_Processed_Outbox_Events.sql"));
            populator.execute(dataSource);

            return dataSource;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public SqlIdempotencyService sqlIdempotencyService(JdbcTemplate jdbcTemplate) {
            return new SqlIdempotencyService(jdbcTemplate);
        }
    }
}
