package com.apms.domain.contract.dto;

import lombok.Data;

@Data
public class ReviewPartnerContractRequest {
    private String decision; // "APPROVE" or "REQUEST_CHANGES"
    private String comment; // required for REQUEST_CHANGES
}
