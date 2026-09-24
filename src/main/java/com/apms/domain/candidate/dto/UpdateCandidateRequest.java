package com.apms.domain.candidate.dto;

import com.apms.common.enums.RelationshipType;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.company.model.ComplianceInfo;
import com.apms.domain.company.model.FinancialInfo;
import com.apms.domain.company.model.InnovationInfo;
import com.apms.domain.company.model.MarketInfo;
import com.apms.domain.company.model.RiskInfo;
import lombok.Data;

@Data
public class UpdateCandidateRequest {
    private CompanyCandidate.Identity identity;
    private CompanyCandidate.Business business;
    private CompanyCandidate.CompanySize companySize;
    private CompanyCandidate.Contact contact;
    private CompanyCandidate.Insights insights;
    private FinancialInfo financial;
    private MarketInfo market;
    private InnovationInfo innovation;
    private RiskInfo risk;
    private ComplianceInfo compliance;
    private CompanyCandidate.Validation validation;
    private RelationshipType suggestedRelationshipType;
}
