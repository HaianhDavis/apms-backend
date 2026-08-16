package com.apms.domain.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MongoDB document representing the official, factual company profile.
 * Does NOT contain classification/relationship data (that lives in Neo4j).
 *
 * <h3>Semantic Boundary: Factual vs AI-Generated Data</h3>
 * <p>This document contains two distinct categories of data:</p>
 * <ul>
 *   <li><b>Factual fields</b> ({@code identity}, {@code business}, {@code companySize},
 *       {@code contact}, {@code financial}, {@code market}, {@code innovation},
 *       {@code risk}, {@code compliance}): Authoritative business data sourced from
 *       verified documents and human review. Changes to these fields require the
 *       established review/approval workflow (candidate review → profile apply).</li>
 *   <li><b>AI-generated/advisory fields</b> ({@code insights}): SWOT analysis produced
 *       by AI extraction. These are advisory outputs that assist human evaluation but
 *       are not authoritative business facts. AI output must not silently overwrite
 *       factual fields.</li>
 * </ul>
 * <p>Immutable snapshots for evaluation purposes are stored separately in
 * {@code CompanyProfileVersion}.</p>
 *
 * Collection: company_profiles
 */
@Document(collection = "company_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfile {

    @Id
    private String id;

    /**
     * Universal company identity (UUID).
     * Used as the primary key in Neo4j (CompanyNode) and SQL Server (score_snapshots).
     */
    @Indexed(unique = true)
    private String companyId;

    // ─────────────────────────────────────────────────────────────
    // Factual Company Data — authoritative business information
    // sourced from verified documents. Changes require the
    // established candidate review/approval workflow.
    // ─────────────────────────────────────────────────────────────

    private Identity identity;
    private Business business;
    private CompanySize companySize;
    private Contact contact;

    /**
     * AI-generated SWOT analysis (strengths, weaknesses, opportunities, threats).
     * <p>This field is <b>advisory</b>, not authoritative business data. It is
     * populated during AI extraction and must not be used to silently overwrite
     * any factual profile fields. Consumers should treat this as supplementary
     * context for human decision-making.</p>
     */
    private Insights insights;

    private com.apms.domain.company.model.FinancialInfo financial;
    private com.apms.domain.company.model.MarketInfo market;
    private com.apms.domain.company.model.InnovationInfo innovation;
    private com.apms.domain.company.model.RiskInfo risk;
    private com.apms.domain.company.model.ComplianceInfo compliance;

    @Builder.Default
    private List<CompanyMember> companyMembers = new ArrayList<>();

    /**
     * Per-year financial statements of the Owner Organization, embedded so the
     * Owner and SYSTEM_ADMIN share the same data source.
     * Each entry stores one report type (e.g. SUMMARY, BALANCE_SHEET) for one
     * financial year; the same (reportType, reportYear) is unique per profile.
     */
    @Builder.Default
    private List<FinancialReport> financialReports = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────
    // Profile Metadata
    // ─────────────────────────────────────────────────────────────

    /**
     * The ID of the Business Development Manager responsible for this company profile.
     * Established via Project ownership, enabling exclusive authorization for
     * managing Company Continuous Monitoring.
     */
    private Long responsibleManagerId;

    @Builder.Default
    private SourceRefs sourceRefs = new SourceRefs();

    private String reviewStatus; // e.g., VERIFIED, UNVERIFIED, NEEDS_UPDATE

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private Boolean isDeleted = false;

    @Builder.Default
    private Boolean isHidden = false;

    private Metadata metadata;

    @Builder.Default
    private Integer version = 1;

    // ─────────────────────────────────────────────────────────────
    // Nested Classes (mirrors or extends Candidate data structures)
    // ─────────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Identity {
        @TextIndexed
        private String legalName;
        private String tradeName;
        
        @Indexed(unique = true, sparse = true)
        private String taxCode;
        
        @Indexed(unique = true, sparse = true)
        private String registrationNumber;
        
        private String stockTicker;
        private String stockExchange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Business {
        private java.util.List<String> industries;
        private String businessModel;
        private java.util.List<Product> products;
        private java.util.List<String> markets;
        private java.util.List<String> targetCustomers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Product {
        private String name;
        private String category;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanySize {
        private String employeeTier;
        private Integer employeeCount;
        private String revenueTier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Contact {
        private String website;
        private java.util.List<String> emails;
        private java.util.List<String> phones;
        private java.util.List<Address> addresses;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address {
        private String type;
        private String fullAddress;
        private String city;
        private String country;
    }

    /**
     * AI-generated SWOT analysis. This is an advisory output produced by the
     * AI extraction pipeline and is not authoritative business data.
     * <p>Each list contains qualitative observations. These should inform
     * human evaluation but must not be treated as verified facts or used to
     * derive official scores without human review.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Insights {
        private java.util.List<String> strengths;
        private java.util.List<String> weaknesses;
        private java.util.List<String> opportunities;
        private java.util.List<String> threats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceRefs {
        @Builder.Default
        private Set<String> projectIds = new HashSet<>();

        @Builder.Default
        private Set<String> importJobIds = new HashSet<>();

        @Builder.Default
        private Set<String> rawDocumentIds = new HashSet<>();

        @Builder.Default
        private Set<String> candidateIds = new HashSet<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metadata {
        private String createdBy;
        private LocalDateTime createdAt;
        private String lastModifiedBy;
        private LocalDateTime updatedAt;
        private LocalDateTime deletedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyMember {
        private String fullName;
        private String position;
        private String imageUrl;
        private String sourceUrl;
        private String notes;
        private LocalDateTime researchedAt;
        private Long researchedBy;
        private Long taskId;
    }

    /**
     * One financial statement (reportType + reportYear) embedded in the profile.
     * {@code itemsJson} is a JSON {@code FinancialDocument} describing a single
     * period: { unit, templace:[{code,name}], data:[{data:[{time, data:[{code,value}]}]}] }.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinancialReport {
        private String reportType;
        private String periodType;
        private Integer reportYear;
        private String reportPeriod;
        private String itemsJson;
        private String sourceUrl;
    }
}
