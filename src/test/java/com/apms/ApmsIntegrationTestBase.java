package com.apms;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Neo4jContainer;

@SpringBootTest
public abstract class ApmsIntegrationTestBase {

    static final MSSQLServerContainer<?> sqlServerContainer;
    static final MongoDBContainer mongoDBContainer;
    static final Neo4jContainer<?> neo4jContainer;

    static {
        sqlServerContainer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                .acceptLicense();
        sqlServerContainer.start();

        mongoDBContainer = new MongoDBContainer("mongo:7.0");
        mongoDBContainer.start();

        neo4jContainer = new Neo4jContainer<>("neo4j:5.20")
                .withoutAuthentication();
        neo4jContainer.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", sqlServerContainer::getJdbcUrl);
        registry.add("spring.datasource.username", sqlServerContainer::getUsername);
        registry.add("spring.datasource.password", sqlServerContainer::getPassword);

        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);

        registry.add("spring.neo4j.uri", neo4jContainer::getBoltUrl);
    }
}
