package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Strict typed DTO for normalized terms to prevent unrestricted Maps.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractClauseTerms {
    private List<String> governingLaw;
    private List<String> permittedUses;
    private List<String> restrictions;
    private String liabilityCap;
    private String confidentialityDuration;
    private String indemnificationScope;
    private String terminationConditions;
    private Boolean autoRenewal;
    private String paymentTerms;
    private String otherSpecificTerms;
}
