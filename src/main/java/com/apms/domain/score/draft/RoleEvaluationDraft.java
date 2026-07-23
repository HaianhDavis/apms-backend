package com.apms.domain.score.draft;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

@Document(collection = "role_evaluation_drafts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleEvaluationDraft {

    @Id
    private String id;

    @Indexed
    private Long projectId;

    @Indexed
    private Long taskId;

    // Stable business identifier (UUID)
    @Indexed
    private String targetCompanyId;
    
    // MongoDB _id of the target company profile
    private String targetProfileDocumentId;
    
    private Integer targetProfileVersion;

    // Stable business identifier (UUID) of FPT
    @Indexed
    private String referenceCompanyId;
    
    // MongoDB _id of the FPT company profile
    private String referenceProfileDocumentId;
    
    private Integer referenceProfileVersion;

    private CompanyRole evaluatedRole;
    
    private String ruleSetVersion;
    
    private String weightVersion;
    
    private RoleEvaluationStatus status;

    @Builder.Default
    private LinkedHashMap<String, CriterionInput> criterionInputs = new LinkedHashMap<>();
    
    @Builder.Default
    private LinkedHashMap<String, AutomaticSuggestion> automaticSuggestions = new LinkedHashMap<>();
    
    @Builder.Default
    private LinkedHashMap<String, List<EvidenceRecord>> criterionEvidence = new LinkedHashMap<>();

    @Builder.Default
    private LinkedHashMap<String, List<PartnerSuggestionGenerationMetadata>> generationIdempotency = new LinkedHashMap<>();

    @Builder.Default
    private Boolean staleTargetProfile = false;
    
    @Builder.Default
    private Boolean staleReferenceProfile = false;
    
    @Builder.Default
    private Boolean staleRuleSet = false;

    @Builder.Default
    private Boolean active = true;

    // Unique index to ensure only one active draft per task/role
    // Format: projectId + ":" + taskId + ":" + evaluatedRole
    @Indexed(unique = true, sparse = true)
    private String activeDraftKey;

    private Long approvedSnapshotId; // Used for COMPETITOR
    
    // PARTNER specific fields
    private EvaluationPeriod evaluationPeriod;
    private String currentApprovedVersionId;
    private Integer currentApprovedVersionNumber;
    
    @Builder.Default
    private List<ApprovedSourceReference> pinnedSourceReferences = new java.util.ArrayList<>();
    
    private String sourceSnapshotHash;
    
    @Builder.Default
    private Integer workingRevisionNumber = 1;
    
    @org.springframework.data.annotation.Version
    private Long optimisticVersion;
    
    @Indexed
    private String approvalIdempotencyKey;

    private Long createdByAccountId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Long submittedByAccountId;
    private LocalDateTime submittedAt;

    private Long reviewedByAccountId;
    private LocalDateTime reviewedAt;
    private String reviewComment;

    private LocalDateTime approvalProcessingStartedAt;
    private String approvalFailureReason;
}
