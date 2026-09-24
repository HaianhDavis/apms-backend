//package com.apms.domain.profile.service;
//
//import com.apms.common.enums.AuditAction;
//import com.apms.common.enums.MemberRole;
//import com.apms.common.enums.SystemRole;
//import com.apms.domain.audit.service.AuditLogService;
//import com.apms.domain.profile.CompanyProfile;
//import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
//import com.apms.domain.project.Project;
//import com.apms.domain.project.ProjectMember;
//import com.apms.domain.user.Account;
//import com.apms.domain.user.repository.sql.AccountRepository;
//import com.apms.security.UserDetailsImpl;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.security.access.AccessDeniedException;
//import org.springframework.security.core.authority.SimpleGrantedAuthority;
//
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.Optional;
//import java.util.Set;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyLong;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//public class ProfileServiceResponsibilityTest {
//
//    @Mock
//    private CompanyProfileRepository profileRepository;
//
//    @Mock
//    private AccountRepository accountRepository;
//
//    @Mock
//    private AuditLogService auditLogService;
//
//    @InjectMocks
//    private ProfileService profileService;
//
//    private CompanyProfile profile;
//    private UserDetailsImpl systemAdmin;
//    private UserDetailsImpl currentManager;
//
//    @BeforeEach
//    void setUp() {
//        profile = CompanyProfile.builder()
//                .id("profile1")
//                .companyId("profile1")
//                .responsibleManagerId(10L)
//                .build();
//
//        systemAdmin = new UserDetailsImpl(
//                1L, "admin@test.com", "pass", List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true
//        );
//
//        currentManager = new UserDetailsImpl(
//                10L, "manager@test.com", "pass", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")), true
//        );
//    }
//
//    @Test
//    void transferResponsibility_InvalidTarget_ThrowsException() {
//        Account target = Account.builder().id(20L).isActive(true).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF)).build();
//        when(profileRepository.findByCompanyId("profile1")).thenReturn(Optional.of(profile));
//        when(accountRepository.findById(20L)).thenReturn(Optional.of(target));
//
//        assertThrows(com.apms.common.exception.BusinessValidationException.class, () -> {
//            profileService.transferResponsibility("profile1", 20L, systemAdmin);
//        });
//    }
//
//    @Test
//    void transferResponsibility_LegacyProfile_OnlySystemAdminCanAssign() {
//        profile.setResponsibleManagerId(null);
//        Account target = Account.builder().id(20L).isActive(true).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER)).build();
//        when(profileRepository.findByCompanyId("profile1")).thenReturn(Optional.of(profile));
//        when(accountRepository.findById(20L)).thenReturn(Optional.of(target));
//
//        // Attempt by another manager
//        UserDetailsImpl otherManager = new UserDetailsImpl(
//                30L, "other@test.com", "pass", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")), true
//        );
//
//        assertThrows(AccessDeniedException.class, () -> {
//            profileService.transferResponsibility("profile1", 20L, otherManager);
//        });
//
//        // Attempt by SYSTEM_ADMIN
//        profileService.transferResponsibility("profile1", 20L, systemAdmin);
//
//        assertEquals(20L, profile.getResponsibleManagerId());
//        verify(profileRepository).save(profile);
//    }
//
//    @Test
//    void transferResponsibility_OldManagerLosesPermission() {
//        Account target = Account.builder().id(20L).isActive(true).roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER)).build();
//        when(profileRepository.findByCompanyId("profile1")).thenReturn(Optional.of(profile));
//        when(accountRepository.findById(20L)).thenReturn(Optional.of(target));
//
//        // Transfer responsibility to 20L
//        profileService.transferResponsibility("profile1", 20L, currentManager);
//
//        assertEquals(20L, profile.getResponsibleManagerId());
//        verify(auditLogService).log(eq(10L), eq(AuditAction.COMPANY_PROFILE_RESPONSIBILITY_TRANSFERRED), eq("CompanyProfile"), eq("profile1"), anyString());
//
//        // At this point, the profile's responsible manager is 20L.
//        // In reality, CompanyMonitoringService enforces this, but we can verify the ProfileService part works.
//    }
//}
