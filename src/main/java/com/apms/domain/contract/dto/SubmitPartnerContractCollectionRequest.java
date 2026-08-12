package com.apms.domain.contract.dto;

import lombok.Data;

import java.util.List;

@Data
public class SubmitPartnerContractCollectionRequest {
    private List<String> rawDocumentIds;
    private List<String> contractDraftIds;
    private String note;
}
