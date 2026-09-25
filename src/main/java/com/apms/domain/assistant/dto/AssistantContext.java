package com.apms.domain.assistant.dto;

import com.apms.domain.graph.dto.CompanyRelationshipDto;
import com.apms.domain.profile.CompanyProfile;
//import com.apms.domain.score.ScoreSnapshot;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Internal context object assembled exclusively from approved APMS data.
 * This is NOT exposed in the API response directly.
 *
 * Sources used (approved only):
 *  - company_profiles (MongoDB)
 *  - Neo4j company relationships
 *  - score_snapshots (SQL Server)
 *
 * Sources intentionally excluded:
 *  - raw_documents
 *  - ai_extraction_results
 *  - company_candidates (draft/pending/rejected/corrected)
 */
@Data
@Builder
public class AssistantContext {

    private Long projectId;

    /** The approved CompanyProfile from MongoDB. Primary data source. */
    private CompanyProfile companyProfile;

    /** Approved formatted relationship strings from Neo4j for this company. */
    private List<String> formattedRelationships;

    /** The most recent ScoreSnapshot from SQL Server. Null if none exists. */
//    private ScoreSnapshot latestScore;

    /** Formatted text representation of all context for the AI prompt. */
    private String contextText;

    /** Source references for the response. */
    private List<AiSourceReference> sources;
}
