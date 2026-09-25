package com.apms.domain.contract.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPartyRightsAndObligationsCandidate {
    private String party;
    @Builder.Default
    private List<String> rights = new ArrayList<>();
    @Builder.Default
    private List<String> obligations = new ArrayList<>();
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
}
