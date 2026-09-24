package com.apms.domain.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Dedicated persistence document storing the current APMS Owner Organization mapping.
 * Exactly one owner enterprise exists in the system.
 */
@Document(collection = "owner_organization")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerOrganization {

    @Id
    @Builder.Default
    private String id = "CURRENT_OWNER";

    private String ownerCompanyProfileId;

    private LocalDateTime configuredAt;

    private String configuredBy;
}
