package com.apms.domain.score.dto.draft;

import lombok.Data;

@Data
public class CreateRoleEvaluationDraftRequest {
    private String note; // Optional note only, no evaluatedRole since it's derived from project
}
