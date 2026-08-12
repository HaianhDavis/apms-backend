package com.apms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.neo4j.driver.Driver;

@Configuration
@EnableNeo4jRepositories(
    basePackages = {
        "com.apms.domain.graph.repository.neo4j"
    },
    includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = Neo4jRepository.class),
    transactionManagerRef = "neo4jTransactionManager"
)
public class Neo4jConfig {

    @Bean
    public Neo4jTransactionManager neo4jTransactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }
}
