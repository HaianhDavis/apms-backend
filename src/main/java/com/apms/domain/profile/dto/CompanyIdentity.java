package com.apms.domain.profile.dto;

import com.apms.domain.profile.CompanyProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Encapsulates canonical company identity resolution between
 * MongoDB _id (profileId) and universal Company UUID (companyId).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyIdentity {
    private String profileId;    // Mongo _id
    private String companyId;    // Universal UUID
    private CompanyProfile profile;

    public String getCanonicalCompanyId() {
        if (companyId != null && !companyId.isBlank()) {
            return companyId;
        }
        return profileId;
    }

    public Set<String> allIdentifiers() {
        Set<String> set = new LinkedHashSet<>();
        if (companyId != null && !companyId.isBlank()) {
            set.add(companyId);
        }
        if (profileId != null && !profileId.isBlank()) {
            set.add(profileId);
        }
        return set;
    }
}
