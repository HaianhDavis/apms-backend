package com.apms.domain.score.draft;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.company.enums.CompanyRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "role_evaluation_versions")
@CompoundIndexes({
    @CompoundIndex(name = "evaluation_version_idx", def = "{'evaluationId': 1, 'versionNumber': 1}", unique = true)
})
public class RoleEvaluationVersion {
    @Id
    private String id;
    private String evaluationId;
    private Long projectId;
    private Long taskId;
    private String targetCompanyProfileId;
    private CompanyRole evaluatedRole;
    private Integer versionNumber;
    private Integer approvedDraftRevision;
    @Builder.Default
    private RoleEvaluationStatus status = RoleEvaluationStatus.APPROVED;
    
    private EvaluationPeriod evaluationPeriod;
    
    @Builder.Default
    private Map<String, CriterionSnapshot> criteria = new HashMap<>();
    
    @Builder.Default
    private List<ApprovedSourceReference> sourceReferences = new ArrayList<>();
    
    private String sourceSnapshotHash;
    private EvaluationCompletenessStatus completenessStatus;
    private String partialApprovalJustification;
    
    private Long submittedByAccountId;
    private LocalDateTime submittedAt;
    
    private Long approvedByAccountId;
    private LocalDateTime approvedAt;
    private String reviewComment;
    
    @Builder.Default
    private Integer schemaVersion = 1;
    
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
