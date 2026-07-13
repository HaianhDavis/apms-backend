package com.apms.domain.score.dto.draft;

import lombok.Data;
import java.util.List;

@Data
public class NeedsMoreDataCriterionSuggestionRequest {
    private String reviewComment;
    private List<String> missingData; // Staff can specify what is missing
}
