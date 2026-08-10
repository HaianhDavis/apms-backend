package com.apms.domain.dashboard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RelationshipClosenessDistributionDto {
    private Integer stars;
    private String label;
    private long count;
}
