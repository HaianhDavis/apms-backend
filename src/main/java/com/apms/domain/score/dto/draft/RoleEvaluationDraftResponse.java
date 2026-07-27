package com.apms.domain.score.dto.draft;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.EvidenceRecord;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

@Data
public class RoleEvaluationDraftResponse {
    private String id;
    private Long projectId;
    private Long taskId;

    private String targetCompanyId;
    private String targetProfileDocumentId;
    private Integer targetProfileVersion;

    private String referenceCompanyId;
    private String referenceProfileDocumentId;
    private Integer referenceProfileVersion;

    private CompanyRole evaluatedRole;
    private String ruleSetVersion;
    private String weightVersion;

    private RoleEvaluationStatus status;

    private LinkedHashMap<String, CriterionInput> criterionInputs;
    private LinkedHashMap<String, AutomaticSuggestion> automaticSuggestions;
    private LinkedHashMap<String, List<EvidenceRecord>> criterionEvidence;

    private Boolean staleTargetProfile;
    private Boolean staleReferenceProfile;
    private Boolean staleRuleSet;

    private Boolean active;

    private Long approvedSnapshotId;

    private Long createdByAccountId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Long submittedByAccountId;
    private LocalDateTime submittedAt;

    private Long reviewedByAccountId;
    private LocalDateTime reviewedAt;
    private String reviewComment;
}
