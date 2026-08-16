package com.apms.domain.audit.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.util.CsvExportUtil;
import com.apms.domain.audit.AuditLog;
import com.apms.domain.audit.dto.AuditLogResponse;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;
    private final AccountRepository accountRepository;
    private final CompanyProfileRepository companyProfileRepository;

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> searchAuditLogs(
            Long actorUserId,
            String action,
            String entityType,
            String entityId,
            String keyword,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            Pageable pageable) {

        Specification<AuditLog> spec = buildSpecification(actorUserId, action, entityType, entityId, keyword, fromDate, toDate);
        return auditLogRepository.findAll(spec, pageable).map(this::toResponse);
    }

    public byte[] exportAuditLogs(
            Long actorUserId,
            String action,
            String entityType,
            String entityId,
            String keyword,
            LocalDateTime fromDate,
            LocalDateTime toDate) {

        Specification<AuditLog> spec = buildSpecification(actorUserId, action, entityType, entityId, keyword, fromDate, toDate);
        List<AuditLog> logs = auditLogRepository.findAll(spec);

        StringBuilder csv = new StringBuilder();
        csv.append("ID,Actor User ID,Action,Entity Type,Entity ID,Details,Created At\n");
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (AuditLog logItem : logs) {
            csv.append(logItem.getId()).append(",");
            csv.append(logItem.getActorAccountId() != null ? logItem.getActorAccountId() : "").append(",");
            csv.append(CsvExportUtil.escapeField(logItem.getAction().name())).append(",");
            csv.append(CsvExportUtil.escapeField(logItem.getEntityType())).append(",");
            csv.append(CsvExportUtil.escapeField(logItem.getEntityId())).append(",");
            csv.append(CsvExportUtil.escapeField(logItem.getDetail())).append(",");
            csv.append(logItem.getTimestamp() != null ? logItem.getTimestamp().format(dtf) : "").append("\n");
        }

        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.AUDIT_LOG_EXPORTED, "AuditLog", "ALL", "Exported audit logs CSV");
        }

        return csv.toString().getBytes();
    }

    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
                return ((UserDetailsImpl) auth.getPrincipal()).getId();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Specification<AuditLog> buildSpecification(
            Long actorUserId,
            String action,
            String entityType,
            String entityId,
            String keyword,
            LocalDateTime fromDate,
            LocalDateTime toDate) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (actorUserId != null) {
                predicates.add(cb.equal(root.get("actorAccount").get("id"), actorUserId));
            }
            if (StringUtils.hasText(action)) {
                try {
                    AuditAction auditAction = AuditAction.valueOf(action.toUpperCase());
                    predicates.add(cb.equal(root.get("action"), auditAction));
                } catch (IllegalArgumentException e) {
                    // Invalid action, force false predicate
                    predicates.add(cb.disjunction());
                }
            }
            if (StringUtils.hasText(entityType)) {
                predicates.add(cb.equal(root.get("entityType"), entityType));
            }
            if (StringUtils.hasText(entityId)) {
                predicates.add(cb.equal(root.get("entityId"), entityId));
            }
            if (StringUtils.hasText(keyword)) {
                String pattern = "%" + keyword.trim().toLowerCase() + "%";
                List<Predicate> keywordPredicates = new ArrayList<>();
                keywordPredicates.add(cb.like(cb.lower(root.get("actorAccount").get("email")), pattern));
                keywordPredicates.add(cb.like(cb.lower(root.get("action").as(String.class)), pattern));
                keywordPredicates.add(cb.like(cb.lower(root.get("entityType")), pattern));
                keywordPredicates.add(cb.like(cb.lower(root.get("entityId")), pattern));
                keywordPredicates.add(cb.like(cb.lower(root.get("detail")), pattern));
                predicates.add(cb.or(keywordPredicates.toArray(new Predicate[0])));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), toDate));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AuditLogResponse toResponse(AuditLog log) {
        String email = null;
        if (log.getActorAccount() != null) {
            email = log.getActorAccount().getEmail();
        }

        return AuditLogResponse.builder()
                .id(log.getId())
                .actorAccountId(log.getActorAccountId())
                .actorEmail(email)
                .action(log.getAction().name())
                .actionLabel(toActionLabel(log.getAction().name()))
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .entityName(resolveEntityName(log.getEntityType(), log.getEntityId()))
                .detail(log.getDetail())
                .timestamp(log.getTimestamp())
                .build();
    }

    private String toActionLabel(String action) {
        if (!StringUtils.hasText(action)) return action;
        StringBuilder label = new StringBuilder();
        for (String word : action.split("_")) {
            if (word.isBlank()) continue;
            if (label.length() > 0) label.append(' ');
            label.append("IP".equalsIgnoreCase(word) ? "IP"
                    : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase());
        }
        return label.toString();
    }

    private String resolveEntityName(String entityType, String entityId) {
        if (!StringUtils.hasText(entityType) || !StringUtils.hasText(entityId)) return null;
        try {
            if ("Account".equalsIgnoreCase(entityType)) {
                return accountRepository.findById(Long.parseLong(entityId))
                        .map(account -> account.getEmail())
                        .orElse(null);
            }
            if ("CompanyProfile".equalsIgnoreCase(entityType)) {
                return companyProfileRepository.findById(entityId)
                        .or(() -> companyProfileRepository.findByCompanyId(entityId))
                        .map(this::companyDisplayName)
                        .orElse(null);
            }
        } catch (Exception e) {
            log.debug("Could not resolve entity name for {} [{}]: {}", entityType, entityId, e.getMessage());
        }
        return null;
    }

    private String companyDisplayName(CompanyProfile profile) {
        if (profile == null) return null;
        if (profile.getIdentity() != null) {
            if (StringUtils.hasText(profile.getIdentity().getLegalName())) return profile.getIdentity().getLegalName();
            if (StringUtils.hasText(profile.getIdentity().getTradeName())) return profile.getIdentity().getTradeName();
        }
        return null;
    }
}
