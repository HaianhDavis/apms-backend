package com.apms.domain.profile.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateCompanyProfileUpdateProposalRequest {
    @NotBlank
    private String companyProfileId;
    
    private Map<String, Object> proposedIdentity;
    private Map<String, Object> proposedBusiness;
    private Map<String, Object> proposedContact;
    private Map<String, Object> proposedInsights;
    
    private List<String> sourceDocumentIds;
    private String extractionId;
    private String changeSummary;
}
