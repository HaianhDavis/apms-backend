package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateArrayItemRequest {
    private Map<String, Object> itemPayload;
    private String evidence;
    private Integer sourcePage;
}
