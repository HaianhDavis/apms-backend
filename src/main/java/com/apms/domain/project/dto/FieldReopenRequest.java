package com.apms.domain.project.dto;

import lombok.Data;

@Data
public class FieldReopenRequest {
    private Integer expectedRevisionNumber;
    private Long expectedDocumentVersion;
    private String reason;
}
