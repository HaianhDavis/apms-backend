package com.apms.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentCompanyValidationResult {
    private boolean valid;
    private String resolvedCompanyName;
    private List<DocumentCompanyIdentity> documents;
    private List<String> conflicts;
    private List<DocumentCompanyIdentity> ambiguousDocuments;
    /** DOCUMENT_COMPANY_MISMATCH, DOCUMENT_COMPANY_UNRESOLVED, DOCUMENT_TARGET_COMPANY_MISMATCH */
    private String errorCode;
    private String message;

    public static DocumentCompanyValidationResult valid(String resolvedCompanyName, List<DocumentCompanyIdentity> documents) {
        return DocumentCompanyValidationResult.builder()
                .valid(true)
                .resolvedCompanyName(resolvedCompanyName)
                .documents(documents)
                .build();
    }

    public static DocumentCompanyValidationResult mismatch(String message, List<DocumentCompanyIdentity> documents, List<String> conflicts) {
        return DocumentCompanyValidationResult.builder()
                .valid(false)
                .errorCode("DOCUMENT_COMPANY_MISMATCH")
                .message(message)
                .documents(documents)
                .conflicts(conflicts)
                .build();
    }

    public static DocumentCompanyValidationResult unresolved(String message, List<DocumentCompanyIdentity> documents, List<DocumentCompanyIdentity> ambiguous) {
        return DocumentCompanyValidationResult.builder()
                .valid(false)
                .errorCode("DOCUMENT_COMPANY_UNRESOLVED")
                .message(message)
                .documents(documents)
                .ambiguousDocuments(ambiguous)
                .build();
    }

    public static DocumentCompanyValidationResult targetMismatch(String message, List<DocumentCompanyIdentity> documents, List<String> conflicts) {
        return DocumentCompanyValidationResult.builder()
                .valid(false)
                .errorCode("DOCUMENT_TARGET_COMPANY_MISMATCH")
                .message(message)
                .documents(documents)
                .conflicts(conflicts)
                .build();
    }
}
