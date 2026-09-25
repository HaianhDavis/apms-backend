package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateScalarFieldRequest {
    private Object value;
    private String evidence;
    private Integer sourcePage;
}
