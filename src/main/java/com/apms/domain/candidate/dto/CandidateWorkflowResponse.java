package com.apms.domain.candidate.dto;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateWorkflowResponse {
    private String candidateId;
    private CandidateStatus candidateStatus;
    
    private Long taskId;
    private TaskStatus taskStatus;
    
    private Long submissionId;
    private SubmissionStatus submissionStatus;
    private Integer reviewRound;
    
    private CandidateResponse candidateDetail;
}
