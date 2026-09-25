package com.apms.domain.project.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferLeadershipRequest {

    @NotNull(message = "newLeaderAccountId is required")
    private Long newLeaderAccountId;

    private boolean leaveProject = false;
}
