package com.apms.domain.notification;

import com.apms.common.enums.NotificationType;
import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Nationalized;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_account_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account recipientAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_account_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account senderAccount;

    @Nationalized
    @Column(nullable = false, length = 255)
    private String title;

    @Nationalized
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    private Long projectId;

    private Long taskId;

    private Long submissionId;

    @Column(length = 100)
    private String actionType;

    @Column(length = 100)
    private String documentId;

    @Nationalized
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String rejectReason;

    @Column(name = "company_profile_id", length = 100)
    private String companyProfileId;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isRead = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isDeleted = false;

    private LocalDateTime readAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
