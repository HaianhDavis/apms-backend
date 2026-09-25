package com.apms.domain.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileVisibilitySummaryDto {
    private long totalProfiles;
    private long published;
    private long hidden;
    private long blockedFromPublishing;
}
