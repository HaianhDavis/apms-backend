package com.apms.domain.profile.closeness;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.dto.RelationshipClosenessResponse;
import com.apms.domain.profile.closeness.dto.UpdateRelationshipClosenessRequest;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyRelationshipClosenessServiceTest {

    @Mock
    private CompanyRelationshipClosenessRepository closenessRepository;

    @Mock
    private CompanyProfileRepository companyProfileRepository;

    @Mock
    private OwnerOrganizationService ownerOrganizationService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PlatformTransactionManager transactionManager;
    
    @Mock
    private TransactionStatus transactionStatus;

    private CompanyRelationshipClosenessService service;

    private UserDetailsImpl ownerUser;
    private UserDetailsImpl managerUser;
    private UserDetailsImpl staffUser;
    private UserDetailsImpl adminUser;

    private final String ownerCompanyId = "owner-id";
    private final String targetCompanyId = "target-id";

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new CompanyRelationshipClosenessService(closenessRepository, companyProfileRepository, ownerOrganizationService, projectRepository, auditLogService, transactionManager);
        
        ownerUser = new UserDetailsImpl(1L, "owner@test.com", "pass", Set.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")), true);
        managerUser = new UserDetailsImpl(2L, "manager@test.com", "pass", Set.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")), true);
        staffUser = new UserDetailsImpl(3L, "staff@test.com", "pass", Set.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), true);
        adminUser = new UserDetailsImpl(4L, "admin@test.com", "pass", Set.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true);
    }

    private void mockTargetValid() {
        CompanyProfile target = new CompanyProfile();
        target.setId(targetCompanyId);
        target.setIsHidden(false);
        target.setIsDeleted(false);
        when(companyProfileRepository.findById(targetCompanyId)).thenReturn(Optional.of(target));
        when(ownerOrganizationService.isOwnerCompany(targetCompanyId)).thenReturn(false);
        lenient().when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(ownerCompanyId);
    }

    @Test
    void ownerGetPutDeleteAllowed() {
        mockTargetValid();
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.empty());

        // GET
        RelationshipClosenessResponse getRes = service.getCloseness(targetCompanyId, ownerUser);
        assertEquals("UNRATED", getRes.getLabel());

        // PUT
        UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
        req.setStars(4);
        CompanyRelationshipCloseness savedEntity = new CompanyRelationshipCloseness();
        savedEntity.setStars(4);
        when(closenessRepository.saveAndFlush(any())).thenReturn(savedEntity);
        
        RelationshipClosenessResponse putRes = service.updateCloseness(targetCompanyId, req, ownerUser);
        assertEquals("CLOSE", putRes.getLabel());

        // DELETE
        CompanyRelationshipCloseness existing = new CompanyRelationshipCloseness();
        existing.setId(10L);
        existing.setStars(4);
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.of(existing));
        service.deleteCloseness(targetCompanyId, ownerUser);
        verify(closenessRepository).delete(existing);
    }

    @Test
    void managerGetPutWithValidScopeAllowed_DeleteDenied() {
        mockTargetValid();
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(targetCompanyId, managerUser.getId(), List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE, ProjectStatus.COMPLETED)))
                .thenReturn(true);
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(targetCompanyId, managerUser.getId(), List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE)))
                .thenReturn(true);

        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.empty());

        // GET
        service.getCloseness(targetCompanyId, managerUser);

        // PUT
        UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
        req.setStars(3);
        CompanyRelationshipCloseness savedEntity = new CompanyRelationshipCloseness();
        savedEntity.setStars(3);
        when(closenessRepository.saveAndFlush(any())).thenReturn(savedEntity);
        service.updateCloseness(targetCompanyId, req, managerUser);

        // DELETE (Denied)
        assertThrows(AccessDeniedException.class, () -> service.deleteCloseness(targetCompanyId, managerUser));
    }

    @Test
    void managerCannotUpdateAfterOwnerFinalized() {
        mockTargetValid();
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(targetCompanyId, managerUser.getId(), List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE)))
                .thenReturn(true);

        CompanyRelationshipCloseness existing = new CompanyRelationshipCloseness();
        existing.setId(11L);
        existing.setOwnerCompanyProfileId(ownerCompanyId);
        existing.setTargetCompanyProfileId(targetCompanyId);
        existing.setStars(5);
        existing.setRatedByAccountId(ownerUser.getId());
        existing.setRatedByRole("BUSINESS_OWNER");
        existing.setOwnerStars(5);
        existing.setOwnerRatedByAccountId(ownerUser.getId());
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.of(existing));

        UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
        req.setStars(3);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () -> service.updateCloseness(targetCompanyId, req, managerUser));
        assertTrue(ex.getMessage().contains("Business Owner has finalized"));
        verify(closenessRepository, never()).saveAndFlush(any());
    }

    @Test
    void ownerFinalRatingLocksManagerButKeepsManagerDraft() {
        mockTargetValid();
        CompanyRelationshipCloseness existing = new CompanyRelationshipCloseness();
        existing.setId(12L);
        existing.setOwnerCompanyProfileId(ownerCompanyId);
        existing.setTargetCompanyProfileId(targetCompanyId);
        existing.setStars(3);
        existing.setNote("Manager view");
        existing.setRatedByAccountId(managerUser.getId());
        existing.setRatedByRole("BUSINESS_DEVELOPMENT_MANAGER");
        existing.setManagerStars(3);
        existing.setManagerNote("Manager view");
        existing.setManagerRatedByAccountId(managerUser.getId());
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.of(existing));
        when(closenessRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
        req.setStars(5);
        req.setNote("Owner final");

        RelationshipClosenessResponse response = service.updateCloseness(targetCompanyId, req, ownerUser);

        assertTrue(response.isOwnerFinalized());
        assertEquals(5, response.getStars());
        assertEquals(5, response.getOwnerStars());
        assertEquals(3, response.getManagerStars());
        assertEquals("BUSINESS_OWNER", response.getRatedByRole());
    }

    @Test
    void managerDeniedOutsideScope() {
        mockTargetValid();
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(eq(targetCompanyId), eq(managerUser.getId()), anyList()))
                .thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.getCloseness(targetCompanyId, managerUser));
        assertThrows(AccessDeniedException.class, () -> service.updateCloseness(targetCompanyId, new UpdateRelationshipClosenessRequest(), managerUser));
    }
    
    @Test
    void managerReadCompletedButWriteDenied() {
        mockTargetValid();
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(targetCompanyId, managerUser.getId(), List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE, ProjectStatus.COMPLETED)))
                .thenReturn(true);
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(targetCompanyId, managerUser.getId(), List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE)))
                .thenReturn(false);

        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.empty());

        // GET allowed because COMPLETED grants read scope
        service.getCloseness(targetCompanyId, managerUser);

        // PUT denied because COMPLETED doesn't grant write scope
        UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
        assertThrows(AccessDeniedException.class, () -> service.updateCloseness(targetCompanyId, req, managerUser));
    }

    @Test
    void staffGetWithScopeAllowed_PutDeleteDenied() {
        mockTargetValid();
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(eq(targetCompanyId), eq(staffUser.getId()), anyList()))
                .thenReturn(true);
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.empty());

        // GET allowed
        service.getCloseness(targetCompanyId, staffUser);

        // PUT denied
        assertThrows(AccessDeniedException.class, () -> service.updateCloseness(targetCompanyId, new UpdateRelationshipClosenessRequest(), staffUser));

        // DELETE denied
        assertThrows(AccessDeniedException.class, () -> service.deleteCloseness(targetCompanyId, staffUser));
    }

    @Test
    void systemAdminDenied() {
        mockTargetValid();

        assertThrows(AccessDeniedException.class, () -> service.getCloseness(targetCompanyId, adminUser));
        assertThrows(AccessDeniedException.class, () -> service.updateCloseness(targetCompanyId, new UpdateRelationshipClosenessRequest(), adminUser));
        assertThrows(AccessDeniedException.class, () -> service.deleteCloseness(targetCompanyId, adminUser));
    }

    @Test
    void ownCompanyRejected() {
        when(ownerOrganizationService.isOwnerCompany(ownerCompanyId)).thenReturn(true);
        assertThrows(BusinessValidationException.class, () -> service.getCloseness(ownerCompanyId, ownerUser));
    }

    @Test
    void targetHiddenOrDeletedRejected() {
        CompanyProfile hidden = new CompanyProfile();
        hidden.setId(targetCompanyId);
        hidden.setIsHidden(true);
        when(ownerOrganizationService.isOwnerCompany(targetCompanyId)).thenReturn(false);
        when(companyProfileRepository.findById(targetCompanyId)).thenReturn(Optional.of(hidden));

        assertThrows(BusinessValidationException.class, () -> service.getCloseness(targetCompanyId, ownerUser));

        hidden.setIsHidden(false);
        hidden.setIsDeleted(true);
        assertThrows(BusinessValidationException.class, () -> service.getCloseness(targetCompanyId, ownerUser));
    }

    @Test
    void correctLabelMappingFor1To5() {
        assertEquals("CONTACT_ONLY", RelationshipClosenessLevel.fromStars(1).name());
        assertEquals("WEAK", RelationshipClosenessLevel.fromStars(2).name());
        assertEquals("ESTABLISHED", RelationshipClosenessLevel.fromStars(3).name());
        assertEquals("CLOSE", RelationshipClosenessLevel.fromStars(4).name());
        assertEquals("STRATEGIC", RelationshipClosenessLevel.fromStars(5).name());
        
        assertThrows(BusinessValidationException.class, () -> RelationshipClosenessLevel.fromStars(0));
        assertThrows(BusinessValidationException.class, () -> RelationshipClosenessLevel.fromStars(6));
    }

    @Test
    void noteAbove1000Rejected() {
        mockTargetValid();
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.empty());

        UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
        req.setStars(3);
        req.setNote("a".repeat(1001));

        assertThrows(BusinessValidationException.class, () -> service.updateCloseness(targetCompanyId, req, ownerUser));
    }

    @Test
    void unratedGetReturns200Model() {
        mockTargetValid();
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.empty());

        RelationshipClosenessResponse res = service.getCloseness(targetCompanyId, ownerUser);
        assertNull(res.getStars());
        assertEquals("UNRATED", res.getLabel());
        assertNull(res.getNote());
    }

    @Test
    void createAndUpdateExistingRating() {
        mockTargetValid();
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.empty());

        UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
        req.setStars(3);
        req.setNote("Good partner");

        CompanyRelationshipCloseness newEntity = new CompanyRelationshipCloseness();
        newEntity.setId(1L);
        newEntity.setStars(3);
        when(closenessRepository.saveAndFlush(any())).thenReturn(newEntity);

        service.updateCloseness(targetCompanyId, req, ownerUser);
        
        verify(auditLogService).log(eq(ownerUser.getId()), eq(AuditAction.RELATIONSHIP_CLOSENESS_CREATED), eq("CompanyRelationshipCloseness"), any(), contains("New Stars: 3"));
        verify(auditLogService, never()).log(any(), any(), any(), any(), contains("Good partner"));

        // UPDATE
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerCompanyId, targetCompanyId))
                .thenReturn(Optional.of(newEntity));

        req.setStars(5);
        service.updateCloseness(targetCompanyId, req, ownerUser);
        verify(auditLogService).log(eq(ownerUser.getId()), eq(AuditAction.RELATIONSHIP_CLOSENESS_UPDATED), eq("CompanyRelationshipCloseness"), any(), contains("New Stars: 5"));
    }
}
