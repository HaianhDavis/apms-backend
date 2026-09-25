package com.apms.domain.profile.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class OwnerProfileReadinessResponse {
    private String companyProfileId;
    private String profileVersion;
    private boolean approved;
    private List<String> completedSections;
    private List<String> missingSections;
    private boolean readyForComparison;
}
