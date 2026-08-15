package com.apms.domain.intelligence.service;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.intelligence.dto.OwnerCompanyIntelligenceResponse;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class OwnerCompanyIntelligenceService {
    private final CompanyProfileRepository profileRepository;
    private final ExternalDataRepository externalDataRepository;

    public OwnerCompanyIntelligenceResponse get(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found"));
        var identity = profile.getIdentity();
        var business = profile.getBusiness();
        List<ExternalDataItem> externalItems = Stream.concat(
                        externalDataRepository.findByRelatedCompanyId(profile.getCompanyId()).stream(),
                        externalDataRepository.findByRelatedCompanyId(profile.getId()).stream())
                .filter(item -> item.getId() != null)
                .collect(java.util.stream.Collectors.toMap(ExternalDataItem::getId, item -> item, (left, right) -> left))
                .values().stream()
                .sorted(Comparator.comparing(this::signalDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(java.util.stream.Collectors.toMap(
                        this::signalIdentity,
                        item -> item,
                        (newer, duplicate) -> newer,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .toList();
        List<OwnerCompanyIntelligenceResponse.News> news = externalItems.stream()
                .filter(item -> item.getCategory() == com.apms.common.enums.ExternalDataCategory.NEWS)
                .map(item -> new OwnerCompanyIntelligenceResponse.News(
                        item.getId(), item.getTitle(), item.getSummary(), null, item.getSource(), item.getUrl(),
                        signalDate(item), item.getSentiment(), List.of(), List.of(profile.getCompanyId()),
                        firstNonBlank(item.getRiskLevel(), item.getOpportunityLevel()), item.getSummary()))
                .toList();
        // The competitive timeline is a projection of every verified external
        // signal, including NEWS. The detailed news panel consumes `news`,
        // while the timeline consumes `timeline`; both refer to the same
        // canonical ExternalDataItem record by id.
        List<OwnerCompanyIntelligenceResponse.TimelineEvent> timeline = externalItems.stream()
                .map(item -> new OwnerCompanyIntelligenceResponse.TimelineEvent(
                        item.getId(), signalDate(item), item.getCategory() == null ? "EXTERNAL_SIGNAL" : item.getCategory().name(),
                        firstNonBlank(item.getSummary(), item.getTitle()),
                        firstNonBlank(item.getRiskLevel(), item.getOpportunityLevel()), item.getSource(), item.getUrl()))
                .toList();
        String businessImpact = highestImpact(externalItems);
        String strategicRelevance = externalItems.isEmpty() ? null : businessImpact;
        String overview = buildExecutiveOverview(profile, externalItems);
        var company = new OwnerCompanyIntelligenceResponse.Company(
                profile.getCompanyId(), name(profile), identity == null ? null : identity.getLegalName(),
                null, null, null,
                business == null || business.getIndustries() == null ? List.of() : business.getIndustries(),
                business == null || business.getMarkets() == null ? List.of() : business.getMarkets(),
                business == null ? null : business.getBusinessModel(),
                profile.getCompanySize() == null ? null : profile.getCompanySize().getEmployeeCount());
        return new OwnerCompanyIntelligenceResponse(company,
                new OwnerCompanyIntelligenceResponse.Relationship(null, businessImpact, strategicRelevance, null, List.of()),
                new OwnerCompanyIntelligenceResponse.ExecutiveBrief(overview, executiveReasons(profile, externalItems), null),
                new OwnerCompanyIntelligenceResponse.AiSummary(false, null, "NO_DATA"),
                news, timeline, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new OwnerCompanyIntelligenceResponse.Metadata(
                        externalItems.stream().map(this::signalDate).filter(java.util.Objects::nonNull).findFirst().orElse(null),
                        profile.getReviewStatus()));
    }

    private String name(CompanyProfile profile) {
        if (profile.getIdentity() == null) return profile.getCompanyId();
        return StringUtils.hasText(profile.getIdentity().getTradeName())
                ? profile.getIdentity().getTradeName() : profile.getIdentity().getLegalName();
    }

    private LocalDateTime signalDate(ExternalDataItem item) {
        return item.getPublishedAt() != null ? item.getPublishedAt()
                : item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt();
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : StringUtils.hasText(second) ? second : null;
    }

    /**
     * Crawler runs can ingest the same publication more than once under
     * different Mongo document ids. Prefer the canonical URL when present;
     * otherwise use stable editorial fields without collapsing distinct news.
     */
    private String signalIdentity(ExternalDataItem item) {
        String url = normalizeIdentityValue(item.getUrl());
        if (StringUtils.hasText(url)) {
            return "url:" + url;
        }
        return "article:"
                + normalizeIdentityValue(item.getTitle()) + "|"
                + normalizeIdentityValue(item.getSource()) + "|"
                + (signalDate(item) == null ? "" : signalDate(item).toLocalDate());
    }

    private String normalizeIdentityValue(String value) {
        return StringUtils.hasText(value)
                ? value.trim().replaceAll("/+$", "").toLowerCase(Locale.ROOT)
                : "";
    }

    private String highestImpact(List<ExternalDataItem> items) {
        return items.stream()
                .flatMap(item -> Stream.of(item.getRiskLevel(), item.getOpportunityLevel()))
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase())
                .filter(value -> List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(value))
                .max(Comparator.comparingInt(this::impactRank))
                .orElse(null);
    }

    private int impactRank(String impact) {
        return switch (impact) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private String buildExecutiveOverview(CompanyProfile profile, List<ExternalDataItem> items) {
        String companyName = name(profile);
        String industry = profile.getBusiness() != null && profile.getBusiness().getIndustries() != null
                && !profile.getBusiness().getIndustries().isEmpty() ? profile.getBusiness().getIndustries().get(0) : null;
        String latestSignal = items.isEmpty() ? null : firstNonBlank(items.get(0).getSummary(), items.get(0).getTitle());
        if (StringUtils.hasText(latestSignal)) {
            return companyName + (StringUtils.hasText(industry) ? " hoạt động trong lĩnh vực " + industry + ". " : ". ")
                    + "Tín hiệu đã ghi nhận gần nhất: " + latestSignal;
        }
        return StringUtils.hasText(industry)
                ? companyName + " hoạt động trong lĩnh vực " + industry + "."
                : companyName + " có hồ sơ doanh nghiệp đã được xác minh trong APMS.";
    }

    private List<String> executiveReasons(CompanyProfile profile, List<ExternalDataItem> items) {
        List<String> reasons = new java.util.ArrayList<>();
        if (profile.getBusiness() != null && profile.getBusiness().getMarkets() != null && !profile.getBusiness().getMarkets().isEmpty()) {
            reasons.add("Thị trường hoạt động: " + String.join(", ", profile.getBusiness().getMarkets()) + ".");
        }
        if (!items.isEmpty()) {
            reasons.add("Có " + items.size() + " tín hiệu bên ngoài được liên kết với hồ sơ doanh nghiệp.");
        }
        return reasons;
    }
}
