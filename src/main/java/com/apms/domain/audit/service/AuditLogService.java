package com.apms.domain.audit.service;

import com.apms.common.enums.AuditAction;
import com.apms.domain.audit.AuditLog;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AccountRepository accountRepository;

    @Transactional
    public void log(Long userId, AuditAction action, String entityType, String entityId, String detail) {
        AuditLog auditLog = AuditLog.builder()
                .actorAccount(accountRepository.getReferenceById(userId))
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .detail(detail)
                .build();
        auditLogRepository.save(auditLog);
    }
}
