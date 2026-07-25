package com.apms.domain.score.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.junit.jupiter.api.extension.ExtendWith;
import com.apms.config.MongoConfig;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.common.security.ProjectSecurityEvaluator;
import org.mockito.Mockito;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RoleEvaluationMongoIntegrationTestBase.MinimalMongoTestConfig.class)
@TestPropertySource(properties = {
    "jwt.secret=9a4f2c8d3b7a1e6f45c8a0b3f267d8b1d4e6f3c8a9d2b5f8e3a9c8b5f6v8a3d9",
    "jwt.expirationMs=3600000",
    "spring.ai.openai.api-key=test-api-key",
    "app.storage.upload-dir=/tmp/apms-test-uploads",
    "spring.data.mongodb.auto-index-creation=true"
})
@ActiveProfiles("test")
public abstract class RoleEvaluationMongoIntegrationTestBase {

    @Configuration
    @EnableMongoRepositories(basePackages = "com.apms.domain.score.repository.mongo")
    @EnableTransactionManagement
    @Import({
        MongoConfig.class, // Brings in MongoTransactionManager, MongoTemplate
        org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class
    })
    static class MinimalMongoTestConfig {

        @Bean
        public PartnerDataSufficiencyEvaluator partnerDataSufficiencyEvaluator() {
            return Mockito.mock(PartnerDataSufficiencyEvaluator.class);
        }

        @Bean
        public AccountRepository accountRepository() {
            return Mockito.mock(AccountRepository.class);
        }

        @Bean
        public ProjectSecurityEvaluator projectSecurityEvaluator() {
            return Mockito.mock(ProjectSecurityEvaluator.class);
        }

        @Bean
        public PartnerRoleEvaluationSubmissionStrategy partnerRoleEvaluationSubmissionStrategy(
                MongoTemplate mongoTemplate,
                AccountRepository accountRepository,
                PartnerDataSufficiencyEvaluator sufficiencyEvaluator) {
            return new PartnerRoleEvaluationSubmissionStrategy(mongoTemplate, accountRepository, sufficiencyEvaluator);
        }

        @Bean
        public PartnerRoleEvaluationApprovalStrategy partnerRoleEvaluationApprovalStrategy(
                MongoTemplate mongoTemplate,
                PartnerDataSufficiencyEvaluator sufficiencyEvaluator,
                ProjectSecurityEvaluator projectSecurityEvaluator) {
            return new PartnerRoleEvaluationApprovalStrategy(mongoTemplate, sufficiencyEvaluator, projectSecurityEvaluator);
        }
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> RoleEvaluationMongoContainerHolder.MONGO_CONTAINER.getReplicaSetUrl("rs0"));
    }
}
