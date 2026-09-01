package com.apms.domain.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricSource {
    private String reportEntryId;
    private String documentId;
    private String documentName;
    private Integer page;  // 1-based
}
