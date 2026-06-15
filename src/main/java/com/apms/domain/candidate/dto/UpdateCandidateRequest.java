package com.apms.domain.candidate.dto;

import com.apms.common.enums.RelationshipType;
import com.apms.domain.candidate.CompanyCandidate;
import lombok.Data;

@Data
public class UpdateCandidateRequest {
    private CompanyCandidate.Identity identity;
    private CompanyCandidate.Business business;
    private CompanyCandidate.CompanySize companySize;
    private CompanyCandidate.Contact contact;
    private CompanyCandidate.Insights insights;
    private RelationshipType suggestedRelationshipType;
}
