package com.apms.domain.companymember.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class CompanyMemberResearchItemRequest {
    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Position is required")
    private String position;

    @URL(message = "Image URL must be a valid URL")
    private String imageUrl;

    @NotBlank(message = "Source URL is required")
    @URL(message = "Source URL must be a valid URL")
    private String sourceUrl;

    private String notes;
}
