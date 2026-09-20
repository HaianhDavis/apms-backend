package com.apms.domain.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchUpdateCompanyFinancialsRequest {
    private List<CompanyProfileFinancialRowDto> rows;
    private List<String> deletedIds;
}
