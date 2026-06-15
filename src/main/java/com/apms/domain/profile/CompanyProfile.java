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
    // Factual Company Data
    // ─────────────────────────────────────────────────────────────

    private Identity identity;
    private Business business;
    private CompanySize companySize;
    private Contact contact;
    private Insights insights;

    // ─────────────────────────────────────────────────────────────
    // Profile Metadata
    // ─────────────────────────────────────────────────────────────

    @Builder.Default
    private SourceRefs sourceRefs = new SourceRefs();

    private String reviewStatus; // e.g., VERIFIED, UNVERIFIED, NEEDS_UPDATE

    @Builder.Default
    private List<String> tags = new ArrayList<>();

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
        private String name;
        private String registrationNumber;
        private String taxId;
        private String legalForm;
        private String foundedYear;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Business {
        private String industry;
        private String subIndustry;
        private String description;
        private String coreProducts;
        private String marketPosition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanySize {
        private String employeeCountRange;
        private String estimatedRevenueRange;
        private String physicalLocationsCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Contact {
        private String website;
        private String primaryEmail;
        private String primaryPhone;
        private String headquartersAddress;
        private String keyExecutives;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Insights {
        private String strengths;
        private String weaknesses;
        private String opportunities;
        private String threats;
        private String strategicValue;
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
    }
}
