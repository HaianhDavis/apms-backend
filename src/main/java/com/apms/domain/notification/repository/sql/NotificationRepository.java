package com.apms.domain.notification.repository.sql;

import com.apms.common.enums.NotificationType;
import com.apms.domain.notification.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {
    @Query("""
            select case when count(n) > 0 then true else false end
            from Notification n
            where n.recipientAccount.id = :recipientId
              and n.type = :type
              and n.actionType = :actionType
              and n.projectId = :projectId
              and n.taskId = :taskId
              and n.submissionId = :submissionId
              and ((:documentId is null and n.documentId is null) or n.documentId = :documentId)
              and n.isDeleted = false
            """)
    boolean existsDocumentActionNotification(
            @Param("recipientId") Long recipientId,
            @Param("type") NotificationType type,
            @Param("actionType") String actionType,
            @Param("projectId") Long projectId,
            @Param("taskId") Long taskId,
            @Param("submissionId") Long submissionId,
            @Param("documentId") String documentId);
}
