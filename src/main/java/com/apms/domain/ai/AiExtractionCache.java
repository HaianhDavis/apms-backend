package com.apms.domain.ai;

import com.apms.domain.ai.dto.ExtractedCompanyData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_extraction_results")
public class AiExtractionCache {

    @Id
    private String id;

    @Indexed
    private Long importJobId;

    private String rawDocumentId;
    private String provider;
    private String model;

    private ExtractedCompanyData extractedData;
    private String rawAiOutput;

    private LocalDateTime createdAt;
    
    private String lastModifiedBy;
    private LocalDateTime updatedAt;
}
