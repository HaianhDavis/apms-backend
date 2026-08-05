package com.apms.domain.companymember.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CompanyMemberResearchDraftRequest {
    @NotNull(message = "Members list cannot be null")
    @Valid
    private List<CompanyMemberResearchItemRequest> members;
}
