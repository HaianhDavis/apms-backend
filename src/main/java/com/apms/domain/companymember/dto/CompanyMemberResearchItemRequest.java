package com.apms.domain.companymember.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class CompanyMemberResearchItemRequest {
    @NotBlank(message = "Full name is required")
    @Size(max = 200, message = "Full name must not exceed 200 characters")
    private String fullName;

    @NotBlank(message = "Position is required")
    @Size(max = 200, message = "Position must not exceed 200 characters")
    private String position;

    private String imageUrl;

    @Size(max = 1000, message = "Source URL must not exceed 1000 characters")
    @URL(message = "Source URL must be a valid URL")
    private String sourceUrl;

    private String notes;

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = (sourceUrl != null && sourceUrl.trim().isEmpty()) ? null : sourceUrl;
    }
}
