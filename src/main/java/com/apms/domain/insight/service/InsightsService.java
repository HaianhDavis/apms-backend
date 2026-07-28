package com.apms.domain.insight.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.SystemRole;
import com.apms.domain.admin.dto.AuditLogResponse;
import com.apms.domain.audit.AuditLog;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InsightsService {

    private final CompanyProfileRepository profileRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final AuditLogRepository auditLogRepository;
    private final CompanyCandidateRepository candidateRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getActivityTimeline() {
        return auditLogRepository.findAll(PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "timestamp")))
                .stream()
                .map(this::toActivityItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUserRegistrationSeries() {
        Map<Month, Long> byMonth = accountRepository.findAll().stream()
                .filter(a -> a.getCreatedAt() != null)
                .collect(Collectors.groupingBy(
                        a -> a.getCreatedAt().getMonth(),
                        Collectors.counting()
                ));

        List<Map<String, Object>> points = new ArrayList<>();
        for (Month month : Month.values()) {
            Map<String, Object> item = new HashMap<>();
            item.put("label", month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            item.put("value", byMonth.getOrDefault(month, 0L));
            points.add(item);
        }
        return points;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLoginActivitySeries() {
        Map<LocalDate, Long> byDay = auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.LOGIN && a.getTimestamp() != null)
                .collect(Collectors.groupingBy(
                        a -> a.getTimestamp().toLocalDate(),
                        Collectors.counting()
                ));

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Map<String, Object> item = new HashMap<>();
            item.put("label", day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            item.put("value", byDay.getOrDefault(day, 0L));
            points.add(item);
        }
        return points;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSystemHealth() {
        long totalAccounts = accountRepository.count();
        long activeAccounts = accountRepository.findAll().stream().filter(a -> Boolean.TRUE.equals(a.getIsActive())).count();

        return List.of(
                item("Database", 99.9, "%", "ok"),
                item("API", 99.7, "%", "ok"),
                item("Auth", 98.8, "%", "ok"),
                item("Users", totalAccounts == 0 ? 0 : Math.round((activeAccounts * 100.0) / totalAccounts), "%", "ok")
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRoleDistribution() {
        Map<SystemRole, Long> counts = accountRepository.findAll().stream()
                .flatMap(a -> a.getRoles().stream())
                .collect(Collectors.groupingBy(role -> role, Collectors.counting()));

        return List.of(
                roleItem("Business Owner", counts.getOrDefault(SystemRole.BUSINESS_OWNER, 0L), "badge-green"),
                roleItem("BD Manager", counts.getOrDefault(SystemRole.BUSINESS_DEVELOPMENT_MANAGER, 0L), "badge-yellow"),
                roleItem("Research Staff", counts.getOrDefault(SystemRole.RESEARCH_STAFF, 0L), "badge-blue")
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRiskMonitoring() {
        List<CompanyProfile> profiles = profileRepository.findAll(PageRequest.of(0, 50)).getContent();
        return profiles.stream()
                .map(profile -> {
                    String tradeName = getTradeName(profile);
                    String reviewStatus = profile.getReviewStatus() != null ? profile.getReviewStatus() : "UNVERIFIED";
                    int risk = switch (reviewStatus) {
                        case "VERIFIED" -> 25;
                        case "PENDING_REVIEW", "IN_PROGRESS" -> 55;
                        default -> 70;
                    };
                    Map<String, Object> item = new HashMap<>();
                    item.put("companyId", profile.getCompanyId());
                    item.put("tradeName", tradeName);
                    item.put("legalName", profile.getIdentity() != null ? profile.getIdentity().getLegalName() : tradeName);
                    item.put("taxCode", profile.getIdentity() != null && profile.getIdentity().getTaxCode() != null ? profile.getIdentity().getTaxCode() : "");
                    item.put("industry", profile.getBusiness() != null && profile.getBusiness().getIndustries() != null && !profile.getBusiness().getIndustries().isEmpty()
                            ? profile.getBusiness().getIndustries().get(0)
                            : "Unknown");
                    item.put("reviewStatus", reviewStatus);
                    item.put("riskLevel", risk > 60 ? "High" : risk > 40 ? "Medium" : "Low");
                    item.put("riskScore", risk);
                    return item;
                })
                .sorted(Comparator.comparingInt(m -> -(Integer) m.get("riskScore")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTeamKpi() {
        List<Account> accounts = accountRepository.findAll();
        List<Project> projects = projectRepository.findAll();
        long totalCandidates = candidateRepository.count();
        long approved = candidateRepository.countByStatus(CandidateStatus.APPROVED);

        return accounts.stream()
                .map(account -> {
                    long projectCount = projects.stream()
                            .filter(p -> p.getMembers() != null && p.getMembers().stream().anyMatch(m -> m.getAccountId() != null && m.getAccountId().equals(account.getId())))
                            .count();
                    long reviewed = Math.max(1, projectCount + totalCandidates / Math.max(1, accounts.size()));
                    int accuracy = (int) Math.min(99, 70 + (approved % 25));
                    boolean bonus = reviewed >= 2 && accuracy >= 75;
                    return Map.<String, Object>of(
                            "name", account.getUsername() != null ? account.getUsername() : account.getEmail().split("@")[0],
                            "role", displayRole(account.getRoles()),
                            "companiesReviewed", reviewed,
                            "target", 3,
                            "accuracy", accuracy,
                            "bonus", bonus,
                            "aiReviewed", Math.max(1, (int) (reviewed * 0.6))
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getReports() {
        List<Project> projects = projectRepository.findAll(PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        return projects.stream()
                .map(project -> Map.<String, Object>of(
                        "id", project.getId(),
                        "title", project.getProjectName(),
                        "date", project.getCreatedAt() != null ? project.getCreatedAt().toLocalDate().toString() : LocalDate.now().toString(),
                        "status", project.getStatus() != null && "COMPLETED".equals(project.getStatus().name()) ? "published" : "draft"
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAnalysisHistory() {
        return projectRepository.findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .stream()
                .map(project -> Map.<String, Object>of(
                        "id", project.getId(),
                        "companyName", project.getTargetCompanyName(),
                        "analysisType", project.getProjectType().name(),
                        "status", project.getStatus().name(),
                        "date", project.getUpdatedAt() != null ? project.getUpdatedAt().toLocalDate().toString() : LocalDate.now().toString(),
                        "summary", project.getDescription() != null ? project.getDescription() : project.getProjectName()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTrainingSessions() {
        return List.of(
                session(1, "Data Extraction Basics", LocalDate.now().minusDays(1).toString(), "09:00", 88),
                session(2, "Relationship Classification", LocalDate.now().minusDays(3).toString(), "14:30", 74),
                session(3, "Profile Review Workshop", LocalDate.now().minusDays(5).toString(), "10:15", 91)
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTrainingQuestions() {
        return List.of(
                question(1, "Which relationship type indicates a strategic partner?", List.of("PARTNER_WITH", "COMPETITOR_OF", "SUPPLIER_OF"), 0),
                question(2, "What status should a candidate have before approval?", List.of("DRAFT", "APPROVED", "REJECTED"), 0),
                question(3, "Which role can approve candidates?", List.of("BUSINESS_OWNER", "BUSINESS_DEVELOPMENT_MANAGER", "RESEARCH_STAFF"), 1)
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLearningCourses() {
        return List.of(
                course(1, "APMS Onboarding", "Beginner", 100, 6, "2h 30m"),
                course(2, "Company Profile Review", "Intermediate", 60, 8, "3h 15m"),
                course(3, "AI Assisted Validation", "Advanced", 20, 10, "4h 10m")
        );
    }

    private Map<String, Object> toActivityItem(AuditLog log) {
        Map<String, Object> item = new HashMap<>();
        item.put("title", log.getAction().name().replace('_', ' '));
        item.put("desc", log.getDetail() != null ? log.getDetail() : log.getEntityType() + " " + (log.getEntityId() != null ? log.getEntityId() : ""));
        item.put("time", log.getTimestamp() != null ? log.getTimestamp().toString() : "");
        item.put("timestamp", log.getTimestamp() != null ? log.getTimestamp().toString() : "");
        item.put("color", switch (log.getAction()) {
            case APPROVE_CANDIDATE, CREATE_PROFILE, UPDATE_PROFILE -> "#10B981";
            case REJECT_CANDIDATE -> "#EF4444";
            case LOGIN, LOGOUT -> "#2563EB";
            default -> "#64748B";
        });
        return item;
    }

    private Map<String, Object> item(String label, Number value, String unit, String status) {
        return Map.of("label", label, "value", value, "unit", unit, "status", status);
    }

    private Map<String, Object> roleItem(String role, long count, String badge) {
        return Map.of("role", role, "count", count, "badge", badge);
    }

    private Map<String, Object> session(int id, String topic, String date, String time, int score) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("topic", topic);
        item.put("date", date);
        item.put("time", time);
        item.put("score", score);
        return item;
    }

    private Map<String, Object> question(int id, String q, List<String> options, int correct) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("q", q);
        item.put("options", options);
        item.put("correct", correct);
        return item;
    }

    private Map<String, Object> course(int id, String title, String level, int progress, int lessons, String duration) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("title", title);
        item.put("level", level);
        item.put("progress", progress);
        item.put("lessons", lessons);
        item.put("duration", duration);
        return item;
    }

    private String displayRole(Set<SystemRole> roles) {
        if (roles == null || roles.isEmpty()) return "Research Staff";
        SystemRole role = roles.iterator().next();
        return switch (role) {
            case SYSTEM_ADMIN -> "System Administrator";
            case BUSINESS_OWNER -> "Business Owner";
            case BUSINESS_DIRECTOR -> "Business Director";
            case BUSINESS_DEVELOPMENT_MANAGER -> "BD Manager";
            case BUSINESS_DEVELOPMENT_STAFF -> "BD Staff";
            case KEY_MEMBER -> "Key Member";
            case RESEARCH_STAFF -> "Research Staff";
        };
    }

    private String getTradeName(CompanyProfile profile) {
        if (profile.getIdentity() == null) return "Unknown";
        if (profile.getIdentity().getTradeName() != null) return profile.getIdentity().getTradeName();
        return profile.getIdentity().getLegalName() != null ? profile.getIdentity().getLegalName() : "Unknown";
    }
}
