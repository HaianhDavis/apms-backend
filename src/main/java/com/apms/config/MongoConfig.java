package com.apms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

@Configuration
@EnableMongoRepositories(
    basePackages = {
        "com.apms.domain.candidate.repository.mongo",
        "com.apms.domain.profile.repository.mongo",
        "com.apms.domain.document.repository.mongo",
        "com.apms.domain.ai.repository.mongo"
    },
    includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MongoRepository.class)
)
public class MongoConfig {
}
