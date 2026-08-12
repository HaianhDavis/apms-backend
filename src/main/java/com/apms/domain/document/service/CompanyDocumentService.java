package com.apms.domain.document.service;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.CompanyDocumentResponse;
import com.apms.domain.document.entity.CompanyDocument;
import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyDocumentService {
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String DOCUMENT_TYPE_PARTNER_CONTRACT = "PARTNER_CONTRACT";
    private static final String DOCUMENT_TYPE_AI_EXTRACTION_SOURCE = "AI_EXTRACTION_SOURCE";

    private final CompanyDocumentRepository companyDocumentRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final StorageService storageService;
    private final AccountRepository accountRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;

    public record DocumentDownload(Resource resource, String fileName, String mimeType) {}

    @Transactional(readOnly = true)
    public Page<CompanyDocumentResponse> getPublishedDocuments(String companyProfileIdOrCompanyId, Pageable pageable) {
        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);

        Page<CompanyDocument> documents = companyDocumentRepository.findByCompanyProfileIdInAndStatusAndDocumentTypeAndDeletedAtIsNull(
                profileIdentifiers(profile), STATUS_PUBLISHED, DOCUMENT_TYPE_PARTNER_CONTRACT, pageable);
        
        return documents.map(this::mapToResponse);
    }

    @Transactional
    public int reconcilePublishedDocuments(String companyProfileIdOrCompanyId) {
        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);
        return softDeleteLegacyCandidateDocuments(profile);
    }

    @Transactional(readOnly = true)
    public DocumentDownload downloadDocument(String companyProfileIdOrCompanyId, String documentId) {
        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);
        CompanyDocument doc = companyDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        if (!profileIdentifiers(profile).contains(doc.getCompanyProfileId())) {
            throw new ResourceNotFoundException("Document does not belong to this profile");
        }
        if (!STATUS_PUBLISHED.equals(doc.getStatus())
                || doc.getDeletedAt() != null
                || !DOCUMENT_TYPE_PARTNER_CONTRACT.equals(doc.getDocumentType())) {
            throw new ResourceNotFoundException("Document is not an approved partner contract for this profile");
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
        response.setSourceProjectId(doc.getSourceProjectId());
        response.setSourceTaskId(doc.getSourceTaskId());
        response.setSourceSubmissionId(doc.getSourceSubmissionId());
        response.setSourceCandidateId(doc.getSourceCandidateId());
        response.setDisplayName(doc.getDisplayName());
        response.setDocumentType(doc.getDocumentType());
        response.setDescription(doc.getDescription());
        response.setStatus(doc.getStatus());
        response.setUploadedAt(doc.getUploadedAt());
        response.setApprovedAt(doc.getApprovedAt());
        response.setDownloadAvailable(false);
        response.setPreviewAvailable(false);

        if (StringUtils.hasText(doc.getSourceProjectId())) {
            try {
                projectRepository.findById(Long.valueOf(doc.getSourceProjectId()))
                        .ifPresent(project -> response.setSourceProjectName(project.getProjectName()));
            } catch (NumberFormatException ignored) {
                // Keep sourceProjectId even if legacy data is not numeric.
            }
        }

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
                boolean downloadable = raw.getStorage().getPath() != null && "LOCAL".equalsIgnoreCase(raw.getStorage().getProvider());
                response.setDownloadAvailable(downloadable);
                response.setPreviewAvailable(downloadable && raw.getStorage().getMimeType() != null
                        && raw.getStorage().getMimeType().toLowerCase().contains("pdf"));
            }
        });

        return response;
    }

    private CompanyProfile resolveProfile(String companyProfileIdOrCompanyId) {
        return companyProfileRepository.findById(companyProfileIdOrCompanyId)
                .or(() -> companyProfileRepository.findByCompanyId(companyProfileIdOrCompanyId))
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found: " + companyProfileIdOrCompanyId));
    }

    private List<String> profileIdentifiers(CompanyProfile profile) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (StringUtils.hasText(profile.getId())) ids.add(profile.getId());
        if (StringUtils.hasText(profile.getCompanyId())) ids.add(profile.getCompanyId());
        return new ArrayList<>(ids);
    }

    private int softDeleteLegacyCandidateDocuments(CompanyProfile profile) {
        LocalDateTime now = LocalDateTime.now();
        List<CompanyDocument> legacyDocuments = companyDocumentRepository.findByCompanyProfileIdInAndStatusAndDeletedAtIsNull(
                profileIdentifiers(profile), STATUS_PUBLISHED, org.springframework.data.domain.Pageable.unpaged()).getContent();

        int changed = 0;
        for (CompanyDocument document : legacyDocuments) {
            boolean traceableCandidatePublication = StringUtils.hasText(document.getSourceCandidateId())
                    || DOCUMENT_TYPE_AI_EXTRACTION_SOURCE.equals(document.getDocumentType());
            if (traceableCandidatePublication && !DOCUMENT_TYPE_PARTNER_CONTRACT.equals(document.getDocumentType())) {
                document.setDeletedAt(now);
                document.setUpdatedAt(now);
                companyDocumentRepository.save(document);
                changed++;
            }
        }
        return changed;
    }
    
    private String getFullName(Account account) {
        return account.getEmail() != null ? account.getEmail() : "Unknown";
    }
}
