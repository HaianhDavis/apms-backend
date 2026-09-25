package com.apms.domain.contract.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExclusivityClause {
    private Boolean isExclusive;
    private String scope;
}
