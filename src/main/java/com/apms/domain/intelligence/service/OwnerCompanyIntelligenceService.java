package com.apms.domain.intelligence.service;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.intelligence.dto.OwnerCompanyIntelligenceResponse;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.common.enums.ProjectStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class OwnerCompanyIntelligenceService {
    private final CompanyProfileRepository profileRepository;
    private final ExternalDataRepository externalDataRepository;
    private final GraphService graphService;
    private final ProjectRepository projectRepository;

    public OwnerCompanyIntelligenceResponse get(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found"));
        List<ExternalDataItem> articles = articlesFor(profile).stream()
                .sorted(Comparator.comparing(this::eventDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<OwnerCompanyIntelligenceResponse.News> news = articles.stream().map(this::news).toList();
        List<OwnerCompanyIntelligenceResponse.TimelineEvent> timeline = articles.stream().map(this::timeline).toList();
        List<OwnerCompanyIntelligenceResponse.MarketExpansion> marketExpansion = articles.stream()
                .filter(item -> topics(item).contains("MARKET_EXPANSION")).map(this::market).toList();
        List<OwnerCompanyIntelligenceResponse.HiringSignal> hiring = articles.stream()
                .filter(item -> topics(item).contains("HIRING")).map(this::hiring).toList();
        List<String> profileIds = java.util.stream.Stream.of(profile.getId(), profile.getCompanyId())
                .filter(StringUtils::hasText).distinct().toList();
        List<Project> activeProjects = profileIds.isEmpty() ? List.of()
                : projectRepository.findByTargetCompanyProfileIdInAndStatus(profileIds, ProjectStatus.ACTIVE);
        List<OwnerCompanyIntelligenceResponse.Evidence> articleEvidence = articles.stream().map(this::evidence).toList();
        List<OwnerCompanyIntelligenceResponse.Evidence> projectEvidence = activeProjects.stream().map(this::projectEvidence).toList();
        List<OwnerCompanyIntelligenceResponse.Evidence> evidence = java.util.stream.Stream.concat(articleEvidence.stream(), projectEvidence.stream()).toList();

        String relationship = relationshipFor(profile.getCompanyId());
        String impact = articles.stream().map(this::impact).filter(StringUtils::hasText).findFirst().orElse(null);
        String strategicRelevance = null;
        String impactTrend = null;
        LocalDateTime updated = articles.stream().map(this::eventDate).filter(java.util.Objects::nonNull).findFirst()
                .orElse(profile.getMetadata() == null ? null : profile.getMetadata().getUpdatedAt());
        OwnerCompanyIntelligenceResponse.ExecutiveBrief executiveBrief = executiveBrief(relationship, activeProjects, articles);
        return new OwnerCompanyIntelligenceResponse(
                company(profile), new OwnerCompanyIntelligenceResponse.Relationship(relationship, impact, strategicRelevance, impactTrend, evidence),
                executiveBrief, aiSummary(articles), news, timeline,
                marketExpansion, hiring, financial(profile), leadership(profile), products(profile), evidence,
                new OwnerCompanyIntelligenceResponse.Metadata(updated, profile.getReviewStatus()));
    }

    /**
     * Older crawler runs were linked by either Mongo profile id, graph company id, or
     * the company name. Keep those records visible while new runs use the canonical ids.
     * This is deliberately an in-memory fallback: it is only used for one Owner drawer
     * and avoids turning a partial name into a broad, unverified Mongo query.
     */
    private List<ExternalDataItem> articlesFor(CompanyProfile profile) {
        Map<String, ExternalDataItem> matches = new LinkedHashMap<>();
        externalDataRepository.findByCompanyProfileIdOrRelatedCompanyId(profile.getId(), profile.getCompanyId())
                .forEach(item -> matches.put(item.getId(), item));

        Set<String> aliases = aliases(profile);
        if (!aliases.isEmpty()) {
            externalDataRepository.findAll().stream()
                    .filter(item -> aliases.contains(normalize(item.getRelatedCompanyName())))
                    .forEach(item -> matches.put(item.getId(), item));
        }
        return List.copyOf(matches.values());
    }

    private Set<String> aliases(CompanyProfile profile) {
        Set<String> values = new LinkedHashSet<>();
        if (profile.getIdentity() == null) return values;
        addAlias(values, profile.getIdentity().getLegalName());
        addAlias(values, profile.getIdentity().getTradeName());
        addAlias(values, profile.getIdentity().getStockTicker());
        return values;
    }

    private void addAlias(Set<String> aliases, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) aliases.add(normalized);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").trim().replaceAll("\\s+", " ");
    }

    private OwnerCompanyIntelligenceResponse.Company company(CompanyProfile p) {
        var identity = p.getIdentity(); var contact = p.getContact(); var business = p.getBusiness();
        String headquarters = contact == null || contact.getAddresses() == null ? null : contact.getAddresses().stream()
                .map(CompanyProfile.Address::getFullAddress).filter(StringUtils::hasText).findFirst().orElse(null);
        return new OwnerCompanyIntelligenceResponse.Company(p.getCompanyId(), name(p), identity == null ? null : identity.getLegalName(),
                identity == null ? null : identity.getStockTicker(), contact == null ? null : contact.getWebsite(), headquarters,
                business == null || business.getIndustries() == null ? List.of() : business.getIndustries(),
                business == null || business.getMarkets() == null ? List.of() : business.getMarkets(),
                business == null ? null : business.getBusinessModel(), p.getCompanySize() == null ? null : p.getCompanySize().getEmployeeCount());
    }

    private OwnerCompanyIntelligenceResponse.News news(ExternalDataItem item) {
        return new OwnerCompanyIntelligenceResponse.News(item.getId(), clean(item.getTitle()), clean(item.getAiSummary()), clean(item.getSummary()),
                clean(item.getSource()), safeUrl(item.getUrl()), item.getPublishedAt(), item.getSentiment(), topics(item),
                Stream.of(item.getCompanyProfileId(), item.getRelatedCompanyId())
                        .filter(StringUtils::hasText)
                        .toList(), impact(item), clean(item.getAiSummary()));
    }

    private OwnerCompanyIntelligenceResponse.AiSummary aiSummary(List<ExternalDataItem> articles) {
        String content = articles.stream()
                .map(ExternalDataItem::getAiSummary)
                .map(this::clean)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        return new OwnerCompanyIntelligenceResponse.AiSummary(
                content != null,
                content,
                content != null ? "AVAILABLE" : "NO_DATA");
    }
    private OwnerCompanyIntelligenceResponse.TimelineEvent timeline(ExternalDataItem item) {
        String type = topics(item).stream().filter(t -> !"COMPANY_NEWS".equals(t)).findFirst().orElse("NEWS");
        return new OwnerCompanyIntelligenceResponse.TimelineEvent(item.getId(), eventDate(item), type, clean(item.getAiSummary() != null ? item.getAiSummary() : item.getSummary()), impact(item), clean(item.getSource()), safeUrl(item.getUrl()));
    }
    private OwnerCompanyIntelligenceResponse.MarketExpansion market(ExternalDataItem item) {
        return new OwnerCompanyIntelligenceResponse.MarketExpansion(clean(item.getRelatedCompanyName()), "MARKET_EXPANSION", clean(item.getAiSummary() != null ? item.getAiSummary() : item.getSummary()), impact(item), clean(item.getSource()), safeUrl(item.getUrl()), eventDate(item));
    }
    private OwnerCompanyIntelligenceResponse.HiringSignal hiring(ExternalDataItem item) {
        return new OwnerCompanyIntelligenceResponse.HiringSignal(clean(item.getTitle()), null, clean(item.getAiSummary() != null ? item.getAiSummary() : item.getSummary()), clean(item.getSource()), safeUrl(item.getUrl()), eventDate(item));
    }
    private OwnerCompanyIntelligenceResponse.Evidence evidence(ExternalDataItem item) {
        return new OwnerCompanyIntelligenceResponse.Evidence(clean(item.getSource()), "NEWS", safeUrl(item.getUrl()), item.getPublishedAt(), item.getCreatedAt(), "UNASSESSED");
    }
    private OwnerCompanyIntelligenceResponse.Evidence projectEvidence(Project project) {
        return new OwnerCompanyIntelligenceResponse.Evidence("APMS Project: " + project.getProjectName(), "OTHER", null,
                project.getUpdatedAt(), project.getUpdatedAt(), "UNASSESSED");
    }
    private List<OwnerCompanyIntelligenceResponse.FinancialSignal> financial(CompanyProfile p) {
        if (p.getFinancial() == null) return List.of();
        var f = p.getFinancial(); LocalDateTime date = p.getMetadata() == null ? null : p.getMetadata().getUpdatedAt();
        return List.of(signal("Revenue", f.getRevenue(), f.getRevenueCurrency(), date), signal("Revenue growth", f.getRevenueGrowth(), "%", date), signal("Debt ratio", f.getDebtRatio(), "%", date), signal("Profit margin", f.getProfitMargin(), "%", date)).stream().filter(java.util.Objects::nonNull).toList();
    }
    private OwnerCompanyIntelligenceResponse.FinancialSignal signal(String name, BigDecimal value, String currency, LocalDateTime date) { return value == null ? null : new OwnerCompanyIntelligenceResponse.FinancialSignal(name, value, currency, date); }
    private List<OwnerCompanyIntelligenceResponse.Person> leadership(CompanyProfile p) { return p.getCompanyMembers() == null ? List.of() : p.getCompanyMembers().stream().map(m -> new OwnerCompanyIntelligenceResponse.Person(m.getFullName(), m.getPosition(), safeUrl(m.getSourceUrl()), m.getResearchedAt())).toList(); }
    private List<OwnerCompanyIntelligenceResponse.Product> products(CompanyProfile p) { return p.getBusiness() == null || p.getBusiness().getProducts() == null ? List.of() : p.getBusiness().getProducts().stream().map(x -> new OwnerCompanyIntelligenceResponse.Product(x.getName(), x.getCategory(), x.getDescription())).toList(); }
    private OwnerCompanyIntelligenceResponse.ExecutiveBrief executiveBrief(String relationship,
                                                                            List<Project> activeProjects, List<ExternalDataItem> articles) {
        List<String> reasons = new java.util.ArrayList<>();
        if (StringUtils.hasText(relationship)) reasons.add("Quan hệ đã ghi nhận: " + relationship + ".");
        if (!activeProjects.isEmpty()) reasons.add("Có " + activeProjects.size() + " project ACTIVE đang liên kết với doanh nghiệp này.");
        articles.stream().findFirst().ifPresent(item -> {
            String signal = clean(item.getAiSummary() != null ? item.getAiSummary() : item.getSummary());
            if (StringUtils.hasText(signal)) reasons.add("Tín hiệu đã ghi nhận: " + signal);
        });
        if (reasons.isEmpty()) return new OwnerCompanyIntelligenceResponse.ExecutiveBrief(null, List.of(), null);

        String summary = String.join(" ", reasons);
        return new OwnerCompanyIntelligenceResponse.ExecutiveBrief(summary, List.copyOf(reasons), null);
    }

    /**
     * Deliberately uses only recorded relationship and ACTIVE project evidence.
     * It does not reinterpret legacy fit, threat, or risk scores as strategic relevance.
     */
    private String strategicRelevance(String relationship, List<Project> activeProjects) {
        if (!activeProjects.isEmpty() && ("PARTNER".equals(relationship) || "SUPPLIER".equals(relationship))) return "HIGH";
        if (!activeProjects.isEmpty() || "PARTNER".equals(relationship) || "SUPPLIER".equals(relationship)
                || "COMPETITOR".equals(relationship)) return "MEDIUM";
        return null;
    }

    /** Returns a directional trend only when recorded topic history supports it. */
    private String impactTrend(List<ExternalDataItem> articles) {
        LocalDateTime recentSince = LocalDateTime.now().minusDays(90);
        List<ExternalDataItem> recentArticles = articles.stream()
                .filter(item -> eventDate(item) != null && !eventDate(item).isBefore(recentSince)).toList();
        boolean contraction = recentArticles.stream().anyMatch(item -> hasAnyTopic(item, "MARKET_EXIT", "CONTRACTION", "DISCONTINUATION"));
        if (contraction) return "DECREASING";
        long expansionSignals = recentArticles.stream().filter(item -> hasAnyTopic(item,
                "MARKET_EXPANSION", "HIRING", "STRATEGIC_ACTIVITY", "THREAT")).count();
        return expansionSignals >= 2 ? "INCREASING" : null;
    }

    private boolean hasAnyTopic(ExternalDataItem item, String... expectedTopics) {
        Set<String> values = topics(item).stream().filter(StringUtils::hasText)
                .map(value -> value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        for (String topic : expectedTopics) if (values.contains(topic)) return true;
        return false;
    }

    private String relationshipFor(String id) {
        if (graphService.getCompaniesByRelationshipType("COMPETITOR_OF").stream().anyMatch(x -> id.equals(x.getCompanyId()))) return "COMPETITOR";
        if (graphService.getCompaniesByRelationshipType("PARTNER_WITH").stream().anyMatch(x -> id.equals(x.getCompanyId()))) return "PARTNER";
        if (graphService.getCompaniesByRelationshipType("SUPPLIER_OF").stream().anyMatch(x -> id.equals(x.getCompanyId()))) return "SUPPLIER";
        if (graphService.getCompaniesByRelationshipType("CUSTOMER_OF").stream().anyMatch(x -> id.equals(x.getCompanyId()))) return "CUSTOMER";
        return null;
    }
    private List<String> topics(ExternalDataItem item) { return item.getTopics() == null ? List.of() : item.getTopics(); }
    private String impact(ExternalDataItem item) { return StringUtils.hasText(item.getRiskLevel()) ? item.getRiskLevel() : item.getOpportunityLevel(); }
    private LocalDateTime eventDate(ExternalDataItem item) { return item.getPublishedAt() != null ? item.getPublishedAt() : item.getCreatedAt(); }
    private String name(CompanyProfile p) { return p.getIdentity() == null ? null : StringUtils.hasText(p.getIdentity().getTradeName()) ? p.getIdentity().getTradeName() : p.getIdentity().getLegalName(); }
    private String safeUrl(String value) { return value != null && (value.startsWith("https://") || value.startsWith("http://")) ? value : null; }
    private String clean(String value) { if (!StringUtils.hasText(value)) return null; return value.replaceAll("(?is)<script.*?</script>|<style.*?</style>", "").replaceAll("(?i)<br\\s*/?>|</p>", " ").replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim(); }
}
