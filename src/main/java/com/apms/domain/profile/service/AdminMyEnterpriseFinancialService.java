package com.apms.domain.profile.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.service.StorageService;
import com.apms.domain.financial.ReportingPeriod;
import com.apms.domain.financial.ReportingPeriodType;
import com.apms.domain.financial.dto.AiFinancialMetricCandidate;
import com.apms.domain.financial.dto.FinancialDocumentExtractionResult;
import com.apms.domain.financial.service.FinancialExtractionService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileFinancialRow;
import com.apms.domain.profile.dto.CompanyProfileFinancialRowDto;
import com.apms.domain.profile.repository.mongo.CompanyProfileFinancialRowRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMyEnterpriseFinancialService {

    private static final long MAX_PDF_SIZE_BYTES = 50L * 1024 * 1024; // 50MB

    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyProfileFinancialRowRepository rowRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final StorageService storageService;
    private final FinancialExtractionService extractionService;
    private final AuditLogService auditLogService;

    @Transactional
    public List<CompanyProfileFinancialRowDto> createFinancialReport(
            String title,
            Integer year,
            String period,
            String dataEntryMethod,
            MultipartFile file,
            UserDetailsImpl currentUser) {

        CompanyProfile ownerProfile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        String companyProfileId = ownerProfile.getId();
        Long userId = currentUser != null ? currentUser.getId() : null;

        if (!StringUtils.hasText(title)) {
            throw new BusinessValidationException("Report title is required.");
        }
        if (year == null || year < 1800 || year > 2100) {
            throw new BusinessValidationException("Valid reporting year is required.");
        }

        String normalizedQuarter = normalizePeriod(period);
        boolean isAi = "AI_EXTRACTION".equalsIgnoreCase(dataEntryMethod);

        RawDocument rawDoc = null;
        if (file != null && !file.isEmpty()) {
            validatePdfFile(file);
            rawDoc = storeRawDocument(file, companyProfileId, userId);
        } else if (isAi) {
            throw new BusinessValidationException("Source document (PDF) is required for AI Extraction.");
        }

        String reportId = UUID.randomUUID().toString();
        List<CompanyProfileFinancialRow> rowsToSave = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        String publicationDate = now.toLocalDate().toString();

        if (isAi && rawDoc != null) {
            // DO NOT automatically start Gemini/AI extraction!
            // Store PDF and initialize template rows in pre-extraction / ready state.
            List<CompanyProfileFinancialRow> templateRows = createStandardTemplateRows(
                    companyProfileId, reportId, title.trim(), rawDoc.getId(),
                    "AI_EXTRACTION", year, normalizedQuarter, publicationDate, now, userId);
            for (CompanyProfileFinancialRow row : templateRows) {
                row.setSourceMetricId("NOT_EXTRACTED");
            }
            rowsToSave.addAll(templateRows);
        } else {
            // Manual entry report
            rowsToSave.addAll(createStandardTemplateRows(companyProfileId, reportId, title.trim(),
                    rawDoc != null ? rawDoc.getId() : null, "MANUAL", year, normalizedQuarter, publicationDate, now, userId));
        }

        rowRepository.saveAll(rowsToSave);

        if (userId != null) {
            auditLogService.log(
                    userId,
                    AuditAction.FINANCIAL_ROW_CREATED,
                    "CompanyProfileFinancialRow",
                    companyProfileId,
                    String.format("Created %s financial report '%s' (%s %d) for enterprise %s with %d metrics",
                            isAi ? "AI" : "manual", title.trim(), normalizedQuarter, year, companyProfileId, rowsToSave.size())
            );
        }

        return rowsToSave.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public List<CompanyProfileFinancialRowDto> updateFinancialReport(
            String reportId,
            String title,
            Integer year,
            String period,
            MultipartFile file,
            UserDetailsImpl currentUser) {

        CompanyProfile ownerProfile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        String companyProfileId = ownerProfile.getId();
        Long userId = currentUser != null ? currentUser.getId() : null;

        List<CompanyProfileFinancialRow> existingRows = rowRepository.findByCompanyProfileId(companyProfileId)
                .stream()
                .filter(r -> reportId.equals(r.getSourceReportId()))
                .collect(Collectors.toList());

        if (existingRows.isEmpty()) {
            throw new BusinessValidationException("Financial report not found: " + reportId);
        }

        String normalizedQuarter = StringUtils.hasText(period) ? normalizePeriod(period) : null;
        RawDocument newRawDoc = null;
        if (file != null && !file.isEmpty()) {
            validatePdfFile(file);
            newRawDoc = storeRawDocument(file, companyProfileId, userId);
        }

        boolean isAiReport = existingRows.stream().anyMatch(r -> "AI_EXTRACTION".equalsIgnoreCase(r.getSourceType()));
        LocalDateTime now = LocalDateTime.now();

        if (newRawDoc != null && isAiReport) {
            // Document replaced for AI report: clear old extracted metrics and return to ready state so Admin can explicitly start extraction
            rowRepository.deleteAll(existingRows);

            int targetYear = year != null ? year : existingRows.get(0).getYear();
            String targetQuarter = normalizedQuarter != null ? normalizedQuarter : existingRows.get(0).getQuarter();
            String targetTitle = StringUtils.hasText(title) ? title.trim() : existingRows.get(0).getSourceReportTitle();

            List<CompanyProfileFinancialRow> templateRows = createStandardTemplateRows(companyProfileId, reportId, targetTitle,
                    newRawDoc.getId(), "AI_EXTRACTION", targetYear, targetQuarter, now.toLocalDate().toString(), now, userId);
            for (CompanyProfileFinancialRow row : templateRows) {
                row.setSourceMetricId("NOT_EXTRACTED");
            }

            rowRepository.saveAll(templateRows);
            return templateRows.stream().map(this::toDto).collect(Collectors.toList());
        }

        // Updating metadata and/or reference document for manual report
        for (CompanyProfileFinancialRow row : existingRows) {
            if (StringUtils.hasText(title)) {
                row.setSourceReportTitle(title.trim());
            }
            if (year != null && year >= 1800) {
                row.setYear(year);
            }
            if (normalizedQuarter != null) {
                row.setQuarter(normalizedQuarter);
            }
            if (newRawDoc != null) {
                row.setSourceDocumentId(newRawDoc.getId());
            }
            row.setUpdatedAt(now);
            row.setLastModifiedBy(userId);
        }

        rowRepository.saveAll(existingRows);

        if (userId != null) {
            auditLogService.log(
                    userId,
                    AuditAction.FINANCIAL_ROW_UPDATED,
                    "CompanyProfileFinancialRow",
                    companyProfileId,
                    String.format("Updated financial report '%s' (%s) for enterprise %s",
                            reportId, title != null ? title.trim() : existingRows.get(0).getSourceReportTitle(), companyProfileId)
            );
        }

        return existingRows.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public List<CompanyProfileFinancialRowDto> reExtractFinancialReport(
            String reportId,
            UserDetailsImpl currentUser) {

        CompanyProfile ownerProfile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        String companyProfileId = ownerProfile.getId();
        Long userId = currentUser != null ? currentUser.getId() : null;

        List<CompanyProfileFinancialRow> existingRows = rowRepository.findByCompanyProfileId(companyProfileId)
                .stream()
                .filter(r -> reportId.equals(r.getSourceReportId()))
                .collect(Collectors.toList());

        if (existingRows.isEmpty()) {
            throw new BusinessValidationException("Financial report not found: " + reportId);
        }

        String documentId = existingRows.stream()
                .map(CompanyProfileFinancialRow::getSourceDocumentId)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Report has no source PDF document for re-extraction."));

        RawDocument rawDoc = rawDocumentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessValidationException("Source document not found: " + documentId));

        CompanyProfileFinancialRow first = existingRows.get(0);
        int targetYear = first.getYear() != null ? first.getYear() : LocalDateTime.now().getYear();
        String targetQuarter = first.getQuarter() != null ? first.getQuarter() : "FY";
        String targetTitle = first.getSourceReportTitle() != null ? first.getSourceReportTitle() : "Financial Report";

        ReportingPeriod targetPeriod = ReportingPeriod.builder()
                .year(targetYear)
                .period(targetQuarter)
                .periodType("FY".equalsIgnoreCase(targetQuarter) ? ReportingPeriodType.FULL_YEAR : ReportingPeriodType.QUARTER)
                .build();

        Optional<FinancialDocumentExtractionResult> resultOpt = extractionService.extractDocument(rawDoc, targetPeriod);

        rowRepository.deleteAll(existingRows);

        LocalDateTime now = LocalDateTime.now();
        List<CompanyProfileFinancialRow> newRows = new ArrayList<>();
        if (resultOpt.isPresent() && resultOpt.get().getMetricCandidates() != null && !resultOpt.get().getMetricCandidates().isEmpty()) {
            int displayOrder = 0;
            for (AiFinancialMetricCandidate candidate : resultOpt.get().getMetricCandidates()) {
                BigDecimal parsedVal = parseNumeric(candidate.getRawValue());
                String unit = StringUtils.hasText(candidate.getRawUnit()) ? candidate.getRawUnit().trim() : "Triệu VNĐ";
                CompanyProfileFinancialRow row = CompanyProfileFinancialRow.builder()
                        .id(UUID.randomUUID().toString())
                        .companyProfileId(companyProfileId)
                        .metricName(candidate.getLabel() != null ? candidate.getLabel().trim() : "")
                        .normalizedKey(candidate.getMetricCode())
                        .value(parsedVal)
                        .unit(unit)
                        .year(targetYear)
                        .quarter(targetQuarter)
                        .displayOrder(displayOrder++)
                        .sourceType("AI_EXTRACTION")
                        .sourceReportId(reportId)
                        .sourceReportTitle(targetTitle)
                        .sourceDocumentId(rawDoc.getId())
                        .sourcePage(candidate.getSourcePage())
                        .sourceMetricId(StringUtils.hasText(candidate.getMetricCode()) ? candidate.getMetricCode() : "EXTRACTED")
                        .publicationDate(now.toLocalDate().toString())
                        .createdAt(now)
                        .updatedAt(now)
                        .lastModifiedBy(userId)
                        .build();
                newRows.add(row);
            }
        }

        if (newRows.isEmpty()) {
            List<CompanyProfileFinancialRow> templateRows = createStandardTemplateRows(companyProfileId, reportId, targetTitle,
                    rawDoc.getId(), "AI_EXTRACTION", targetYear, targetQuarter, now.toLocalDate().toString(), now, userId);
            for (CompanyProfileFinancialRow row : templateRows) {
                row.setSourceMetricId("EXTRACTED");
            }
            newRows.addAll(templateRows);
        }

        rowRepository.saveAll(newRows);

        if (userId != null) {
            auditLogService.log(
                    userId,
                    AuditAction.FINANCIAL_AI_EXTRACTION_RUN,
                    "CompanyProfileFinancialRow",
                    companyProfileId,
                    String.format("Re-extracted AI metrics for report '%s' (%s)", reportId, targetTitle)
            );
        }

        return newRows.stream().map(this::toDto).collect(Collectors.toList());
    }

    public void cancelExtractFinancialReport(String reportId, UserDetailsImpl currentUser) {
        log.info("Cancelled financial AI extraction for report {}", reportId);
        if (currentUser != null) {
            auditLogService.log(
                    currentUser.getId(),
                    AuditAction.FINANCIAL_AI_EXTRACTION_RUN,
                    "CompanyProfileFinancialRow",
                    reportId,
                    "Cancelled AI extraction for report " + reportId
            );
        }
    }

    @Transactional
    public void deleteFinancialReport(String reportId, UserDetailsImpl currentUser) {
        CompanyProfile ownerProfile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        String companyProfileId = ownerProfile.getId();

        List<CompanyProfileFinancialRow> rowsToDelete = rowRepository.findByCompanyProfileId(companyProfileId)
                .stream()
                .filter(r -> reportId.equals(r.getSourceReportId()))
                .collect(Collectors.toList());

        if (rowsToDelete.isEmpty()) {
            return;
        }

        rowRepository.deleteAll(rowsToDelete);

        if (currentUser != null) {
            auditLogService.log(
                    currentUser.getId(),
                    AuditAction.FINANCIAL_ROW_DELETED,
                    "CompanyProfileFinancialRow",
                    companyProfileId,
                    String.format("Deleted financial report '%s' (%d rows) for enterprise %s",
                            reportId, rowsToDelete.size(), companyProfileId)
            );
        }
    }

    private void validatePdfFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessValidationException("Uploaded file cannot be empty.");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.trim().toLowerCase().endsWith(".pdf")) {
            throw new BusinessValidationException("Only PDF files are allowed.");
        }
        if (file.getSize() > MAX_PDF_SIZE_BYTES) {
            throw new BusinessValidationException("File size exceeds 50 MB limit.");
        }
    }

    private RawDocument storeRawDocument(MultipartFile file, String companyProfileId, Long userId) {
        String localFilePath = storageService.store(file);
        LocalDateTime now = LocalDateTime.now();

        RawDocument rawDoc = RawDocument.builder()
                .ownerCompanyProfileId(companyProfileId)
                .targetCompanyProfileId(companyProfileId)
                .source(RawDocument.Source.builder()
                        .type("PDF")
                        .fileName(file.getOriginalFilename())
                        .build())
                .storage(RawDocument.Storage.builder()
                        .provider("LOCAL")
                        .path(localFilePath)
                        .mimeType("application/pdf")
                        .sizeBytes(file.getSize())
                        .build())
                .processing(RawDocument.Processing.builder()
                        .status("UPLOADED")
                        .startedAt(now)
                        .completedAt(now)
                        .build())
                .metadata(RawDocument.Metadata.builder()
                        .uploadedBy(userId != null ? String.valueOf(userId) : "SYSTEM")
                        .uploadedAt(now)
                        .updatedAt(now)
                        .build())
                .build();

        return rawDocumentRepository.save(rawDoc);
    }

    private List<CompanyProfileFinancialRow> createStandardTemplateRows(
            String companyProfileId,
            String reportId,
            String title,
            String documentId,
            String sourceType,
            Integer year,
            String quarter,
            String publicationDate,
            LocalDateTime now,
            Long userId) {

        String[] standardMetrics = new String[]{
                "Doanh thu thuần về bán hàng và cung cấp dịch vụ",
                "Lợi nhuận gộp về bán hàng và cung cấp dịch vụ",
                "Lợi nhuận thuần từ hoạt động kinh doanh",
                "Tổng lợi nhuận kế toán trước thuế",
                "Lợi nhuận sau thuế thu nhập doanh nghiệp",
                "Tổng cộng tài sản",
                "Nợ phải trả",
                "Vốn chủ sở hữu"
        };

        List<CompanyProfileFinancialRow> templateRows = new ArrayList<>();
        int order = 0;
        for (String metricName : standardMetrics) {
            templateRows.add(CompanyProfileFinancialRow.builder()
                    .id(UUID.randomUUID().toString())
                    .companyProfileId(companyProfileId)
                    .metricName(metricName)
                    .value(BigDecimal.ZERO)
                    .unit("Triệu VNĐ")
                    .year(year)
                    .quarter(quarter)
                    .displayOrder(order++)
                    .sourceType(sourceType)
                    .sourceReportId(reportId)
                    .sourceReportTitle(title)
                    .sourceDocumentId(documentId)
                    .publicationDate(publicationDate)
                    .createdAt(now)
                    .updatedAt(now)
                    .lastModifiedBy(userId)
                    .build());
        }
        return templateRows;
    }

    private BigDecimal parseNumeric(String val) {
        if (!StringUtils.hasText(val)) return BigDecimal.ZERO;
        String clean = val.trim();
        boolean negative = false;
        if ((clean.startsWith("(") && clean.endsWith(")")) || clean.startsWith("-")) {
            negative = true;
        }
        clean = clean.replaceAll("[^0-9.]", "");
        if (clean.isBlank()) return BigDecimal.ZERO;
        try {
            BigDecimal bd = new BigDecimal(clean);
            return negative ? bd.negate() : bd;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String normalizePeriod(String period) {
        if (!StringUtils.hasText(period)) return "FY";
        String p = period.trim().toUpperCase();
        if ("FULL_YEAR".equals(p) || "FULL YEAR".equals(p) || "FY".equals(p)) return "FY";
        if ("Q1".equals(p) || "Q2".equals(p) || "Q3".equals(p) || "Q4".equals(p)) return p;
        return p;
    }

    private CompanyProfileFinancialRowDto toDto(CompanyProfileFinancialRow row) {
        return CompanyProfileFinancialRowDto.builder()
                .id(row.getId())
                .companyProfileId(row.getCompanyProfileId())
                .metricName(row.getMetricName())
                .normalizedKey(row.getNormalizedKey())
                .value(row.getValue())
                .unit(row.getUnit())
                .year(row.getYear())
                .quarter(row.getQuarter())
                .displayOrder(row.getDisplayOrder())
                .sourceType(row.getSourceType())
                .sourceResearchId(row.getSourceResearchId())
                .sourceReportId(row.getSourceReportId())
                .sourceReportTitle(row.getSourceReportTitle())
                .sourceDocumentId(row.getSourceDocumentId())
                .sourcePage(row.getSourcePage())
                .sourceMetricId(row.getSourceMetricId())
                .publicationDate(row.getPublicationDate())
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }
}
