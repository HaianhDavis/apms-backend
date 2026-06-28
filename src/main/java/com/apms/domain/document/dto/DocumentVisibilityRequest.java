package com.apms.domain.document.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;

@Data
public class DocumentVisibilityRequest {
    @NotNull
    private Boolean hidden;
}
