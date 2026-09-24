package com.apms.domain.graph.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * DTO representing a relationship edge in the Neo4j company graph.
 *
 * <p>Core fields ({@code sourceCompanyId}, {@code targetCompanyId},
 * {@code relationshipType}) are always present. Optional metadata fields
 * ({@code startDate}, {@code endDate}, {@code status}, {@code metadata})
 * are additive and default to {@code null} for backward compatibility.</p>
 */
@Data
@Builder
public class CompanyRelationshipDto {
    private String sourceCompanyId;
    private String targetCompanyId;
    private String relationshipType;
    private Double confidenceScore;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String notes;
    private String projectId;
    private String candidateId;

    /** Optional: when the relationship became effective. */
    private LocalDate startDate;

    /** Optional: when the relationship ended or is expected to end. */
    private LocalDate endDate;

    /**
     * Optional relationship lifecycle status (e.g. ACTIVE, INACTIVE, PENDING).
     * Does not affect company role classification or scoring.
     */
    private String status;

    /** Optional free-form metadata for graph traversal assistance. */
    private Map<String, Object> metadata;
}
