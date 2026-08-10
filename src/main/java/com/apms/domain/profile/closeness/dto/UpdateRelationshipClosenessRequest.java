package com.apms.domain.profile.closeness.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateRelationshipClosenessRequest {

    @NotNull(message = "Stars rating is required")
    @Min(value = 1, message = "Stars rating must be at least 1")
    @Max(value = 5, message = "Stars rating must be at most 5")
    private Integer stars;

    @Size(max = 1000, message = "Note cannot exceed 1000 characters")
    private String note;
}
