package com.apms.domain.externaldata;

import com.apms.common.enums.ExternalDataCategory;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@Document(collection = "external_data_items")
public class ExternalDataItem {

    @Id
    private String id;
    
    private String title;
    private String summary;
    private String source;
    private String url;
    private LocalDateTime publishedAt;
    
    private ExternalDataCategory category;
    
    private String sentiment;
    private String riskLevel;
    private String opportunityLevel;
    
    private String relatedCompanyName;
    private String relatedCompanyId;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
