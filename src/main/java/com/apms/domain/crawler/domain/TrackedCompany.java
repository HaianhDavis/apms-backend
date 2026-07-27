package com.apms.domain.crawler.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document representing a company being tracked by the intelligence platform.
 * Administrators manage this list; the crawler dynamically loads it from the database.
 *
 * Collection: tracked_companies
 */
@Document(collection = "tracked_companies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackedCompany {

    @Id
    private String id;

    /**
     * Primary company name used for display and matching.
     * Example: "Microsoft"
     */
    @Indexed(unique = true)
    private String companyName;

    /**
     * Alternative names, ticker symbols, abbreviations.
     * Example: ["MSFT", "Microsoft Corporation", "Microsoft Corp"]
     */
    @Builder.Default
    private List<String> aliases = new ArrayList<>();

    /**
     * Known subsidiaries, divisions, and brands.
     * Example: ["Azure", "LinkedIn", "GitHub", "Xbox"]
     */
    @Builder.Default
    private List<String> subsidiaries = new ArrayList<>();

    /**
     * Key products and technologies associated with this company.
     * Example: ["Windows", "Office 365", "Copilot", "Teams"]
     */
    @Builder.Default
    private List<String> products = new ArrayList<>();

    /**
     * Key people (CEOs, founders) for semantic matching.
     * Example: ["Satya Nadella", "Bill Gates"]
     */
    @Builder.Default
    private List<String> keyPeople = new ArrayList<>();

    /**
     * Industry sector for context.
     * Example: "Technology"
     */
    private String industry;

    /**
     * Whether this company is actively being tracked.
     * Soft-delete: set to false instead of removing.
     */
    @Builder.Default
    private Boolean isActive = true;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

