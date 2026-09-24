package com.apms.domain.dashboard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RelationshipClosenessOverviewDto {
    private long ratedRelationshipCount;
    private long unratedRelationshipCount;
    private java.util.List<RelationshipClosenessDistributionDto> distribution;
}
