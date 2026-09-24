package com.apms.domain.companymember;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyMemberResearchItem {
    private String fullName;
    private String position;
    private String imageUrl;
    private String sourceUrl;
    private String notes;
}
