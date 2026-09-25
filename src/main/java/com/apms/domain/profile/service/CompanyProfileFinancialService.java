package com.apms.domain.profile.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.financial.FinancialMetric;
import com.apms.domain.financial.FinancialReportEntry;
import com.apms.domain.financial.FinancialReportReviewStatus;
import com.apms.domain.financial.FinancialResearch;
import com.apms.domain.financial.FinancialResearchStatus;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.profile.CompanyProfileFinancialRow;
import com.apms.domain.profile.dto.BatchUpdateCompanyFinancialsRequest;
import com.apms.domain.profile.dto.CompanyProfileFinancialRowDto;
import com.apms.domain.profile.repository.mongo.CompanyProfileFinancialRowRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileFinancialService {

    private final CompanyProfileFinancialRowRepository rowRepository;
    private final FinancialResearchRepository researchRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final AuditLogService auditLogService;

    /**
     * Strictly read-only query for canonical company profile financial rows.
     * Never performs database writes or hidden mutations.
     */
    public List<CompanyProfileFinancialRowDto> getFinancials(String companyProfileId) {
        List<CompanyProfileFinancialRow> rows = rowRepository.findByCompanyProfileIdOrderByYearDescQuarterDescDisplayOrderAsc(companyProfileId);
        rows.sort((a, b) -> {
            int yComp = Integer.compare(b.getYear() != null ? b.getYear() : 0, a.getYear() != null ? a.getYear() : 0);
            if (yComp != 0) return yComp;
            int qComp = (b.getQuarter() != null ? b.getQuarter() : "").compareTo(a.getQuarter() != null ? a.getQuarter() : "");
            if (qComp != 0) return qComp;
            int ordA = a.getDisplayOrder() != null ? a.getDisplayOrder() : Integer.MAX_VALUE;
            int ordB = b.getDisplayOrder() != null ? b.getDisplayOrder() : Integer.MAX_VALUE;
            return Integer.compare(ordA, ordB);
        });
        return rows.stream().map(this::toDto).collect(Collectors.toList());
    }

    /**
     * Idempotent promotion: converts approved metrics from a FinancialResearch package
     * into canonical CompanyProfileFinancialRow documents.
     * Prevents duplicates by matching sourceMetricId.
     */
    @Transactional
    public int promoteFromApprovedResearch(FinancialResearch research) {
        if (research == null) return 0;

        String companyProfileId = research.getCompanyProfileId();
        if (companyProfileId == null && research.getTaskId() != null) {
            companyProfileId = projectTaskRepository.findWithProjectById(research.getTaskId())
                    .map(t -> t.getTargetCompanyProfileId() != null
                            ? t.getTargetCompanyProfileId()
                            : (t.getProject() != null ? t.getProject().getTargetCompanyProfileId() : null))
                    .orElse(null);
        }

        if (companyProfileId == null) {
            log.warn("Cannot promote FinancialResearch {}: companyProfileId is null", research.getId());
            return 0;
        }

        List<FinancialReportEntry> reports = research.getReports();
        List<FinancialMetric> metrics = research.getMetrics();
        if (reports == null || metrics == null) return 0;

        int promotedCount = 0;
        LocalDateTime now = LocalDateTime.now();

        for (FinancialReportEntry report : reports) {
            if (report.getReviewStatus() != FinancialReportReviewStatus.APPROVED) {
                continue;
            }

            int metricIndex = 0;
            for (FinancialMetric metric : metrics) {
                if (metric.getId() == null) continue;

                boolean belongs = (metric.getSource() != null && report.getId().equals(metric.getSource().getReportEntryId()))
                        || (metric.getSource() != null && metric.getSource().getDocumentId() != null
                        && metric.getSource().getDocumentId().equals(report.getDocumentId()));

                if (!belongs) continue;

                int order = metricIndex++;

                Integer year = (metric.getPeriod() != null && metric.getPeriod().getYear() != null)
                        ? metric.getPeriod().getYear()
                        : (report.getReportingPeriod() != null ? report.getReportingPeriod().getYear() : null);

                String quarter = (metric.getPeriod() != null && metric.getPeriod().getPeriod() != null)
                        ? metric.getPeriod().getPeriod()
                        : (report.getReportingPeriod() != null ? report.getReportingPeriod().getPeriod() : "FY");

                BigDecimal metricVal = resolveMetricValue(metric);
                String metricUnit = resolveMetricUnit(metric);

                // Targeted idempotent check & repair
                var existingOpt = rowRepository.findByCompanyProfileIdAndSourceMetricId(companyProfileId, metric.getId());
                if (existingOpt.isPresent()) {
                    CompanyProfileFinancialRow existing = existingOpt.get();
                    // Only repair untouched promoted rows (never overwrite Manager-edited or Manual rows)
                    if ("PROMOTED".equals(existing.getSourceType()) && existing.getLastModifiedBy() == null) {
                        boolean changed = false;
                        if (existing.getValue() == null || existing.getValue().compareTo(metricVal) != 0) {
                            existing.setValue(metricVal);
                            changed = true;
                        }
                        if (metricUnit != null && !metricUnit.equals(existing.getUnit())) {
                            existing.setUnit(metricUnit);
                            changed = true;
                        }
                        if (existing.getDisplayOrder() == null || !existing.getDisplayOrder().equals(order)) {
                            existing.setDisplayOrder(order);
                            changed = true;
                        }
                        if (changed) {
                            existing.setUpdatedAt(now);
                            rowRepository.save(existing);
                            promotedCount++;
                        }
                    }
                    continue;
                }

                CompanyProfileFinancialRow row = CompanyProfileFinancialRow.builder()
                        .id(UUID.randomUUID().toString())
                        .companyProfileId(companyProfileId)
                        .metricName(metric.getLabel())
                        .normalizedKey(metric.getNormalizedKey())
                        .value(metricVal)
                        .unit(metricUnit)
                        .year(year)
                        .quarter(quarter)
                        .displayOrder(order)
                        .sourceType("PROMOTED")
                        .sourceResearchId(research.getId())
                        .sourceReportId(report.getId())
                        .sourceReportTitle(report.getTitle())
                        .sourceDocumentId(report.getDocumentId())
                        .sourcePage(metric.getSource() != null ? metric.getSource().getPage() : null)
                        .sourceMetricId(metric.getId())
                        .publicationDate(report.getPublicationDate() != null ? report.getPublicationDate().toString() : null)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();

                rowRepository.save(row);
                promotedCount++;
            }
        }

        if (promotedCount > 0) {
            log.info("Promoted {} approved financial metrics into canonical rows for company {}", promotedCount, companyProfileId);
        }
        return promotedCount;
    }

    /**
     * Explicit backfill/migration for existing approved research.
     * Idempotent: safe to run multiple times without duplicating rows.
     */
    @Transactional
    public int backfillExistingApprovedResearch(String companyProfileId) {
        List<FinancialResearch> researchList = new ArrayList<>(
                researchRepository.findByCompanyProfileIdAndStatus(companyProfileId, FinancialResearchStatus.APPROVED));

        try {
            List<ProjectTask> tasks = new ArrayList<>();
            tasks.addAll(projectTaskRepository.findByTargetCompanyProfileId(companyProfileId));
            tasks.addAll(projectTaskRepository.findByProject_TargetCompanyProfileId(companyProfileId));

            for (ProjectTask task : tasks) {
                researchRepository.findByTaskId(task.getId()).ifPresent(r -> {
                    if (r.getStatus() == FinancialResearchStatus.APPROVED) {
                        if (researchList.stream().noneMatch(existing -> existing.getId().equals(r.getId()))) {
                            researchList.add(r);
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Failed to find tasks for backfill of company profile {}: {}", companyProfileId, e.getMessage());
        }

        int totalPromoted = 0;
        for (FinancialResearch research : researchList) {
            totalPromoted += promoteFromApprovedResearch(research);
        }
        return totalPromoted;
    }

    /**
     * Manager Batch CRUD for canonical company profile financial rows.
     * Enforces row ownership on update/delete and preserves research provenance.
     */
    @Transactional
    public List<CompanyProfileFinancialRowDto> batchUpdateFinancials(
            String companyProfileId,
            BatchUpdateCompanyFinancialsRequest request,
            UserDetailsImpl currentUser) {

        LocalDateTime now = LocalDateTime.now();
        Long userId = currentUser != null ? currentUser.getId() : null;

        // 1. Process Deletions
        if (request.getDeletedIds() != null && !request.getDeletedIds().isEmpty()) {
            for (String deleteId : request.getDeletedIds()) {
                if (deleteId == null || deleteId.isBlank()) continue;
                CompanyProfileFinancialRow row = rowRepository.findById(deleteId).orElse(null);
                if (row != null) {
                    // Ownership check: must match path companyProfileId
                    if (!companyProfileId.equals(row.getCompanyProfileId())) {
                        throw new AccessDeniedException("Financial row " + deleteId + " does not belong to company " + companyProfileId);
                    }
                    rowRepository.delete(row);
                    if (userId != null) {
                        auditLogService.log(
                                userId,
                                AuditAction.FINANCIAL_ROW_DELETED,
                                "CompanyProfileFinancialRow",
                                row.getId(),
                                String.format("Deleted financial row for company %s: %s (%s %s)",
                                        companyProfileId, row.getMetricName(), row.getYear(), row.getQuarter())
                        );
                    }
                }
            }
        }

        // 2. Process Upserts (Inserts and Updates)
        if (request.getRows() != null && !request.getRows().isEmpty()) {
            for (CompanyProfileFinancialRowDto rowDto : request.getRows()) {
                if (rowDto == null) continue;

                if (rowDto.getId() != null && !rowDto.getId().isBlank()) {
                    // Existing Row Update
                    CompanyProfileFinancialRow existing = rowRepository.findById(rowDto.getId())
                            .orElseThrow(() -> new BusinessValidationException("Financial row not found: " + rowDto.getId()));

                    // Ownership check
                    if (!companyProfileId.equals(existing.getCompanyProfileId())) {
                        throw new AccessDeniedException("Financial row " + rowDto.getId() + " does not belong to company " + companyProfileId);
                    }

                    String oldMetric = existing.getMetricName();
                    java.math.BigDecimal oldValue = existing.getValue();
                    String oldUnit = existing.getUnit();
                    Integer oldYear = existing.getYear();
                    String oldQuarter = existing.getQuarter();

                    // Update canonical business fields only
                    existing.setMetricName(rowDto.getMetricName() != null ? rowDto.getMetricName().trim() : "");
                    existing.setValue(rowDto.getValue());
                    existing.setUnit(rowDto.getUnit() != null ? rowDto.getUnit().trim() : "");
                    existing.setYear(rowDto.getYear());
                    existing.setQuarter(rowDto.getQuarter() != null ? rowDto.getQuarter().trim() : "FY");
                    if (rowDto.getDisplayOrder() != null) {
                        existing.setDisplayOrder(rowDto.getDisplayOrder());
                    }
                    existing.setUpdatedAt(now);
                    existing.setLastModifiedBy(userId);

                    // PROVENANCE IS STRICTLY PRESERVED:
                    // sourceResearchId, sourceReportId, sourceReportTitle, sourceDocumentId, sourcePage, sourceType remain intact!
                    rowRepository.save(existing);

                    if (userId != null) {
                        auditLogService.log(
                                userId,
                                AuditAction.FINANCIAL_ROW_UPDATED,
                                "CompanyProfileFinancialRow",
                                existing.getId(),
                                String.format("Updated financial row for company %s [%s]: metric='%s'->'%s', value=%s->%s, unit='%s'->'%s', period=%s %s -> %s %s",
                                        companyProfileId, existing.getId(),
                                        oldMetric, existing.getMetricName(),
                                        oldValue, existing.getValue(),
                                        oldUnit, existing.getUnit(),
                                        oldYear, oldQuarter, existing.getYear(), existing.getQuarter())
                        );
                    }
                } else {
                    // New Manual Row
                    int maxOrder = rowRepository.findByCompanyProfileId(companyProfileId).stream()
                            .map(CompanyProfileFinancialRow::getDisplayOrder)
                            .filter(java.util.Objects::nonNull)
                            .max(Integer::compareTo)
                            .orElse(-1);
                    int nextOrder = (rowDto.getDisplayOrder() != null) ? rowDto.getDisplayOrder() : (maxOrder + 1);

                    CompanyProfileFinancialRow newRow = CompanyProfileFinancialRow.builder()
                            .id(UUID.randomUUID().toString())
                            .companyProfileId(companyProfileId)
                            .metricName(rowDto.getMetricName() != null ? rowDto.getMetricName().trim() : "")
                            .value(rowDto.getValue())
                            .unit(rowDto.getUnit() != null ? rowDto.getUnit().trim() : "")
                            .year(rowDto.getYear())
                            .quarter(rowDto.getQuarter() != null ? rowDto.getQuarter().trim() : "FY")
                            .displayOrder(nextOrder)
                            .sourceType("MANUAL")
                            .sourceResearchId(rowDto.getSourceResearchId())
                            .sourceReportId(rowDto.getSourceReportId())
                            .sourceReportTitle(rowDto.getSourceReportTitle())
                            .sourceDocumentId(rowDto.getSourceDocumentId())
                            .sourcePage(rowDto.getSourcePage())
                            .publicationDate(rowDto.getPublicationDate())
                            .createdAt(now)
                            .updatedAt(now)
                            .lastModifiedBy(userId)
                            .build();

                    rowRepository.save(newRow);

                    if (userId != null) {
                        auditLogService.log(
                                userId,
                                AuditAction.FINANCIAL_ROW_CREATED,
                                "CompanyProfileFinancialRow",
                                newRow.getId(),
                                String.format("Created manual financial row for company %s: %s (%s %s) = %s %s",
                                        companyProfileId, newRow.getMetricName(), newRow.getYear(), newRow.getQuarter(),
                                        newRow.getValue(), newRow.getUnit())
                        );
                    }
                }
            }
        }

        // 3. Summary Profile Audit Log
        if (userId != null) {
            auditLogService.log(
                    userId,
                    AuditAction.COMPANY_PROFILE_UPDATED,
                    "CompanyProfile",
                    companyProfileId,
                    "Batch updated canonical financial rows (saved " + (request.getRows() != null ? request.getRows().size() : 0)
                            + ", deleted " + (request.getDeletedIds() != null ? request.getDeletedIds().size() : 0) + ")");
        }

        return getFinancials(companyProfileId);
    }

    public static BigDecimal resolveMetricValue(FinancialMetric metric) {
        if (metric == null) {
            throw new IllegalArgumentException("FinancialMetric cannot be null");
        }
        // 1. If rawValue is present, parse it directly as it represents the exact statement figure
        if (metric.getRawValue() != null && !metric.getRawValue().isBlank()) {
            return parseFinancialValue(metric.getRawValue());
        }
        // 2. Fallback to normalizedValue if rawValue is missing
        if (metric.getNormalizedValue() != null) {
            return metric.getNormalizedValue();
        }
        throw new BusinessValidationException("Cannot resolve financial value for metric: " + metric.getLabel());
    }

    public static BigDecimal parseFinancialValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Financial rawValue is null or blank");
        }

        String s = rawValue.trim();
        boolean negative = false;
        if ((s.startsWith("(") && s.endsWith(")")) || s.startsWith("-")) {
            negative = true;
        }

        // Keep only digits, dots, commas
        String cleaned = s.replaceAll("[^0-9.,]", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("No numeric digits found in rawValue: " + rawValue);
        }

        int lastDot = cleaned.lastIndexOf('.');
        int lastComma = cleaned.lastIndexOf(',');

        String standardNumberStr;

        if (lastDot != -1 && lastComma != -1) {
            // Both dot and comma present
            if (lastDot > lastComma) {
                // e.g. 14,602,141.50 -> comma is thousand separator, dot is decimal
                standardNumberStr = cleaned.replace(",", "");
            } else {
                // e.g. 14.602.141,50 -> dot is thousand separator, comma is decimal
                standardNumberStr = cleaned.replace(".", "").replace(',', '.');
            }
        } else if (lastDot != -1) {
            // Only dots present
            int firstDot = cleaned.indexOf('.');
            if (firstDot != lastDot) {
                // Multiple dots: e.g. 14.602.141 or 1.728.183.370 -> dots are thousand separators
                standardNumberStr = cleaned.replace(".", "");
            } else {
                // Single dot: e.g. 859.682 or 14.5
                // If followed by exactly 3 digits (e.g. 859.682, 94.881, 2.522) in accounting data, it's a thousand separator
                int digitsAfterDot = cleaned.length() - 1 - lastDot;
                if (digitsAfterDot == 3) {
                    standardNumberStr = cleaned.replace(".", "");
                } else {
                    standardNumberStr = cleaned;
                }
            }
        } else if (lastComma != -1) {
            // Only commas present
            int firstComma = cleaned.indexOf(',');
            if (firstComma != lastComma) {
                // Multiple commas: e.g. 14,602,141 -> commas are thousand separators
                standardNumberStr = cleaned.replace(",", "");
            } else {
                // Single comma: e.g. 859,682 or 14,5
                int digitsAfterComma = cleaned.length() - 1 - lastComma;
                if (digitsAfterComma == 3) {
                    standardNumberStr = cleaned.replace(",", "");
                } else {
                    standardNumberStr = cleaned.replace(',', '.');
                }
            }
        } else {
            // No separators, purely digits: e.g. 14602141
            standardNumberStr = cleaned;
        }

        BigDecimal bd = new BigDecimal(standardNumberStr);
        return negative ? bd.negate() : bd;
    }

    public static String resolveMetricUnit(FinancialMetric metric) {
        if (metric == null) return "Triệu VND";
        String rawUnit = metric.getRawUnit();
        String normUnit = metric.getNormalizedUnit();
        String u = (rawUnit != null && !rawUnit.isBlank()) ? rawUnit.trim() : (normUnit != null ? normUnit.trim() : "Triệu VND");
        if ("MILLION_VND".equalsIgnoreCase(u) || "TRIỆU VND".equalsIgnoreCase(u) || "TRIỆU VNĐ".equalsIgnoreCase(u) || "MILION_VND".equalsIgnoreCase(u)) {
            return "Triệu VND";
        }
        if ("BILLION_VND".equalsIgnoreCase(u) || "TỶ VND".equalsIgnoreCase(u) || "TỶ VNĐ".equalsIgnoreCase(u)) {
            return "Tỷ VND";
        }
        if ("THOUSAND_VND".equalsIgnoreCase(u) || "NGHÌN VND".equalsIgnoreCase(u) || "NGHÌN VNĐ".equalsIgnoreCase(u)) {
            return "Nghìn VND";
        }
        if ("PERCENT".equalsIgnoreCase(u) || "%".equals(u)) {
            return "%";
        }
        return u;
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
