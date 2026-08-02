package com.apms.domain.score.dto;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class RoleEvaluationVersionResponse {
    private String id;
    private String evaluationId;
    private Long projectId;
    private Long taskId;
    private String targetCompanyProfileId;
    private String targetCompanyId;
    private String targetCompanyName;
    private List<String> industries;
    private CompanyRole evaluatedRole;
    private Integer versionNumber;
    private RoleEvaluationStatus status;
    private EvaluationCompletenessStatus completenessStatus;
    private BigDecimal overallScore;
    private Map<String, CriterionResponse> criteria;
    private Long submittedByAccountId;
    private LocalDateTime submittedAt;
    private Long approvedByAccountId;
    private LocalDateTime approvedAt;
    private String reviewComment;
    private LocalDateTime createdAt;

    @Data
    @Builder
    public static class CriterionResponse {
        private String criterionKey;
        private BigDecimal rawScore;
        private String finalRationale;
        private CriterionInputMethod inputMethod;
        private List<String> evidenceReferenceIds;
        private List<EvidenceResponse> evidence;
        private String dataSufficiencyStatus;
        private String missingDataExplanation;
        private CriterionSuggestionReviewStatus suggestionReviewStatus;
        private Boolean staffEdited;
        private String managerFeedback;
        private BigDecimal aiConfidence;
    }

    @Data
    @Builder
    public static class EvidenceResponse {
        private String evidenceId;
        private String criterionKey;
        private String sourceType;
        private String rawDocumentId;
        private String fileName;
        private String mimeType;
        private Long sizeBytes;
        private String projectId;
        private String taskId;
        private String evidenceCategory;
        private String reliability;
        private String note;
        private String externalUrl;
    }
}
