package com.apms.domain.ai.dto;

import com.apms.common.enums.MergeAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldEvidence {

    /**
     * Dot-path to the field, e.g. "identity.legalName", "business.industries"
     */
    private String fieldPath;

    /**
     * The value proposed by the new extraction(s).
     */
    private Object proposedValue;

    /**
     * The current existing value in the profile or candidate baseline. Null for new companies.
     */
    private Object existingValue;

    private List<String> sourceDocumentIds;
    private List<String> importJobIds;
    private List<String> extractionIds;

    private Double confidence;

    private MergeAction mergeAction;

    @Builder.Default
    private Boolean conflict = false;

    private String note;
}
