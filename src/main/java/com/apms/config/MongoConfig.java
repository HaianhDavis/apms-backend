package com.apms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
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
        "com.apms.domain.ai.repository.mongo",
        "com.apms.domain.assistant.repository.mongo",
        "com.apms.domain.externaldata.repository.mongo",
        "com.apms.domain.score.repository.mongo",
        "com.apms.domain.contract.repository.mongo"
    },
    includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MongoRepository.class)
)
public class MongoConfig {

    /**
     * Note: MongoDB Transactions require a Replica Set.
     * Ensure the production and integration-test MongoDB environments are deployed as replica sets.
     */
    @Bean(name = "mongoTransactionManager")
    public MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
