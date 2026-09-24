package com.apms.domain.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileManagerHistoryDto {
    private String id;
    private String companyProfileId;
    private String companyId;
    private Long previousManagerAccountId;
    private String previousManagerDisplayName;
    private Long newManagerAccountId;
    private String newManagerDisplayName;
    private Long transferredByAccountId;
    private String transferredByDisplayName;
    private String reason;
    private LocalDateTime transferredAt;
}
