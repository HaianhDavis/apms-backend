package com.apms.domain.profile.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Whitelist DTO for SYSTEM_ADMIN to manage Leadership members of the canonical Owner Enterprise.
 * Scoped specifically to leadership member records and optimistic locking versions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateEnterpriseLeadershipRequest {

    @Valid
    private List<AdminEnterpriseLeadershipMemberRequest> members;

    private Integer expectedMajorVersion;
    private Integer expectedRevision;
}
