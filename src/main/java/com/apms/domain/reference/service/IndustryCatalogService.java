package com.apms.domain.reference.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.reference.dto.IndustryCatalogResponse;
import com.apms.domain.reference.entity.IndustryCatalog;
import com.apms.domain.reference.repository.IndustryCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryCatalogService {

    private final IndustryCatalogRepository industryCatalogRepository;
    private final MongoTemplate mongoTemplate;

    public static String normalize(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public static String cleanDisplayName(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("\\s+", " ");
    }

    public List<IndustryCatalogResponse> getActiveIndustries(String search) {
        Map<String, String> distinctMap = new LinkedHashMap<>();

        // 1. Primary source: existing non-deleted company profile industries
        try {
            Criteria criteria = Criteria.where("isDeleted").ne(true);
            List<String> profileIndustries = mongoTemplate.findDistinct(
                    Query.query(criteria),
                    "business.industries",
                    CompanyProfile.class,
                    String.class
            );
            if (profileIndustries != null) {
                for (String raw : profileIndustries) {
                    if (raw == null) continue;
                    String clean = cleanDisplayName(raw);
                    if (clean.isBlank()) continue;
                    distinctMap.putIfAbsent(clean.toLowerCase(), clean);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch distinct industries from CompanyProfile: {}", e.getMessage());
        }

        // 2. Also include canonical entries from industryCatalogRepository if any
        try {
            List<IndustryCatalog> list;
            if (StringUtils.hasText(search)) {
                list = industryCatalogRepository.findByNameContainingIgnoreCaseAndStatusOrderByNameAsc(search.trim(), "ACTIVE");
            } else {
                list = industryCatalogRepository.findByStatusOrderByNameAsc("ACTIVE");
            }
            if (list != null) {
                for (IndustryCatalog item : list) {
                    if (item.getName() != null && !item.getName().isBlank()) {
                        String clean = cleanDisplayName(item.getName());
                        if (!clean.isBlank()) {
                            distinctMap.putIfAbsent(clean.toLowerCase(), clean);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch active industries from repository: {}", e.getMessage());
        }

        String searchLower = StringUtils.hasText(search) ? search.trim().toLowerCase() : null;

        return distinctMap.values().stream()
                .filter(name -> searchLower == null || name.toLowerCase().contains(searchLower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(name -> IndustryCatalogResponse.builder()
                        .id(name)
                        .name(name)
                        .build())
                .collect(Collectors.toList());
    }

    public List<String> getDistinctActiveIndustryNames() {
        return industryCatalogRepository.findByStatusOrderByNameAsc("ACTIVE").stream()
                .map(IndustryCatalog::getName)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    public List<String> upsertApprovedIndustries(List<String> rawIndustries, String candidateId, String profileId, Long reviewerId) {
        if (rawIndustries == null || rawIndustries.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> canonicalList = new ArrayList<>();
        Set<String> seenNormalized = new HashSet<>();

        for (String raw : rawIndustries) {
            String clean = cleanDisplayName(raw);
            if (!StringUtils.hasText(clean)) {
                continue;
            }
            String norm = normalize(clean);
            if (!seenNormalized.add(norm)) {
                continue;
            }

            Optional<IndustryCatalog> existingOpt = industryCatalogRepository.findByNormalizedName(norm);
            if (existingOpt.isPresent()) {
                canonicalList.add(existingOpt.get().getName());
            } else {
                IndustryCatalog newCatalog = IndustryCatalog.builder()
                        .name(clean)
                        .normalizedName(norm)
                        .createdAt(LocalDateTime.now())
                        .createdBy(reviewerId != null ? String.valueOf(reviewerId) : "SYSTEM")
                        .sourceCompanyProfileId(profileId)
                        .sourceCandidateId(candidateId)
                        .status("ACTIVE")
                        .build();
                try {
                    newCatalog = industryCatalogRepository.save(newCatalog);
                    canonicalList.add(newCatalog.getName());
                    log.info("Promoted new canonical industry to catalog: '{}' (sourceCandidateId={})", clean, candidateId);
                } catch (DuplicateKeyException e) {
                    IndustryCatalog raceExisting = industryCatalogRepository.findByNormalizedName(norm)
                            .orElse(null);
                    if (raceExisting != null) {
                        canonicalList.add(raceExisting.getName());
                    } else {
                        canonicalList.add(clean);
                    }
                }
            }
        }
        return canonicalList;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillFromExistingProfiles() {
        try {
            Criteria criteria = Criteria.where("isDeleted").ne(true);
            List<String> profileIndustries = mongoTemplate.findDistinct(
                    Query.query(criteria),
                    "business.industries",
                    CompanyProfile.class,
                    String.class
            );

            if (profileIndustries == null || profileIndustries.isEmpty()) {
                log.info("No existing company profile industries found for backfill.");
                return;
            }

            int addedCount = 0;
            for (String raw : profileIndustries) {
                String clean = cleanDisplayName(raw);
                if (!StringUtils.hasText(clean)) continue;
                String norm = normalize(clean);
                if (!industryCatalogRepository.existsByNormalizedName(norm)) {
                    IndustryCatalog entry = IndustryCatalog.builder()
                            .name(clean)
                            .normalizedName(norm)
                            .createdAt(LocalDateTime.now())
                            .createdBy("SYSTEM")
                            .status("ACTIVE")
                            .build();
                    try {
                        industryCatalogRepository.save(entry);
                        addedCount++;
                    } catch (DuplicateKeyException ignored) {
                        // Safe on concurrent initialization
                    }
                }
            }
            log.info("IndustryCatalog backfilled from existing CompanyProfiles. Added {} new items.", addedCount);
        } catch (Exception e) {
            log.warn("Failed to backfill IndustryCatalog from CompanyProfiles: {}", e.getMessage());
        }
    }
}
