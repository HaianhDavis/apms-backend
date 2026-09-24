//package com.apms.domain.notification.service;
//
//import com.apms.common.enums.NotificationType;
//import com.apms.common.enums.SystemRole;
//import com.apms.domain.audit.service.AuditLogService;
//import com.apms.domain.notification.Notification;
//import com.apms.domain.notification.dto.NotificationResponse;
//import com.apms.domain.notification.repository.sql.FcmDeviceTokenRepository;
//import com.apms.domain.notification.repository.sql.NotificationRepository;
//import com.apms.domain.profile.CompanyProfile;
//import com.apms.domain.profile.assessment.CompanyRelationshipAssessment;
//import com.apms.domain.profile.assessment.RelationshipAssessmentStatus;
//import com.apms.domain.profile.assessment.repository.CompanyRelationshipAssessmentRepository;
//import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
//import com.apms.domain.user.Account;
//import com.apms.domain.user.repository.sql.AccountRepository;
//import com.google.firebase.messaging.FirebaseMessaging;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.beans.factory.ObjectProvider;
//
//import java.util.Collections;
//import java.util.List;
//import java.util.Optional;
//import java.util.Set;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//public class NotificationLifecycleServiceTest {
//
//    @Mock
//    private NotificationRepository notificationRepository;
//
//    @Mock
//    private FcmDeviceTokenRepository fcmDeviceTokenRepository;
//
//    @Mock
//    private AccountRepository accountRepository;
//
//    @Mock
//    private AuditLogService auditLogService;
//
//    @Mock
//    private ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;
//
//    @Mock
//    private CompanyProfileRepository companyProfileRepository;
//
//    @Mock
//    private CompanyRelationshipAssessmentRepository assessmentRepository;
//
//    private NotificationService notificationService;
//
//    private Account ownerAccount;
//    private Account managerAccount;
//
//    @BeforeEach
//    void setUp() {
//        notificationService = new NotificationService(
//                notificationRepository,
//                fcmDeviceTokenRepository,
//                accountRepository,
//                auditLogService,
//                firebaseMessagingProvider,
//                companyProfileRepository,
//                assessmentRepository
//        );
//
//        ownerAccount = Account.builder()
//                .id(100L)
//                .email("owner@corp.com")
//                .isActive(true)
//                .roles(Set.of(SystemRole.BUSINESS_OWNER))
//                .build();
//
//        managerAccount = Account.builder()
//                .id(200L)
//                .email("manager@corp.com")
//                .isActive(true)
//                .roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER))
//                .build();
//    }
//
//    @Test
//    @DisplayName("Manager completes assessment: Owner receives notification, Manager does not receive self-notification")
//    void testManagerCompletesAssessment_OwnerReceivesNotification_ManagerDoesNot() {
//        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
//                .id(1L)
//                .companyProfileId("COMP-123")
//                .versionNumber(1)
//                .managerAccountId(managerAccount.getId())
//                .managerTotalScore(85)
//                .managerRank("A")
//                .build();
//
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(List.of(ownerAccount));
//        when(accountRepository.findById(managerAccount.getId()))
//                .thenReturn(Optional.of(managerAccount));
//        when(notificationRepository.existsLifecycleNotification(eq(100L), eq("RELATIONSHIP_ASSESSMENT_INITIAL"), eq("1"), eq("COMP-123")))
//                .thenReturn(false);
//        when(notificationRepository.save(any(Notification.class)))
//                .thenAnswer(invocation -> invocation.getArgument(0));
//
//        notificationService.notifyRelationshipAssessmentCompleted(assessment, managerAccount.getId());
//
//        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
//        verify(notificationRepository, times(1)).save(captor.capture());
//
//        Notification saved = captor.getValue();
//        assertEquals(ownerAccount, saved.getRecipientAccount());
//        assertEquals(managerAccount, saved.getSenderAccount());
//        assertEquals("RELATIONSHIP_ASSESSMENT_INITIAL", saved.getActionType());
//        assertEquals(NotificationType.SYSTEM, saved.getType());
//        assertEquals("COMP-123", saved.getCompanyProfileId());
//        assertEquals("1", saved.getEntityId());
//        assertEquals("RELATIONSHIP_ASSESSMENT", saved.getEntityType());
//        assertEquals("Đánh giá mức độ thân thiết mới", saved.getTitle());
//        assertTrue(saved.getMessage().contains("V1 · 85/100 · Rank A"));
//        assertFalse(saved.getIsRead());
//    }
//
//    @Test
//    @DisplayName("Self-notification guard: If actor is the only owner, no notification is saved")
//    void testManagerCompletesAssessment_ActorIsOwner_NoSelfNotification() {
//        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
//                .id(1L)
//                .companyProfileId("COMP-123")
//                .versionNumber(1)
//                .managerAccountId(ownerAccount.getId())
//                .build();
//
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(List.of(ownerAccount));
//
//        // Actor ID is the owner account ID -> must be excluded
//        notificationService.notifyRelationshipAssessmentCompleted(assessment, ownerAccount.getId());
//
//        verify(notificationRepository, never()).save(any(Notification.class));
//    }
//
//    @Test
//    @DisplayName("Owner completes adjustment: Source Manager receives notification, Owner does not receive self-notification")
//    void testOwnerCompletesAdjustment_SourceManagerReceivesNotification_OwnerDoesNot() {
//        CompanyRelationshipAssessment sourceAssessment = CompanyRelationshipAssessment.builder()
//                .id(1L)
//                .companyProfileId("COMP-123")
//                .versionNumber(1)
//                .managerAccountId(managerAccount.getId())
//                .managerTotalScore(87)
//                .managerRank("B")
//                .build();
//
//        CompanyRelationshipAssessment ownerAdjustment = CompanyRelationshipAssessment.builder()
//                .id(2L)
//                .companyProfileId("COMP-123")
//                .versionNumber(2)
//                .sourceAssessmentId(1L)
//                .ownerAccountId(ownerAccount.getId())
//                .ownerFinalTotalScore(90)
//                .ownerFinalRank("A")
//                .build();
//
//        when(accountRepository.findById(managerAccount.getId()))
//                .thenReturn(Optional.of(managerAccount));
//        when(accountRepository.findById(ownerAccount.getId()))
//                .thenReturn(Optional.of(ownerAccount));
//        when(notificationRepository.existsLifecycleNotification(eq(200L), eq("RELATIONSHIP_ASSESSMENT_OWNER_ADJUSTED"), eq("2"), eq("COMP-123")))
//                .thenReturn(false);
//        when(notificationRepository.save(any(Notification.class)))
//                .thenAnswer(invocation -> invocation.getArgument(0));
//
//        notificationService.notifyRelationshipAssessmentOwnerAdjusted(ownerAdjustment, sourceAssessment, ownerAccount.getId());
//
//        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
//        verify(notificationRepository, times(1)).save(captor.capture());
//
//        Notification saved = captor.getValue();
//        assertEquals(managerAccount, saved.getRecipientAccount());
//        assertEquals(ownerAccount, saved.getSenderAccount());
//        assertEquals("RELATIONSHIP_ASSESSMENT_OWNER_ADJUSTED", saved.getActionType());
//        assertEquals(NotificationType.SYSTEM, saved.getType());
//        assertEquals("COMP-123", saved.getCompanyProfileId());
//        assertEquals("2", saved.getEntityId());
//        assertEquals("RELATIONSHIP_ASSESSMENT", saved.getEntityType());
//        assertEquals("Business Owner đã điều chỉnh đánh giá", saved.getTitle());
//        assertTrue(saved.getMessage().contains("87/100 · Rank B → 90/100 · Rank A"));
//        assertFalse(saved.getIsRead());
//    }
//
//    @Test
//    @DisplayName("Owner adjustment self-notification guard: If owner is also the source manager, skip notification")
//    void testOwnerCompletesAdjustment_OwnerIsSourceManager_Skip() {
//        CompanyRelationshipAssessment sourceAssessment = CompanyRelationshipAssessment.builder()
//                .id(1L)
//                .managerAccountId(ownerAccount.getId())
//                .build();
//
//        CompanyRelationshipAssessment ownerAdjustment = CompanyRelationshipAssessment.builder()
//                .id(2L)
//                .sourceAssessmentId(1L)
//                .ownerAccountId(ownerAccount.getId())
//                .build();
//
//        // Both are ownerAccount.getId()
//        notificationService.notifyRelationshipAssessmentOwnerAdjusted(ownerAdjustment, sourceAssessment, ownerAccount.getId());
//
//        verify(notificationRepository, never()).save(any(Notification.class));
//    }
//
//    @Test
//    @DisplayName("Deduplication: Retry completion does not create duplicate notification")
//    void testRetryCompletion_NoDuplicateNotification() {
//        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
//                .id(1L)
//                .companyProfileId("COMP-123")
//                .versionNumber(1)
//                .managerAccountId(managerAccount.getId())
//                .build();
//
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(List.of(ownerAccount));
//        // Notification already exists in database
//        when(notificationRepository.existsLifecycleNotification(eq(100L), eq("RELATIONSHIP_ASSESSMENT_INITIAL"), eq("1"), eq("COMP-123")))
//                .thenReturn(true);
//
//        notificationService.notifyRelationshipAssessmentCompleted(assessment, managerAccount.getId());
//
//        verify(notificationRepository, never()).save(any(Notification.class));
//    }
//
//    @Test
//    @DisplayName("Company profile updated: Owner notified once, actor excluded")
//    void testCompanyProfileUpdated_OwnerNotified_ActorExcluded() {
//        CompanyProfile profile = CompanyProfile.builder()
//                .id("PROFILE-001")
//                .companyId("COMP-001")
//                .identity(CompanyProfile.Identity.builder().tradeName("Acme Corp").build())
//                .build();
//
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(List.of(ownerAccount));
//        when(accountRepository.findById(managerAccount.getId()))
//                .thenReturn(Optional.of(managerAccount));
//        when(notificationRepository.existsLifecycleNotification(eq(100L), eq("COMPANY_PROFILE_UPDATED"), eq("V1.0"), eq("PROFILE-001")))
//                .thenReturn(false);
//        when(notificationRepository.save(any(Notification.class)))
//                .thenAnswer(invocation -> invocation.getArgument(0));
//
//        notificationService.notifyCompanyProfileUpdated(profile, "V1.0", managerAccount.getId());
//
//        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
//        verify(notificationRepository, times(1)).save(captor.capture());
//
//        Notification saved = captor.getValue();
//        assertEquals(ownerAccount, saved.getRecipientAccount());
//        assertEquals("COMPANY_PROFILE_UPDATED", saved.getActionType());
//        assertEquals(NotificationType.SYSTEM, saved.getType());
//        assertEquals("PROFILE-001", saved.getCompanyProfileId());
//        assertEquals("V1.0", saved.getEntityId());
//        assertEquals("COMPANY_PROFILE", saved.getEntityType());
//        assertTrue(saved.getMessage().contains("Acme Corp"));
//    }
//
//    @Test
//    @DisplayName("Profile update version dedup: V10 notified once, retry V10 no duplicate, V11 notified")
//    void testProfileV10NotifiedOnce_RetryV10NoDuplicate_V11Notified() {
//        CompanyProfile profile = CompanyProfile.builder()
//                .id("PROFILE-001")
//                .companyId("COMP-001")
//                .identity(CompanyProfile.Identity.builder().tradeName("Acme Corp").build())
//                .build();
//
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(List.of(ownerAccount));
//        when(accountRepository.findById(managerAccount.getId()))
//                .thenReturn(Optional.of(managerAccount));
//        when(notificationRepository.save(any(Notification.class)))
//                .thenAnswer(invocation -> invocation.getArgument(0));
//
//        // 1. First trigger with V10 -> not exists -> saves
//        when(notificationRepository.existsLifecycleNotification(eq(100L), eq("COMPANY_PROFILE_UPDATED"), eq("V10"), eq("PROFILE-001")))
//                .thenReturn(false);
//
//        notificationService.notifyCompanyProfileUpdated(profile, "V10", managerAccount.getId());
//        verify(notificationRepository, times(1)).save(any(Notification.class));
//
//        // 2. Retry trigger with V10 -> exists -> no save
//        when(notificationRepository.existsLifecycleNotification(eq(100L), eq("COMPANY_PROFILE_UPDATED"), eq("V10"), eq("PROFILE-001")))
//                .thenReturn(true);
//
//        notificationService.notifyCompanyProfileUpdated(profile, "V10", managerAccount.getId());
//        verify(notificationRepository, times(1)).save(any(Notification.class)); // still 1
//
//        // 3. New version V11 -> not exists -> saves
//        when(notificationRepository.existsLifecycleNotification(eq(100L), eq("COMPANY_PROFILE_UPDATED"), eq("V11"), eq("PROFILE-001")))
//                .thenReturn(false);
//
//        notificationService.notifyCompanyProfileUpdated(profile, "V11", managerAccount.getId());
//        verify(notificationRepository, times(2)).save(any(Notification.class)); // incremented to 2
//    }
//
//    @Test
//    @DisplayName("Null profile or no active owners causes graceful early return without error")
//    void testGracefulHandling_NoActiveOwnersOrNullEntity() {
//        notificationService.notifyCompanyProfileUpdated(null, "V1.0", 200L);
//        notificationService.notifyRelationshipAssessmentCompleted(null, 200L);
//        notificationService.notifyRelationshipAssessmentOwnerAdjusted(null, (Long) null, 100L);
//
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(Collections.emptyList());
//
//        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
//                .id(1L)
//                .companyProfileId("COMP-123")
//                .build();
//
//        notificationService.notifyRelationshipAssessmentCompleted(assessment, 200L);
//
//        verify(notificationRepository, never()).save(any(Notification.class));
//    }
//
//    @Test
//    @DisplayName("Vietnamese Unicode preservation: Full vowel set, tone marks, and copy formatting")
//    void testVietnameseUnicodePreservation_FullToneMarksAndFormatting() {
//        String testVowels = "ă â đ ê ô ơ ư Ă Â Đ Ê Ô Ơ Ư";
//        String testTones = "á à ả ã ạ ấ ầ ẩ ẫ ậ ế ề ể ễ ệ ố ồ ổ ỗ ộ ớ ờ ở ỡ ợ ứ ừ ử ữ ự";
//        String fullVietnameseSentence = "Đánh giá mức độ thân thiết của doanh nghiệp đã được điều chỉnh: " + testVowels + " | " + testTones;
//
//        CompanyProfile testProfile = CompanyProfile.builder()
//                .id("PROF-VIETNAM")
//                .companyId("COMP-VN")
//                .identity(CompanyProfile.Identity.builder().tradeName("Tập đoàn Vingroup").build())
//                .build();
//
//        when(companyProfileRepository.findById("PROF-VIETNAM")).thenReturn(Optional.of(testProfile));
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(List.of(ownerAccount));
//        when(accountRepository.findById(managerAccount.getId()))
//                .thenReturn(Optional.of(managerAccount));
//        when(notificationRepository.save(any(Notification.class)))
//                .thenAnswer(invocation -> invocation.getArgument(0));
//
//        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
//                .id(10L)
//                .companyProfileId("PROF-VIETNAM")
//                .versionNumber(5)
//                .managerAccountId(managerAccount.getId())
//                .managerTotalScore(87)
//                .managerRank("B")
//                .build();
//
//        notificationService.notifyRelationshipAssessmentCompleted(assessment, managerAccount.getId());
//
//        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
//        verify(notificationRepository, times(1)).save(captor.capture());
//
//        Notification saved = captor.getValue();
//        assertEquals("Đánh giá mức độ thân thiết mới", saved.getTitle());
//        assertEquals("Tập đoàn Vingroup · V5 · 87/100 · Rank B", saved.getMessage());
//
//        assertFalse(saved.getTitle().contains("?"), "Title must not contain question marks");
//        assertFalse(saved.getMessage().contains("?"), "Message must not contain question marks");
//        assertFalse(saved.getTitle().contains("\uFFFD"), "Title must not contain replacement characters");
//        assertFalse(saved.getMessage().contains("\uFFFD"), "Message must not contain replacement characters");
//        assertFalse(saved.getMessage().contains("PROF-VIETNAM"), "Message must not leak raw companyProfileId");
//    }
//
//    @Test
//    @DisplayName("Owner adjustment copy with company name resolution and arrow formatting")
//    void testOwnerAdjustment_WithResolvedCompanyName() {
//        CompanyProfile testProfile = CompanyProfile.builder()
//                .id("PROF-VINGROUP")
//                .companyId("COMP-VIN")
//                .identity(CompanyProfile.Identity.builder().tradeName("Vingroup").build())
//                .build();
//
//        when(companyProfileRepository.findById("PROF-VINGROUP")).thenReturn(Optional.of(testProfile));
//        when(accountRepository.findById(managerAccount.getId()))
//                .thenReturn(Optional.of(managerAccount));
//        when(accountRepository.findById(ownerAccount.getId()))
//                .thenReturn(Optional.of(ownerAccount));
//        when(notificationRepository.save(any(Notification.class)))
//                .thenAnswer(invocation -> invocation.getArgument(0));
//
//        CompanyRelationshipAssessment source = CompanyRelationshipAssessment.builder()
//                .id(100L)
//                .companyProfileId("PROF-VINGROUP")
//                .versionNumber(5)
//                .managerAccountId(managerAccount.getId())
//                .managerTotalScore(87)
//                .managerRank("B")
//                .build();
//
//        CompanyRelationshipAssessment adjustment = CompanyRelationshipAssessment.builder()
//                .id(101L)
//                .companyProfileId("PROF-VINGROUP")
//                .versionNumber(6)
//                .sourceAssessmentId(100L)
//                .ownerAccountId(ownerAccount.getId())
//                .ownerFinalTotalScore(90)
//                .ownerFinalRank("A")
//                .build();
//
//        notificationService.notifyRelationshipAssessmentOwnerAdjusted(adjustment, source, ownerAccount.getId());
//
//        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
//        verify(notificationRepository, times(1)).save(captor.capture());
//
//        Notification saved = captor.getValue();
//        assertEquals("Business Owner đã điều chỉnh đánh giá", saved.getTitle());
//        assertEquals("Vingroup · 87/100 · Rank B → 90/100 · Rank A", saved.getMessage());
//        assertFalse(saved.getMessage().contains("PROF-VINGROUP"));
//    }
//
//    @Test
//    @DisplayName("REST API response serialization: UTF-8 Unicode preserved without mojibake or escape")
//    void testRestApiResponseSerialization_PreservesVietnameseUnicode() throws Exception {
//        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
//        mapper.findAndRegisterModules();
//
//        NotificationResponse response = NotificationResponse.builder()
//                .id(1L)
//                .title("Đánh giá mức độ thân thiết đã hoàn thành")
//                .message("Vingroup · V5 · 87/100 · Rank B")
//                .actionType("RELATIONSHIP_ASSESSMENT_COMPLETED")
//                .type(NotificationType.SYSTEM)
//                .build();
//
//        String json = mapper.writeValueAsString(response);
//        assertTrue(json.contains("Đánh giá mức độ thân thiết đã hoàn thành"));
//        assertTrue(json.contains("Vingroup · V5 · 87/100 · Rank B"));
//
//        NotificationResponse readBack = mapper.readValue(json, NotificationResponse.class);
//        assertEquals(response.getTitle(), readBack.getTitle());
//        assertEquals(response.getMessage(), readBack.getMessage());
//    }
//
//    @Test
//    @DisplayName("Manager completes reassessment: Owner receives RELATIONSHIP_ASSESSMENT_UPDATED when previous finalized exists")
//    void testManagerCompletesReassessment_OwnerReceivesUpdatedNotification() {
//        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
//                .id(2L)
//                .ownerCompanyProfileId("OWNER-001")
//                .companyProfileId("COMP-123")
//                .versionNumber(2)
//                .managerAccountId(managerAccount.getId())
//                .managerTotalScore(90)
//                .managerRank("A")
//                .build();
//
//        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusAndIdNot(
//                eq("OWNER-001"), eq("COMP-123"), eq(RelationshipAssessmentStatus.FINALIZED), eq(2L)))
//                .thenReturn(true);
//        when(accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER))
//                .thenReturn(List.of(ownerAccount));
//        when(accountRepository.findById(managerAccount.getId()))
//                .thenReturn(Optional.of(managerAccount));
//        when(notificationRepository.existsLifecycleNotification(eq(100L), eq("RELATIONSHIP_ASSESSMENT_UPDATED"), eq("2"), eq("COMP-123")))
//                .thenReturn(false);
//        when(notificationRepository.save(any(Notification.class)))
//                .thenAnswer(invocation -> invocation.getArgument(0));
//
//        notificationService.notifyRelationshipAssessmentCompleted(assessment, managerAccount.getId());
//
//        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
//        verify(notificationRepository, times(1)).save(captor.capture());
//
//        Notification saved = captor.getValue();
//        assertEquals("RELATIONSHIP_ASSESSMENT_UPDATED", saved.getActionType());
//        assertEquals("Cập nhật đánh giá mức độ thân thiết", saved.getTitle());
//    }
//}
