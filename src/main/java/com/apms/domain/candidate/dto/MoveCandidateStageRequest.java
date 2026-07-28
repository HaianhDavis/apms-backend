package com.apms.domain.candidate.dto;

import com.apms.common.enums.CandidateStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoveCandidateStageRequest {
    @NotNull
    private CandidateStatus status;
}
