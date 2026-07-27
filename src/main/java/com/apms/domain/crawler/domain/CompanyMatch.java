package com.apms.domain.crawler.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedded document representing a detected company match within an article.
 * One article may have multiple CompanyMatch entries (multi-company detection).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyMatch {

    /**
     * Reference to the TrackedCompany document ID.
     */
    private String companyId;

    /**
     * The matched company name (denormalized for fast display).
     */
    private String companyName;

    /**
     * AI confidence score between 0.0 and 1.0.
     * - 1.0 = exact name match in title
     * - 0.8+ = strong semantic match
     * - 0.5â€“0.8 = indirect association
     * - below 0.5 = weak/discarded
     */
    private Double confidenceScore;

    /**
     * Human-readable reason for the match.
     * Example: "Article mentions Azure cloud expansion, a Microsoft subsidiary"
     */
    private String matchReason;

    /**
     * How the match was detected:
     * EXACT   â€” company name or alias found literally in text
     * ALIAS   â€” matched via known alias/subsidiary/product
     * SEMANTIC â€” AI inferred relationship without exact match
     */
    private String matchType;
}

