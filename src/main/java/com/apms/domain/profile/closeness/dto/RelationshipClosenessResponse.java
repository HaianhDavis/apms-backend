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
    private String ratedByRole;
    private LocalDateTime ratedAt;
    private LocalDateTime updatedAt;
    private boolean ownerFinalized;
    private Integer managerStars;
    private String managerNote;
    private Long managerRatedByAccountId;
    private LocalDateTime managerRatedAt;
    private Integer ownerStars;
    private String ownerNote;
    private Long ownerRatedByAccountId;
    private LocalDateTime ownerRatedAt;
    private boolean canUpdate;
    private boolean canDelete;
}
