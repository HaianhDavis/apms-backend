package com.apms.domain.contract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateContractEntryRequest {
    @NotBlank(message = "Title is required")
    private String title;

    private LocalDate documentDate;
}
