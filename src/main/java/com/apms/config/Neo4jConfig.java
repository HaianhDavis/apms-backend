package com.apms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

@Configuration
@EnableNeo4jRepositories(
    basePackages = {
        "com.apms.domain.graph.repository.neo4j"
    },
    includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = Neo4jRepository.class)
)
public class Neo4jConfig {
}
