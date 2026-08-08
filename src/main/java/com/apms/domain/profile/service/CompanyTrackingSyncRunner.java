package com.apms.domain.profile.service;

import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class CompanyTrackingSyncRunner implements CommandLineRunner {

    private final CompanyProfileRepository companyProfileRepository;
    private final TrackedCompanyRepository trackedCompanyRepository;
    private final TrackedCompanyCache trackedCompanyCache;
    private final MongoTemplate mongoTemplate;

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting CompanyTrackingSyncRunner to sync existing companies to TrackedCompany...");
        
        // --- Wipe all dummy seed companies and start fresh from Profiles ---
        trackedCompanyRepository.deleteAll();
        log.info("Wiped all existing TrackedCompany records to clean up dummy seed data.");
        
        // --- WIPE ALL CRAWLED ARTICLES AS REQUESTED BY USER ---
        mongoTemplate.dropCollection("crawled_articles");
        log.info("Wiped crawled_articles collection to allow a fresh crawl with correct names.");
        // ----------------------------------------------------------------------------

        List<CompanyProfile> profiles = companyProfileRepository.findAll();
        boolean hasAdded = false;

        for (CompanyProfile profile : profiles) {
            if (profile.getIdentity() == null) continue;

            String legalName = profile.getIdentity().getLegalName();
            String tradeName = profile.getIdentity().getTradeName();

            String primaryName = null;
            if (org.springframework.util.StringUtils.hasText(legalName)) {
                primaryName = legalName.trim();
            } else if (org.springframework.util.StringUtils.hasText(tradeName)) {
                primaryName = tradeName.trim();
            }

            if (primaryName == null) continue;

            List<String> aliases = new ArrayList<>();
            if (org.springframework.util.StringUtils.hasText(tradeName) && !tradeName.trim().equalsIgnoreCase(primaryName)) {
                aliases.add(tradeName.trim());
            }

            TrackedCompany newCompany = TrackedCompany.builder()
                    .companyName(primaryName)
                    .aliases(aliases)
                    .isActive(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            trackedCompanyRepository.save(newCompany);
            log.info("Created synced TrackedCompany for: {} with aliases {}", primaryName, aliases);
            hasAdded = true;
        }

        if (hasAdded) {
            trackedCompanyCache.forceRefresh();
        }
        log.info("CompanyTrackingSyncRunner completed.");
    }
}
