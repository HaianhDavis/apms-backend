package com.apms.domain.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyAutocompleteItemDto {
    private String companyProfileId;
    private String companyId;
    private String legalName;
    private String tradeName;
    private String taxCode;
}
