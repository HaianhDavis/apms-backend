package com.apms.domain.ai.entity;

import com.apms.common.enums.AiExtractionJobStage;
import com.apms.common.enums.AiExtractionJobStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_extraction_jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiExtractionJob {

    @Id
    @Column(length = 36)
    private String id; // UUID string

    @Column(nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiExtractionJobStatus status;

    @Enumerated(EnumType.STRING)
    private AiExtractionJobStage stage;

    private Integer progress;

    private Integer totalDocuments;

    private Integer processedDocuments;

    @Column(length = 36)
    private String candidateId;

    @org.hibernate.annotations.Nationalized
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String errorMessage;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime cancelledAt;

    private Long cancelledBy;
}
