package com.apms.domain.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ManualInputRequest {

    @NotBlank(message = "inputText is required for manual input")
    private String inputText;

    private String companyNameHint;
}
