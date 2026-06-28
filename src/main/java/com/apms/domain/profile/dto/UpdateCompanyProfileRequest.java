package com.apms.domain.profile.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateCompanyProfileRequest {
    private String legalName;
    private String tradeName;
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
