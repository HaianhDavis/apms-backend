package com.apms.domain.contract.model;

import com.apms.domain.contract.enums.ContractResearchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "contract_researches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractResearch {
    @Id
    private String id;

    @Indexed(unique = true)
    private Long taskId;

    private Long projectId;
    private String companyProfileId;

    @Builder.Default
    private List<ContractEntry> contracts = new ArrayList<>();

    @Builder.Default
    private ContractResearchStatus status = ContractResearchStatus.DRAFT;

    private LocalDateTime submittedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewReason;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
