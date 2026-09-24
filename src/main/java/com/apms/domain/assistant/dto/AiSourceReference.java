package com.apms.domain.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSourceReference {

    /**
     * Type of source: "company_profiles", "neo4j", "score_snapshots", "projects".
     */
    private String type;

    /**
     * The ID of the source document/record.
     */
    private String id;

    /**
     * Human-readable title, e.g. company name or "Score Snapshot 2026-06-18".
     */
    private String title;
}
