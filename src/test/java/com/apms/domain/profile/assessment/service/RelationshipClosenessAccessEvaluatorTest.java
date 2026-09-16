package com.apms.domain.profile.assessment.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipClosenessAccessEvaluatorTest {

    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private Neo4jClient neo4jClient;

    private RelationshipClosenessAccessEvaluator evaluator;

    private UserDetailsImpl managerUser;
    private UserDetailsImpl ownerUser;
    private UserDetailsImpl staffUser;
    private UserDetailsImpl adminUser;

    @BeforeEach
    void setUp() {
        RelationshipClosenessAccessEvaluator rawEvaluator = new RelationshipClosenessAccessEvaluator(
                companyProfileRepository,
                projectRepository,
                ownerOrganizationService,
                neo4jClient
        );
        evaluator = spy(rawEvaluator);

        managerUser = new UserDetailsImpl(
                10L, "manager@apms.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_MANAGER.name())),
                true
        );

        ownerUser = new UserDetailsImpl(
                1L, "owner@apms.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_OWNER.name())),
                true
        );

        staffUser = new UserDetailsImpl(
                20L, "staff@apms.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_STAFF.name())),
                true
        );

        adminUser = new UserDetailsImpl(
                2L, "admin@apms.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.SYSTEM_ADMIN.name())),
                true
        );
    }

    private CompanyProfile createProfile(String id, String companyId, Long responsibleManagerId) {
        return CompanyProfile.builder()
                .id(id)
                .companyId(companyId)
                .responsibleManagerId(responsibleManagerId)
                .isHidden(false)
                .isDeleted(false)
                .build();
    }

    @Test
    @DisplayName("Eligible types: PARTNER, CUSTOMER, SUPPLIER and their _OF/_WITH variants")
    void testIsEligibleRelationshipType() {
        assertTrue(evaluator.isEligibleRelationshipType("PARTNER"));
        assertTrue(evaluator.isEligibleRelationshipType("PARTNER_WITH"));
        assertTrue(evaluator.isEligibleRelationshipType("CUSTOMER"));
        assertTrue(evaluator.isEligibleRelationshipType("CUSTOMER_OF"));
        assertTrue(evaluator.isEligibleRelationshipType("SUPPLIER"));
        assertTrue(evaluator.isEligibleRelationshipType("SUPPLIER_OF"));

        assertFalse(evaluator.isEligibleRelationshipType("COMPETITOR"));
        assertFalse(evaluator.isEligibleRelationshipType("COMPETITOR_OF"));
        assertFalse(evaluator.isEligibleRelationshipType("POTENTIAL_PARTNER"));
        assertFalse(evaluator.isEligibleRelationshipType("POTENTIAL_PARTNER_OF"));
        assertFalse(evaluator.isEligibleRelationshipType(null));
        assertFalse(evaluator.isEligibleRelationshipType(""));
        assertFalse(evaluator.isEligibleRelationshipType("UNKNOWN"));
    }

    @Test
    @DisplayName("Manager + Supplier in project scope -> canAccess is true")
    void testManager_Supplier_InProjectScope_CanAccess() {
        CompanyProfile supplier = createProfile("sup-1", "uuid-sup-1", null);
        doReturn("SUPPLIER").when(evaluator).resolveRelationshipType("uuid-sup-1");
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                eq("sup-1"), eq(10L), anyList())).thenReturn(true);

        assertTrue(evaluator.canAccess(supplier, managerUser));
    }

    @Test
    @DisplayName("Manager + Supplier with responsibleManagerId assigned -> canAccess is true")
    void testManager_Supplier_ResponsibleManager_CanAccess() {
        CompanyProfile supplier = createProfile("sup-2", "uuid-sup-2", 10L);
        doReturn("SUPPLIER_OF").when(evaluator).resolveRelationshipType("uuid-sup-2");

        assertTrue(evaluator.canAccess(supplier, managerUser));
    }

    @Test
    @DisplayName("Manager + Supplier out of project scope (CTX Holdings scenario) -> canAccess is false")
    void testManager_Supplier_OutOfScope_CannotAccess() {
        // CTX Holdings: SUPPLIER, responsibleManagerId = null, no project managed by 10L
        CompanyProfile ctxHoldings = createProfile("6a31a0000000000000000037", "6a31a0000000000000000037", null);
        doReturn("SUPPLIER").when(evaluator).resolveRelationshipType("6a31a0000000000000000037");
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                eq("6a31a0000000000000000037"), eq(10L), anyList())).thenReturn(false);

        assertFalse(evaluator.canAccess(ctxHoldings, managerUser));
    }

    @Test
    @DisplayName("Manager + Customer in project scope -> canAccess is true")
    void testManager_Customer_InProjectScope_CanAccess() {
        CompanyProfile customer = createProfile("cust-1", "uuid-cust-1", null);
        doReturn("CUSTOMER").when(evaluator).resolveRelationshipType("uuid-cust-1");
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                eq("cust-1"), eq(10L), anyList())).thenReturn(true);

        assertTrue(evaluator.canAccess(customer, managerUser));
    }

    @Test
    @DisplayName("Manager + Competitor in project scope -> canAccess is FALSE (ineligible type)")
    void testManager_Competitor_InProjectScope_CannotAccess() {
        CompanyProfile competitor = createProfile("comp-1", "uuid-comp-1", 10L);
        doReturn("COMPETITOR").when(evaluator).resolveRelationshipType("uuid-comp-1");

        assertFalse(evaluator.canAccess(competitor, managerUser));
    }

    @Test
    @DisplayName("Owner + Supplier/Customer/Partner -> canAccess is TRUE regardless of project scope")
    void testOwner_CanAccess_EligibleCompaniesSystemWide() {
        CompanyProfile ctxHoldings = createProfile("ctx-1", "uuid-ctx-1", null);
        CompanyProfile customer = createProfile("cust-1", "uuid-cust-1", null);
        CompanyProfile partner = createProfile("part-1", "uuid-part-1", null);

        doReturn("SUPPLIER").when(evaluator).resolveRelationshipType("uuid-ctx-1");
        doReturn("CUSTOMER").when(evaluator).resolveRelationshipType("uuid-cust-1");
        doReturn("PARTNER").when(evaluator).resolveRelationshipType("uuid-part-1");

        assertTrue(evaluator.canAccess(ctxHoldings, ownerUser));
        assertTrue(evaluator.canAccess(customer, ownerUser));
        assertTrue(evaluator.canAccess(partner, ownerUser));

        // Owner cannot access Competitor
        CompanyProfile competitor = createProfile("comp-1", "uuid-comp-1", null);
        doReturn("COMPETITOR").when(evaluator).resolveRelationshipType("uuid-comp-1");
        assertFalse(evaluator.canAccess(competitor, ownerUser));
    }

    @Test
    @DisplayName("Staff + Supplier in project scope -> canAccess is TRUE; out of scope -> FALSE")
    void testStaff_ProjectScopeRequired() {
        CompanyProfile supplier = createProfile("sup-1", "uuid-sup-1", null);
        doReturn("SUPPLIER").when(evaluator).resolveRelationshipType("uuid-sup-1");

        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                eq("sup-1"), eq(20L), anyList())).thenReturn(true);
        assertTrue(evaluator.canAccess(supplier, staffUser));

        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                eq("sup-1"), eq(20L), anyList())).thenReturn(false);
        assertFalse(evaluator.canAccess(supplier, staffUser));
    }

    @Test
    @DisplayName("Admin -> canAccess is always false")
    void testAdmin_CannotAccess() {
        CompanyProfile partner = createProfile("part-1", "uuid-part-1", null);
        doReturn("PARTNER").when(evaluator).resolveRelationshipType("uuid-part-1");

        assertFalse(evaluator.canAccess(partner, adminUser));
    }

    @Test
    @DisplayName("Owner company profile itself -> canAccess is false")
    void testOwnerCompanyItself_CannotAccess() {
        CompanyProfile ownerCompany = createProfile("owner-1", "uuid-owner-1", null);
        when(ownerOrganizationService.isOwnerCompany("uuid-owner-1")).thenReturn(true);

        assertFalse(evaluator.canAccess(ownerCompany, ownerUser));
    }

    @Test
    @DisplayName("Hidden or deleted profile -> canAccess is false")
    void testHiddenOrDeleted_CannotAccess() {
        CompanyProfile hidden = CompanyProfile.builder()
                .id("hid-1")
                .companyId("uuid-hid-1")
                .isHidden(true)
                .build();
        assertFalse(evaluator.canAccess(hidden, ownerUser));

        CompanyProfile deleted = CompanyProfile.builder()
                .id("del-1")
                .companyId("uuid-del-1")
                .isDeleted(true)
                .build();
        assertFalse(evaluator.canAccess(deleted, ownerUser));
    }

    @Test
    @DisplayName("validateAssessmentAccess throws 403 AccessDeniedException for out-of-scope Manager")
    void testValidateAssessmentAccess_OutOfScopeManager_Throws403() {
        CompanyProfile profile = createProfile("target-1", "uuid-1", null);
        when(companyProfileRepository.findById("target-1")).thenReturn(Optional.of(profile));
        doReturn("SUPPLIER").when(evaluator).resolveRelationshipType("uuid-1");
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                eq("target-1"), eq(10L), anyList())).thenReturn(false);

        assertThrows(AccessDeniedException.class, () ->
                evaluator.validateAssessmentAccess("target-1", managerUser, false));
    }

    @Test
    @DisplayName("validateAssessmentAccess throws BusinessValidationException for Competitor")
    void testValidateAssessmentAccess_Competitor_ThrowsBusinessValidationException() {
        CompanyProfile profile = createProfile("comp-1", "uuid-comp-1", null);
        when(companyProfileRepository.findById("comp-1")).thenReturn(Optional.of(profile));
        doReturn("COMPETITOR").when(evaluator).resolveRelationshipType("uuid-comp-1");

        assertThrows(BusinessValidationException.class, () ->
                evaluator.validateAssessmentAccess("comp-1", ownerUser, false));
    }
}
