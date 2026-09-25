package com.apms.domain.ai.dto;

import com.apms.common.enums.AiExtractionJobStage;
import com.apms.common.enums.AiExtractionJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiExtractionJobResponse {
    private String jobId;
    private Long taskId;
    private AiExtractionJobStatus status;
    private AiExtractionJobStage stage;
    private Integer progress;
    private Integer totalDocuments;
    private Integer processedDocuments;
    private String candidateId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private Long cancelledBy;
}
