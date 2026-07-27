package com.apms.domain.score.draft;

import com.apms.domain.score.enums.GenerationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerSuggestionGenerationMetadata {
    private String generationId;
    private String criterionKey;
    private Integer draftRevisionNumber;
    private String sourceSnapshotHash;
    private List<String> sortedSourceReferenceIds;
    private String provider;
    private String model;
    private String promptVersion;
    private GenerationStatus validationStatus;
    private LocalDateTime generatedAt;
    private LocalDateTime appliedAt;
    private String suggestionId;
    private String resultHash;
}
