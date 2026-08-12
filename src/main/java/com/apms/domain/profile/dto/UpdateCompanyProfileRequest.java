package com.apms.domain.profile.dto;

import lombok.Data;
import com.apms.common.enums.StockExchange;
import com.apms.domain.company.model.FinancialInfo;
import com.apms.domain.profile.CompanyProfile;

import java.util.List;

@Data
public class UpdateCompanyProfileRequest {
    private String legalName;
    private String tradeName;
    private String stockTicker;
    private StockExchange stockExchange;
    private FinancialInfo financial;
    private List<CompanyProfile.CompanyMember> companyMembers;
    private List<String> industries;
    private List<String> markets;
    private String employeeTier;
    private Integer employeeCount;
    private String revenueTier;
    private String website;
    private List<String> emails;
    private List<String> phones;
    private List<String> tags;
}
