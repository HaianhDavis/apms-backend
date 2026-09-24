package com.apms.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldReviewDecision {
    private Integer roundNumber;
    private ExtractionReviewStatus status;
    private String comment;
    private Object submittedValue;
    private LocalDateTime reviewedAt;
    private Long reviewedByUserId;
}