package com.apms.domain.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMentionDto {

    /**
     * Entity type: "PROJECT" or "COMPANY".
     */
    private String type;

    /**
     * Explicit project identifier for PROJECT mentions.
     */
    private Long projectId;

    /**
     * Canonical MongoDB CompanyProfile.id for COMPANY mentions.
     */
    private String companyProfileId;

    /**
     * Canonical business companyId for COMPANY mentions.
     */
    private String companyId;

    /**
     * Display label (exact projectName for Project, exact identity.legalName for Company).
     */
    private String label;

    /**
     * Trigger character ("!" for Project, "@" for Company).
     */
    private String trigger;

    /**
     * Optional mention span range start index in question text.
     */
    private Integer startIndex;

    /**
     * Optional mention span range end index in question text.
     */
    private Integer endIndex;
}
