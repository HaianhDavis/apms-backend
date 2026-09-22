package com.apms.domain.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EligibleManagerDto {
    private Long accountId;
    private String displayName;
    private String email;
}
