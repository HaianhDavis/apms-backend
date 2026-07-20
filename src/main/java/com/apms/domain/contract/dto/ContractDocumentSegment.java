package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDocumentSegment {
    private String segmentId;
    private String rawDocumentId;
    private String sourceDocumentHash;
    private int startOffset;
    private int endOffset;
    private String excerpt;
    private String excerptHash;
}
