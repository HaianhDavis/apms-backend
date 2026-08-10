package com.apms.domain.document.service;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.CompanyDocumentResponse;
import com.apms.domain.document.entity.CompanyDocument;
import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
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

    private final CompanyDocumentRepository companyDocumentRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final StorageService storageService;
    private final AccountRepository accountRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final ProjectRepository projectRepository;

    public record DocumentDownload(Resource resource, String fileName, String mimeType) {}

    @Transactional(readOnly = true)
    public Page<CompanyDocumentResponse> getPublishedDocuments(String companyProfileIdOrCompanyId, Pageable pageable) {
        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);

        Page<CompanyDocument> documents = companyDocumentRepository.findByCompanyProfileIdInAndStatusAndDeletedAtIsNull(
                profileIdentifiers(profile), "PUBLISHED", pageable);
        
        return documents.map(this::mapToResponse);
    }

    @Transactional
    public int reconcilePublishedDocuments(String companyProfileIdOrCompanyId) {
        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);
        return backfillMissingCandidateDocuments(profile);
    }

    @Transactional(readOnly = true)
    public DocumentDownload downloadDocument(String companyProfileIdOrCompanyId, String documentId) {
        CompanyProfile profile = resolveProfile(companyProfileIdOrCompanyId);
        CompanyDocument doc = companyDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        if (!profileIdentifiers(profile).contains(doc.getCompanyProfileId())) {
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

    private int backfillMissingCandidateDocuments(CompanyProfile profile) {
        if (profile.getSourceRefs() == null || profile.getSourceRefs().getCandidateIds() == null
                || profile.getSourceRefs().getCandidateIds().isEmpty()) {
            return 0;
        }

        final int[] changed = {0};
        for (String candidateId : profile.getSourceRefs().getCandidateIds()) {
            if (!StringUtils.hasText(candidateId)) continue;
            candidateRepository.findById(candidateId).ifPresent(candidate -> {
                List<String> sourceDocumentIds = sourceDocumentIds(candidate);
                for (String sourceDocumentId : sourceDocumentIds) {
                    CompanyDocument document = resolveExistingDocumentForBackfill(profile, sourceDocumentId);

                    if (!profile.getId().equals(document.getCompanyProfileId())) {
                        document.setCompanyProfileId(profile.getId());
                    }

                    enrichDocumentFromCandidate(document, candidate);
                    companyDocumentRepository.save(document);
                    changed[0]++;
                }
            });
        }
        return changed[0];
    }

    private CompanyDocument resolveExistingDocumentForBackfill(CompanyProfile profile, String sourceDocumentId) {
        return companyDocumentRepository.findByCompanyProfileIdAndSourceDocumentId(profile.getId(), sourceDocumentId)
                .or(() -> StringUtils.hasText(profile.getCompanyId())
                        ? companyDocumentRepository.findByCompanyProfileIdAndSourceDocumentId(profile.getCompanyId(), sourceDocumentId)
                        : java.util.Optional.empty())
                .orElseGet(() -> {
                    CompanyDocument created = new CompanyDocument();
                    created.setCompanyProfileId(profile.getId());
                    created.setSourceDocumentId(sourceDocumentId);
                    created.setCreatedAt(LocalDateTime.now());
                    return created;
                });
    }

    private List<String> sourceDocumentIds(CompanyCandidate candidate) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (candidate.getSourceDocumentIds() != null) {
            candidate.getSourceDocumentIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(ids::add);
        }
        if (StringUtils.hasText(candidate.getRawDocumentId())) {
            ids.add(candidate.getRawDocumentId().trim());
        }
        return new ArrayList<>(ids);
    }

    private void enrichDocumentFromCandidate(CompanyDocument document, CompanyCandidate candidate) {
        LocalDateTime now = LocalDateTime.now();
        document.setStatus("PUBLISHED");
        document.setSourceProjectId(candidate.getProjectId());
        if (candidate.getTaskId() != null) {
            document.setSourceTaskId(String.valueOf(candidate.getTaskId()));
        }
        document.setSourceCandidateId(candidate.getId());
        document.setDocumentType("AI_EXTRACTION_SOURCE");
        document.setDescription("Used for Candidate Extraction");
        document.setUpdatedAt(now);
        if (document.getPublishedAt() == null) {
            document.setPublishedAt(now);
        }

        if (candidate.getReview() != null) {
            if (StringUtils.hasText(candidate.getReview().getReviewedBy())) {
                try {
                    Long reviewerId = Long.valueOf(candidate.getReview().getReviewedBy());
                    document.setApprovedBy(reviewerId);
                    document.setPublishedBy(reviewerId);
                } catch (NumberFormatException ignored) {
                    // Legacy reviewer value is kept out of numeric approver fields.
                }
            }
            if (candidate.getReview().getReviewedAt() != null) {
                document.setApprovedAt(candidate.getReview().getReviewedAt());
            }
        }
        if (document.getApprovedAt() == null) {
            document.setApprovedAt(now);
        }

        rawDocumentRepository.findById(document.getSourceDocumentId()).ifPresent(rawDoc -> {
            if (rawDoc.getMetadata() != null) {
                document.setUploadedBy(rawDoc.getMetadata().getUploadedBy());
                document.setUploadedAt(rawDoc.getMetadata().getUploadedAt());
            }
            if (rawDoc.getSource() != null && !StringUtils.hasText(document.getDisplayName())) {
                document.setDisplayName(rawDoc.getSource().getFileName());
            }
        });
    }
    
    private String getFullName(Account account) {
        return account.getEmail() != null ? account.getEmail() : "Unknown";
    }
}
