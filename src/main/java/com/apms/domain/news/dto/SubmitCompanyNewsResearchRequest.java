package com.apms.domain.news.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class SubmitCompanyNewsResearchRequest {

    @NotEmpty(message = "newsDraftIds must not be empty")
    private List<String> newsDraftIds;
}
