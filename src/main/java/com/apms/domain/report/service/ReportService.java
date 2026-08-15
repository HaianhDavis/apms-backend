//package com.apms.domain.report.service;
//
//import com.apms.common.enums.AuditAction;
//import com.apms.common.util.CsvExportUtil;
//import com.apms.domain.audit.service.AuditLogService;
//import com.apms.domain.profile.CompanyProfile;
//import com.apms.domain.report.dto.CompanyReportItemResponse;
//import com.apms.domain.score.ScoreSnapshot;
//import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
//import com.apms.security.UserDetailsImpl;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageImpl;
//import org.springframework.data.domain.Pageable;
//import org.springframework.data.mongodb.core.MongoTemplate;
//import org.springframework.data.mongodb.core.query.Criteria;
//import org.springframework.data.mongodb.core.query.Query;
//import org.springframework.data.neo4j.core.Neo4jClient;
//import org.springframework.security.core.Authentication;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.util.StringUtils;
//
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.List;
//import java.util.stream.Collectors;
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class ReportService {
//
//    private final MongoTemplate mongoTemplate;
//    private final Neo4jClient neo4jClient;
//    private final ScoreSnapshotRepository scoreSnapshotRepository;
//    private final AuditLogService auditLogService;
//
//    @Transactional(readOnly = true)
//    public Page<CompanyReportItemResponse> getCompanyReports(
//            String relationshipType,
//            String companyProfileId,
//            String keyword,
//            LocalDateTime fromDate,
//            LocalDateTime toDate,
//            Pageable pageable) {
//
//        Criteria criteria = Criteria.where("isDeleted").ne(true);
//
//        if (StringUtils.hasText(companyProfileId)) {
//            criteria.and("id").is(companyProfileId);
//        }
//
//        if (StringUtils.hasText(keyword)) {
//            criteria.orOperator(
//                    Criteria.where("identity.legalName").regex(keyword, "i"),
//                    Criteria.where("identity.tradeName").regex(keyword, "i")
//            );
//        }
//
//        if (fromDate != null) {
//            criteria.and("metadata.createdAt").gte(fromDate);
//        }
//        if (toDate != null) {
//            criteria.andOperator(Criteria.where("metadata.createdAt").lte(toDate)); // Handle overlapping with fromDate
//        }
//
//        if (StringUtils.hasText(relationshipType)) {
//            // Validate relationship type
//            List<String> validTypes = List.of("PARTNER_WITH", "COMPETITOR_OF", "POTENTIAL_PARTNER_OF", "SUPPLIER_OF", "CUSTOMER_OF");
//            if (!validTypes.contains(relationshipType)) {
//                return Page.empty(pageable);
//            }
//
//            String cypher = String.format("MATCH (c:Company)-[r]-(:Company) WHERE type(r) = '%s' RETURN DISTINCT c.companyId AS companyId", relationshipType);
//            List<String> neo4jCompanyIds = new java.util.ArrayList<>(neo4jClient.query(cypher)
//                    .fetchAs(String.class)
//                    .mappedBy((typeSystem, record) -> record.get("companyId").asString())
//                    .all());
//
//            if (neo4jCompanyIds.isEmpty()) {
//                return Page.empty(pageable);
//            }
//
//            criteria.and("companyId").in(neo4jCompanyIds);
//        }
//
//        Query query = new Query(criteria);
//        long total = mongoTemplate.count(query, CompanyProfile.class);
//        query.with(pageable);
//        List<CompanyProfile> profiles = mongoTemplate.find(query, CompanyProfile.class);
//
//        List<CompanyReportItemResponse> responses = profiles.stream().map(profile -> {
//            ScoreSnapshot latestScore = null;
//            if (StringUtils.hasText(profile.getCompanyId())) {
//                List<ScoreSnapshot> snapshots = scoreSnapshotRepository.findByCompanyIdAndEvaluatedRoleIsNullOrderByCreatedAtDesc(profile.getCompanyId());
//                if (!snapshots.isEmpty()) {
//                    latestScore = snapshots.get(0);
//                }
//            }
//            return buildResponse(profile, latestScore, relationshipType); // Simplified relationship extraction
//        }).collect(Collectors.toList());
//
//        return new PageImpl<>(responses, pageable, total);
//    }
//
//    public byte[] exportCompanyReports(
//            String relationshipType,
//            String companyProfileId,
//            String keyword,
//            LocalDateTime fromDate,
//            LocalDateTime toDate) {
//
//        // Fetch all matching data (unpaged or large page)
//        Page<CompanyReportItemResponse> data = getCompanyReports(relationshipType, companyProfileId, keyword, fromDate, toDate, Pageable.unpaged());
//
//        StringBuilder csv = new StringBuilder();
//        // Header
//        csv.append("Company Profile ID,Company ID,Company Name,Industries,Markets,Relationship Types,Partner Fit Score,Competition Score,Risk Score,Relationship Score,Last Score Date\n");
//
//        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
//
//        for (CompanyReportItemResponse item : data.getContent()) {
//            csv.append(CsvExportUtil.escapeField(item.getCompanyProfileId())).append(",");
//            csv.append(CsvExportUtil.escapeField(item.getCompanyId())).append(",");
//            csv.append(CsvExportUtil.escapeField(item.getName())).append(",");
//            csv.append(CsvExportUtil.escapeList(item.getIndustries())).append(",");
//            csv.append(CsvExportUtil.escapeList(item.getMarkets())).append(",");
//            csv.append(CsvExportUtil.escapeList(item.getRelationshipTypes())).append(",");
//            csv.append(item.getPartnerFitScore() != null ? item.getPartnerFitScore() : "").append(",");
//            csv.append(item.getCompetitionScore() != null ? item.getCompetitionScore() : "").append(",");
//            csv.append(item.getRiskScore() != null ? item.getRiskScore() : "").append(",");
//            csv.append(item.getRelationshipScore() != null ? item.getRelationshipScore() : "").append(",");
//            csv.append(item.getLastScoreDate() != null ? item.getLastScoreDate().format(dtf) : "").append("\n");
//        }
//
//        Long currentUserId = getCurrentUserId();
//        if (currentUserId != null) {
//            auditLogService.log(currentUserId, AuditAction.REPORT_EXPORTED, "CompanyReport", "ALL", "Exported company report CSV");
//        }
//
//        return csv.toString().getBytes();
//    }
//
//    private Long getCurrentUserId() {
//        try {
//            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
//            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
//                return ((UserDetailsImpl) auth.getPrincipal()).getId();
//            }
//        } catch (Exception ignored) {}
//        return null;
//    }
//
//    private CompanyReportItemResponse buildResponse(CompanyProfile p, ScoreSnapshot score, String explicitRelType) {
//        String name = "";
//        if (p.getIdentity() != null) {
//            name = StringUtils.hasText(p.getIdentity().getLegalName()) ? p.getIdentity().getLegalName() : p.getIdentity().getTradeName();
//        }
//
//        List<String> ind = p.getBusiness() != null ? p.getBusiness().getIndustries() : null;
//        List<String> mkts = p.getBusiness() != null ? p.getBusiness().getMarkets() : null;
//
//        return CompanyReportItemResponse.builder()
//                .companyProfileId(p.getId())
//                .companyId(p.getCompanyId())
//                .name(name)
//                .industries(ind)
//                .markets(mkts)
//                .relationshipTypes(explicitRelType != null ? List.of(explicitRelType) : null) // Fetching all relationships is expensive for listing; we attach the queried one for MVP
//                .partnerFitScore(score != null ? score.getPartnerFitScore() : null)
//                .competitionScore(score != null ? score.getCompetitionLevel() : null)
//                .riskScore(score != null ? score.getRiskLevel() : null)
//                .relationshipScore(score != null ? score.getRelationshipStrength() : null)
//                .lastScoreDate(score != null ? score.getCreatedAt() : null)
//                .build();
//    }
//}
