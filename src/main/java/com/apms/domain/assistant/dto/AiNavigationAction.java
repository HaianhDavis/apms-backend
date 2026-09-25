package com.apms.domain.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiNavigationAction {
    private String type;
    private String label;
    private String companyProfileId;
    private String companyId;
    private String companyName;
}
