package com.apms.domain.score.dto.draft;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class PartnerCriterionContext {
    private String criterionKey;
    private String sourceSnapshotHash;
    private Integer draftRevisionNumber;
    
    private LocalDate periodStart;
    private LocalDate periodEnd;

    @Builder.Default
    private List<Map<String, Object>> pinnedSources = new java.util.ArrayList<>();
}
