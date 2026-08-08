package com.apms.domain.document.service;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.CompanyDocumentResponse;
import com.apms.domain.document.entity.CompanyDocument;
import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyDocumentService {

    private final CompanyDocumentRepository companyDocumentRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final StorageService storageService;
    private final AccountRepository accountRepository;

    public record DocumentDownload(Resource resource, String fileName, String mimeType) {}

    @Transactional(readOnly = true)
    public Page<CompanyDocumentResponse> getPublishedDocuments(String companyProfileId, Pageable pageable) {
        Page<CompanyDocument> documents = companyDocumentRepository.findByCompanyProfileIdAndStatusAndDeletedAtIsNull(
                companyProfileId, "PUBLISHED", pageable);
        
        return documents.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public DocumentDownload downloadDocument(String companyProfileId, String documentId) {
        CompanyDocument doc = companyDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        if (!doc.getCompanyProfileId().equals(companyProfileId)) {
            throw new ResourceNotFoundException("Document does not belong to this profile");
        }

        RawDocument rawDocument = rawDocumentRepository.findById(doc.getSourceDocumentId())
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));

        RawDocument.Storage storage = rawDocument.getStorage();
        if (storage == null || storage.getPath() == null || !"LOCAL".equalsIgnoreCase(storage.getProvider())) {
            throw new ResourceNotFoundException("No downloadable file is available for this document");
        }

        String fileName = rawDocument.getSource() != null && rawDocument.getSource().getFileName() != null
                ? rawDocument.getSource().getFileName()
                : storage.getPath();

        return new DocumentDownload(
                storageService.loadAsResource(storage.getPath()),
                fileName,
                storage.getMimeType() != null ? storage.getMimeType() : "application/octet-stream"
        );
    }

    private CompanyDocumentResponse mapToResponse(CompanyDocument doc) {
        CompanyDocumentResponse response = new CompanyDocumentResponse();
        response.setId(doc.getId());
        response.setCompanyProfileId(doc.getCompanyProfileId());
        response.setSourceDocumentId(doc.getSourceDocumentId());
        response.setDisplayName(doc.getDisplayName());
        response.setDocumentType(doc.getDocumentType());
        response.setDescription(doc.getDescription());
        response.setStatus(doc.getStatus());
        response.setUploadedAt(doc.getUploadedAt());
        response.setApprovedAt(doc.getApprovedAt());
        response.setDownloadAvailable(true); // Default assuming file exists
        response.setPreviewAvailable(false); // Can be enhanced later

        // Fetch user info for uploadedBy
        if (doc.getUploadedBy() != null) {
            try {
                Long uploaderId = Long.parseLong(doc.getUploadedBy());
                accountRepository.findById(uploaderId).ifPresent(account -> {
                    response.setUploadedBy(new CompanyDocumentResponse.UserInfo(doc.getUploadedBy(), getFullName(account)));
                });
            } catch (NumberFormatException e) {
                // Ignore parsing errors for system uploads
            }
        }

        // Fetch user info for approvedBy
        if (doc.getApprovedBy() != null) {
            accountRepository.findById(doc.getApprovedBy()).ifPresent(account -> {
                response.setApprovedBy(new CompanyDocumentResponse.UserInfo(String.valueOf(doc.getApprovedBy()), getFullName(account)));
            });
        }

        // Fetch RawDocument to fill missing details
        rawDocumentRepository.findById(doc.getSourceDocumentId()).ifPresent(raw -> {
            if (raw.getSource() != null) {
                response.setOriginalFileName(raw.getSource().getFileName());
                if (response.getDisplayName() == null) {
                    response.setDisplayName(raw.getSource().getFileName());
                }
            }
            if (raw.getStorage() != null) {
                response.setMimeType(raw.getStorage().getMimeType());
                response.setFileSize(raw.getStorage().getSizeBytes());
                if (raw.getStorage().getPath() == null || !"LOCAL".equalsIgnoreCase(raw.getStorage().getProvider())) {
                    response.setDownloadAvailable(false);
                }
            }
        });

        return response;
    }
    
    private String getFullName(Account account) {
        return account.getEmail() != null ? account.getEmail() : "Unknown";
    }
}
