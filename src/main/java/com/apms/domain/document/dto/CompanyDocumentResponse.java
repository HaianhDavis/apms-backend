package com.apms.domain.document.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyDocumentResponse {
    private String id;
    private String companyProfileId;
    private String sourceDocumentId;
    private String displayName;
    private String originalFileName;
    private String documentType;
    private String description;
    private String mimeType;
    private Long fileSize;
    private String status;
    private UserInfo uploadedBy;
    private LocalDateTime uploadedAt;
    private UserInfo approvedBy;
    private LocalDateTime approvedAt;
    private Boolean previewAvailable;
    private Boolean downloadAvailable;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private String id;
        private String name;
    }
}
