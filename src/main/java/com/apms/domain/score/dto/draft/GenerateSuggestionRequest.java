package com.apms.domain.score.dto.draft;

import lombok.Data;
import java.time.LocalDate;

@Data
public class GenerateSuggestionRequest {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Boolean force;
    private String reviewComment;
    private String generationId;
}
