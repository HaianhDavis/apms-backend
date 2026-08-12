package com.apms.domain.intelligence.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Stable, nullable-safe read model for the Owner Company Intelligence drawer. */
public record OwnerCompanyIntelligenceResponse(
        Company company,
        Relationship relationship,
        ExecutiveBrief executiveBrief,
        AiSummary aiSummary,
        List<News> news,
        List<TimelineEvent> timeline,
        List<MarketExpansion> marketExpansion,
        List<HiringSignal> hiring,
        List<FinancialSignal> financial,
        List<Person> leadership,
        List<Product> products,
        List<Evidence> evidence,
        Metadata metadata) {

    public record Company(String id, String name, String legalName, String ticker, String website,
                          String headquarters, List<String> industries, List<String> markets,
                          String businessModel, Integer employeeCount) {}
    public record Relationship(String type, String businessImpact, String strategicRelevance,
                               String impactTrend, List<Evidence> evidence) {}
    public record ExecutiveBrief(String summary, List<String> whyItMatters, Integer confidence) {}
    public record AiSummary(boolean available, String content, String status) {}
    public record News(String id, String title, String summary, String content, String source,
                       String sourceUrl, LocalDateTime publishedAt, String sentiment,
                       List<String> topics, List<String> companyIds, String businessImpact,
                       String aiSummary) {}
    public record TimelineEvent(String id, LocalDateTime date, String eventType, String summary,
                                String impact, String source, String sourceUrl) {}
    public record MarketExpansion(String market, String eventType, String description,
                                  String businessImpact, String source, String sourceUrl,
                                  LocalDateTime date) {}
    public record HiringSignal(String title, String trend, String description, String source,
                               String sourceUrl, LocalDateTime date) {}
    public record FinancialSignal(String name, BigDecimal value, String currency, LocalDateTime period) {}
    public record Person(String name, String position, String sourceUrl, LocalDateTime researchedAt) {}
    public record Product(String name, String category, String description) {}
    public record Evidence(String sourceName, String sourceType, String sourceUrl,
                           LocalDateTime publishedAt, LocalDateTime retrievedAt, String reliability) {}
    public record Metadata(LocalDateTime lastUpdated, String dataQuality) {}
}
