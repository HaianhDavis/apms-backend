package com.apms.domain.dashboard.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OwnerInsightDto {
    private String id;
    private InsightType type;
    private String title;
    private String summary;
    private String companyProfileId;
    private String companyName;
    private List<InsightSourceRef> sources;
    private LocalDateTime generatedAt;
    private boolean actionable;
    private String generationMethod;
}
