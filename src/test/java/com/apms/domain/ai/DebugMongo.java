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
public class DebugMongo {

    public static void main(String[] args) {
        System.setProperty("spring.main.web-application-type", "none");
        System.setProperty("spring.autoconfigure.exclude", "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration");
        // Disable security configuration classes that require TOTP key
        System.setProperty("spring.autoconfigure.exclude", "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,com.apms.common.config.SecurityConfig");
        
        SpringApplication.run(DebugMongo.class, args);
    }

    @Bean
    public CommandLineRunner runDebugMongo(MongoTemplate mongoTemplate) {
        return args -> {
            Query query = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1);
            List<CompanyCandidate> candidates = mongoTemplate.find(query, CompanyCandidate.class);
            if (candidates.isEmpty()) {
                System.out.println("NO CANDIDATES FOUND!");
            } else {
                CompanyCandidate candidate = candidates.get(0);
                System.out.println("====== LATEST CANDIDATE ======");
                System.out.println("ID: " + candidate.getId());
                System.out.println("Business Model in Entity: " + (candidate.getBusiness() != null ? candidate.getBusiness().getBusinessModel() : "null"));
                System.out.println("Industries in Entity: " + (candidate.getBusiness() != null ? candidate.getBusiness().getIndustries() : "null"));
                
                System.out.println("FieldResults Keys: " + candidate.getFieldResults().keySet());
                if (candidate.getFieldResults().containsKey("businessModel")) {
                    System.out.println("businessModel Value in FieldResults: " + candidate.getFieldResults().get("businessModel").getValue());
                } else {
                    System.out.println("businessModel NOT IN FieldResults!");
                }
                
                if (candidate.getFieldResults().containsKey("industries")) {
                    System.out.println("industries Value in FieldResults: " + candidate.getFieldResults().get("industries").getValue());
                } else {
                    System.out.println("industries NOT IN FieldResults!");
                }
            }
            // System.exit(0);
        };
    }
}
