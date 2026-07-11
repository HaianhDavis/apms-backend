package com.apms.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "apms.owner")
public class OwnerOrganizationProperties {
    
    /**
     * The ID of the CompanyProfile that represents the APMS Owner Organization.
     * Default is the legacy demo ID to preserve current runtime behavior.
     */
    @NotBlank(message = "apms.owner.company-profile-id must not be blank")
    private String companyProfileId = "6a31a0000000000000000000";
}
