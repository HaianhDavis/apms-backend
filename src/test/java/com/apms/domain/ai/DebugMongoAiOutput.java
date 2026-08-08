package com.apms.domain.ai;

import com.apms.domain.candidate.CompanyCandidate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;
import java.util.List;

@SpringBootApplication
public class DebugMongoAiOutput {

    public static void main(String[] args) {
        System.setProperty("spring.main.web-application-type", "none");
        System.setProperty("spring.autoconfigure.exclude", "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,com.apms.common.config.SecurityConfig");
        
        SpringApplication.run(DebugMongoAiOutput.class, args);
    }

    @Bean
    public CommandLineRunner run(MongoTemplate mongoTemplate) {
        return args -> {
            Query query = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1);
            List<CompanyCandidate> candidates = mongoTemplate.find(query, CompanyCandidate.class);
            if (candidates.isEmpty()) {
                System.out.println("NO CANDIDATES FOUND!");
            } else {
                CompanyCandidate candidate = candidates.get(0);
                System.out.println("====== RAW AI OUTPUT ======");
                System.out.println(candidate.getRawAiOutput());
                System.out.println("===========================");
            }
            System.exit(0);
        };
    }
}
