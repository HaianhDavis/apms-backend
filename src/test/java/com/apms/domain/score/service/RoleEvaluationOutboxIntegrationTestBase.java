package com.apms.domain.score.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "jwt.secret=9a4f2c8d3b7a1e6f45c8a0b3f267d8b1d4e6f3c8a9d2b5f8e3a9c8b5f6v8a3d9",
    "jwt.expirationMs=3600000",
    "spring.ai.openai.api-key=test-api-key",
    "app.storage.upload-dir=/tmp/apms-test-uploads",
    "spring.data.mongodb.auto-index-creation=true",
    "spring.jpa.hibernate.ddl-auto=update"
})
@Testcontainers
@ActiveProfiles("test")
public abstract class RoleEvaluationOutboxIntegrationTestBase {

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER::getDriverClassName);
        registry.add("spring.data.mongodb.uri", () -> RoleEvaluationMongoContainerHolder.MONGO_CONTAINER.getReplicaSetUrl("rs0"));
    }
}
