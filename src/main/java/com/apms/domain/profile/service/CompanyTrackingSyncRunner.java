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
        java.util.Set<String> validProfileIds = new java.util.HashSet<>();

        for (CompanyProfile profile : profiles) {
            if (profile.getIdentity() == null || Boolean.TRUE.equals(profile.getIsDeleted())) continue;

            String legalName = profile.getIdentity().getLegalName();
            String tradeName = profile.getIdentity().getTradeName();
            String displayName = profile.resolveDisplayName();

            String primaryName = null;
            if (org.springframework.util.StringUtils.hasText(legalName) && !legalName.trim().equalsIgnoreCase("Not updated") && !legalName.trim().equalsIgnoreCase("N/A")) {
                primaryName = legalName.trim();
            } else if (org.springframework.util.StringUtils.hasText(tradeName)) {
                primaryName = tradeName.trim();
            } else {
                primaryName = displayName;
            }

            if (primaryName == null) continue;

            List<String> aliases = new ArrayList<>();
            if (org.springframework.util.StringUtils.hasText(tradeName) && !tradeName.trim().equalsIgnoreCase(primaryName)) {
                aliases.add(tradeName.trim());
            }
            if (org.springframework.util.StringUtils.hasText(legalName) && !legalName.trim().equalsIgnoreCase(primaryName) && !aliases.contains(legalName.trim())) {
                aliases.add(legalName.trim());
            }
            if (org.springframework.util.StringUtils.hasText(displayName) && !displayName.equalsIgnoreCase(primaryName) && !aliases.contains(displayName)) {
                aliases.add(displayName);
            }
            String stockTicker = profile.getIdentity().getStockTicker();
            if (org.springframework.util.StringUtils.hasText(stockTicker) && !aliases.contains(stockTicker.trim())) {
                aliases.add(stockTicker.trim());
            }

            // Guard against duplicate names across distinct canonical profiles
            final String canonicalPrimaryName = primaryName;
            if (companiesToSave.values().stream().anyMatch(c -> !c.getId().equals(profile.getId()) && c.getCompanyName().equalsIgnoreCase(canonicalPrimaryName))) {
                log.error("Collision between distinct canonical profiles on companyName '{}'. Profile ID: {}", primaryName, profile.getId());
                continue;
            }

            // Step A: Check for existing record by canonical CompanyProfile ID
            Optional<TrackedCompany> canonicalOpt = trackedCompanyRepository.findById(profile.getId());

            // Step B: Reconcile any legacy tracked company records representing this same logical company
            java.util.Set<String> searchLegacyNames = new java.util.HashSet<>();
            searchLegacyNames.add(primaryName.toLowerCase());
            if (org.springframework.util.StringUtils.hasText(tradeName)) searchLegacyNames.add(tradeName.trim().toLowerCase());
            if (org.springframework.util.StringUtils.hasText(legalName)) searchLegacyNames.add(legalName.trim().toLowerCase());
            if (org.springframework.util.StringUtils.hasText(displayName)) searchLegacyNames.add(displayName.trim().toLowerCase());

            TrackedCompany legacyToMigrate = null;
            for (String legacyName : searchLegacyNames) {
                Optional<TrackedCompany> legacyOpt = trackedCompanyRepository.findByCompanyNameIgnoreCase(legacyName);
                if (legacyOpt.isPresent()) {
                    TrackedCompany legacy = legacyOpt.get();
                    if (!legacy.getId().equals(profile.getId())) {
                        if (legacyToMigrate == null) {
                            legacyToMigrate = legacy;
                        }
                        // Merge legacy aliases into our aliases list
                        if (legacy.getAliases() != null) {
                            for (String a : legacy.getAliases()) {
                                if (org.springframework.util.StringUtils.hasText(a) && !aliases.contains(a.trim())) {
                                    aliases.add(a.trim());
                                }
                            }
                        }
                        // Delete legacy record so companyName unique index doesn't conflict
                        trackedCompanyRepository.deleteById(legacy.getId());
                        log.info("Reconciled legacy TrackedCompany oldId={} to canonical profile id={} companyName={}",
                                legacy.getId(), profile.getId(), legacy.getCompanyName());
                    }
                }
            }

            TrackedCompany company;
            if (canonicalOpt.isPresent()) {
                company = canonicalOpt.get();
                company.setCompanyName(primaryName);
                company.setDisplayName(displayName);
                if (company.getAliases() != null) {
                    for (String a : company.getAliases()) {
                        if (org.springframework.util.StringUtils.hasText(a) && !aliases.contains(a.trim())) {
                            aliases.add(a.trim());
                        }
                    }
                }
                company.setAliases(aliases);
                company.setUpdatedAt(LocalDateTime.now());
                company.setIsActive(true);
            } else {
                company = TrackedCompany.builder()
                        .id(profile.getId())
                        .companyName(primaryName)
                        .displayName(displayName)
                        .aliases(aliases)
                        .subsidiaries(legacyToMigrate != null && legacyToMigrate.getSubsidiaries() != null ? legacyToMigrate.getSubsidiaries() : new ArrayList<>())
                        .products(legacyToMigrate != null && legacyToMigrate.getProducts() != null ? legacyToMigrate.getProducts() : new ArrayList<>())
                        .keyPeople(legacyToMigrate != null && legacyToMigrate.getKeyPeople() != null ? legacyToMigrate.getKeyPeople() : new ArrayList<>())
                        .isActive(true)
                        .createdAt(legacyToMigrate != null && legacyToMigrate.getCreatedAt() != null ? legacyToMigrate.getCreatedAt() : LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                hasAdded = true;
            }

            companiesToSave.put(company.getId(), company);
            validProfileIds.add(profile.getId());
            if (profile.getCompanyId() != null) {
                validProfileIds.add(profile.getCompanyId());
            }
        }

        for (TrackedCompany company : companiesToSave.values()) {
            trackedCompanyRepository.save(company);
            log.info("Saved synced TrackedCompany for: {} (display: {})", company.getCompanyName(), company.getDisplayName());
        }

        // Deactivate any tracked company not present in canonical CompanyProfiles
        List<TrackedCompany> allExisting = trackedCompanyRepository.findAll();
        for (TrackedCompany tc : allExisting) {
            boolean matches = companiesToSave.containsKey(tc.getId()) || validProfileIds.contains(tc.getId());
            if (!matches && Boolean.TRUE.equals(tc.getIsActive())) {
                tc.setIsActive(false);
                tc.setUpdatedAt(LocalDateTime.now());
                trackedCompanyRepository.save(tc);
                log.info("Deactivated non-canonical TrackedCompany: {}", tc.getCompanyName());
            }
        }

        if (hasAdded || !companiesToSave.isEmpty()) {
            trackedCompanyCache.forceRefresh();
        }
        log.info("CompanyTrackingSyncRunner completed.");
    }
}
