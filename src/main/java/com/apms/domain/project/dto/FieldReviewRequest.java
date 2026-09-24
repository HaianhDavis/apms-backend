package com.apms.domain.project.dto;

import lombok.Data;
import java.util.List;

@Data
public class FieldReviewRequest {
    private Integer expectedRevisionNumber;
    private Long expectedDocumentVersion;
    private List<FieldReviewDecisionItem> decisions;
}
