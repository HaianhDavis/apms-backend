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
        
        // Remove destructive wipes:
        // trackedCompanyRepository.deleteAll();
        // mongoTemplate.dropCollection("crawled_articles");

        List<CompanyProfile> profiles = companyProfileRepository.findAll();
        boolean hasAdded = false;

        java.util.Map<String, TrackedCompany> companiesToSave = new java.util.HashMap<>();

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

            String normalizedName = primaryName.toLowerCase();
            if (companiesToSave.containsKey(normalizedName)) {
                continue;
            }

            List<String> aliases = new ArrayList<>();
            if (org.springframework.util.StringUtils.hasText(tradeName) && !tradeName.trim().equalsIgnoreCase(primaryName)) {
                aliases.add(tradeName.trim());
            }
            String stockTicker = profile.getIdentity().getStockTicker();
            if (org.springframework.util.StringUtils.hasText(stockTicker)) {
                aliases.add(stockTicker.trim());
            }

            Optional<TrackedCompany> existingOpt = trackedCompanyRepository.findByCompanyNameIgnoreCase(primaryName);
            TrackedCompany company;
            if (existingOpt.isPresent()) {
                company = existingOpt.get();
                company.setAliases(aliases);
                company.setUpdatedAt(LocalDateTime.now());
                company.setIsActive(true);
            } else {
                company = TrackedCompany.builder()
                        .id(profile.getId())
                        .companyName(primaryName)
                        .aliases(aliases)
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                hasAdded = true;
            }
            companiesToSave.put(normalizedName, company);
        }

        for (TrackedCompany company : companiesToSave.values()) {
            trackedCompanyRepository.save(company);
            log.info("Saved synced TrackedCompany for: {}", company.getCompanyName());
        }

        if (hasAdded || !companiesToSave.isEmpty()) {
            trackedCompanyCache.forceRefresh();
        }
        log.info("CompanyTrackingSyncRunner completed.");
    }
}
