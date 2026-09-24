package com.apms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "apms.owner")
public class OwnerOrganizationProperties {

    /**
     * Optional fallback ID of the CompanyProfile that represents the APMS Owner Organization.
     * In normal operation, the Owner Organization is persisted dynamically in MongoDB.
     */
    private String companyProfileId;
}
