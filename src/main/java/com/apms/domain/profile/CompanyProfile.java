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

    @Builder.Default
    private Boolean isDeleted = false;

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
        private String taxCode;
        private String registrationNumber;
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
}
