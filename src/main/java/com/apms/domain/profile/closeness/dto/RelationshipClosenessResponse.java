package com.apms.domain.profile.closeness.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RelationshipClosenessResponse {
    private String targetCompanyProfileId;
    private Integer stars;
    private String label;
    private String note;
    private Long ratedByAccountId;
    private LocalDateTime ratedAt;
    private LocalDateTime updatedAt;
}
