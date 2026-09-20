package com.apms.domain.profile.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentAssessmentItemDto {
    private Long id;
    private Integer versionNumber;
    private Integer majorVersion;
    private Integer minorRevision;
    private String formattedVersion;
    private String assessmentType;
    private Integer score;
    private String rank;
    private String rankDescription;
    private String finalizedAt; // Timezone-safe ISO-8601 string (e.g. 2026-09-17T13:20:00+07:00)
    private String actorRole;   // BUSINESS_OWNER or BUSINESS_DEVELOPMENT_MANAGER
    private Map<String, Integer> criteria;
}
