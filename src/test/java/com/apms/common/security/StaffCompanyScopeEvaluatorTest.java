package com.apms.common.security;

import com.apms.common.enums.ProjectStatus;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffCompanyScopeEvaluatorTest {

    private static final String OWNER_COMPANY = "OWNER-COMPANY";
    private static final Long STAFF_ACCOUNT = 100L;

    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private RoleEvaluationDraftRepository roleEvaluationDraftRepository;
    @Mock
    private ImportJobRepository importJobRepository;
    @Mock
    private CompanyProfileUpdateProposalRepository proposalRepository;
    @Mock
    private AiExtractionCacheRepository extractionCacheRepository;

    private StaffCompanyScopeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new StaffCompanyScopeEvaluator(
                ownerOrganizationService,
                companyProfileRepository,
                projectRepository,
                roleEvaluationDraftRepository,
                importJobRepository,
                proposalRepository,
                extractionCacheRepository);

        lenient().when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(OWNER_COMPANY);
        lenient().when(ownerOrganizationService.isOwnerCompany(anyString()))
                .thenAnswer(inv -> OWNER_COMPANY.equals(inv.getArgument(0)));
        lenient().when(companyProfileRepository.findByCompanyId(anyString()))
                .thenAnswer(inv -> Optional.of(CompanyProfile.builder().companyId(inv.getArgument(0)).build()));
        lenient().when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                        anyString(), anyLong(), anyList()))
                .thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        UserDetailsImpl user = new UserDetailsImpl(STAFF_ACCOUNT, "staff@apms.vn", "x", authorities, true);
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateStaff() {
        authenticateAs("ROLE_BUSINESS_DEVELOPMENT_STAFF");
    }

    private void authenticateNonStaff() {
        authenticateAs("ROLE_SYSTEM_ADMIN");
    }

    private void noAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // TEST 1: Staff (không có assignment) vẫn xem được Owner Company → PASS
    @Test
    void test1_staffWithoutAssignment_canAccessOwnerCompany() {
        authenticateStaff();
        assertThat(evaluator.canAccessCompany(OWNER_COMPANY)).isTrue();
    }

    // TEST 2: Staff (không có assignment) xem Company khác → DENY
    @Test
    void test2_staffWithoutAssignment_cannotAccessOtherCompany() {
        authenticateStaff();
        assertThat(evaluator.canAccessCompany("B")).isFalse();
    }

    // TEST 3: Staff được assign Project 1 → target Company B → PASS
    @Test
    void test3_staffAssignedToProject_canAccessTargetCompany() {
        when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(
                        "B", STAFF_ACCOUNT, List.of(ProjectStatus.values())))
                .thenReturn(true);
        authenticateStaff();
        assertThat(evaluator.canAccessCompany("B")).isTrue();
    }

    // TEST 4: Staff project 1 → B, nhưng Company C (project 2) → DENY
    @Test
    void test4_staffAssignedToProject_cannotAccessCompanyOfOtherProject() {
        authenticateStaff();
        assertThat(evaluator.canAccessCompany("C")).isFalse();
    }

    // TEST 5: Staff tự sửa companyId C (không thuộc project được assign) → DENY
    @Test
    void test5_staffTampersCompanyIdDetail_denied() {
        authenticateStaff();
        assertThat(evaluator.canAccessCompany("C")).isFalse();
        assertThat(evaluator.canAccessCompanyId("C", currentUser())).isFalse();
    }

    // TEST 6: Staff list → chỉ thấy Owner Company + Company của project được assign
    @Test
    void test6_staffList_onlySeesOwnerAndAssignedProjectCompanies() {
        when(projectRepository.findTargetCompanyProfileIdsByMemberAccountId(STAFF_ACCOUNT))
                .thenReturn(List.of("B"));
        authenticateStaff();
        Set<String> allowed = evaluator.allowedCompanyIds();
        assertThat(allowed).containsExactlyInAnyOrder(OWNER_COMPANY, "B");
        assertThat(allowed).doesNotContain("C", "D");
    }

    // TEST 7: Staff được assign project 1, project 2 → project 2 DENY
    @Test
    void test7_staffAssignedToProjectOne_cannotAccessProjectTwo() {
        when(projectRepository.existsByIdAndMembersAccountId(1L, STAFF_ACCOUNT)).thenReturn(true);
        authenticateStaff();
        assertThat(evaluator.canAccessProject(1L)).isTrue();
        assertThat(evaluator.canAccessProject(2L)).isFalse();
    }

    // TEST 8: Staff thay đổi projectId → DENY
    @Test
    void test8_staffTampersProjectId_denied() {
        authenticateStaff();
        assertThat(evaluator.canAccessProject(2L)).isFalse();
    }

    // TEST 9: Staff truy cập tài nguyên partner/competitor theo companyId C → DENY
    @Test
    void test9_staffAccessPartnerCompetitorCompanyOutsideScope_denied() {
        authenticateStaff();
        assertThat(evaluator.canAccessCompany("C")).isFalse();
        assertThat(evaluator.canAccessCompanyId("C", currentUser())).isFalse();
    }

    // TEST 10: SYSTEM_ADMIN không bị giới hạn
    @Test
    void test10_adminIsNotRestricted() {
        authenticateAs("ROLE_SYSTEM_ADMIN");
        assertThat(evaluator.canAccessCompany("ANY")).isTrue();
        assertThat(evaluator.canAccessProject(99L)).isTrue();
        assertThat(evaluator.allowedCompanyIds()).isNull();
    }

    // TEST 11: BUSINESS_OWNER không bị giới hạn
    @Test
    void test11_ownerIsNotRestricted() {
        authenticateAs("ROLE_BUSINESS_OWNER");
        assertThat(evaluator.canAccessCompany("ANY")).isTrue();
        assertThat(evaluator.canAccessProject(99L)).isTrue();
        assertThat(evaluator.allowedCompanyIds()).isNull();
    }

    // TEST 12: BUSINESS_DEVELOPMENT_MANAGER không bị giới hạn
    @Test
    void test12_managerIsNotRestricted() {
        authenticateAs("ROLE_BUSINESS_DEVELOPMENT_MANAGER");
        assertThat(evaluator.canAccessCompany("ANY")).isTrue();
        assertThat(evaluator.canAccessProject(99L)).isTrue();
        assertThat(evaluator.allowedCompanyIds()).isNull();
    }

    @Test
    void unauthenticated_alwaysDenied() {
        noAuthentication();
        assertThat(evaluator.canAccessCompany(OWNER_COMPANY)).isFalse();
        assertThat(evaluator.canAccessProject(1L)).isFalse();
        assertThat(evaluator.allowedCompanyIds()).isEmpty();
    }

    // Derived-project guards: evaluation/import job/proposal/extraction of a foreign project → DENY
    @Test
    void staff_cannotAccessEvaluationOfForeignProject() {
        when(roleEvaluationDraftRepository.findById("EVAL-1"))
                .thenReturn(Optional.of(RoleEvaluationDraft.builder().projectId(9L).build()));
        when(projectRepository.existsByIdAndMembersAccountId(9L, STAFF_ACCOUNT)).thenReturn(false);
        authenticateStaff();
        assertThat(evaluator.canAccessEvaluation("EVAL-1")).isFalse();
    }

    @Test
    void staff_canAccessEvaluationOfAssignedProject() {
        when(roleEvaluationDraftRepository.findById("EVAL-1"))
                .thenReturn(Optional.of(RoleEvaluationDraft.builder().projectId(1L).build()));
        when(projectRepository.existsByIdAndMembersAccountId(1L, STAFF_ACCOUNT)).thenReturn(true);
        authenticateStaff();
        assertThat(evaluator.canAccessEvaluation("EVAL-1")).isTrue();
    }

    @Test
    void staff_cannotAccessImportJobOfForeignProject() {
        when(importJobRepository.findById(5L))
                .thenReturn(Optional.of(ImportJob.builder().id(5L).project(Project.builder().id(9L).build()).build()));
        when(projectRepository.existsByIdAndMembersAccountId(9L, STAFF_ACCOUNT)).thenReturn(false);
        authenticateStaff();
        assertThat(evaluator.canAccessImportJob(5L)).isFalse();
    }

    @Test
    void staff_cannotAccessProposalOfForeignProject() {
        when(proposalRepository.findById("PROP-1"))
                .thenReturn(Optional.of(CompanyProfileUpdateProposal.builder().projectId(9L).build()));
        when(projectRepository.existsByIdAndMembersAccountId(9L, STAFF_ACCOUNT)).thenReturn(false);
        authenticateStaff();
        assertThat(evaluator.canAccessProposal("PROP-1")).isFalse();
    }

    @Test
    void staff_cannotAccessExtractionOfForeignImportJob() {
        when(extractionCacheRepository.findById("EX-1"))
                .thenReturn(Optional.of(AiExtractionCache.builder().importJobId(5L).build()));
        when(importJobRepository.findById(5L))
                .thenReturn(Optional.of(ImportJob.builder().id(5L).project(Project.builder().id(9L).build()).build()));
        when(projectRepository.existsByIdAndMembersAccountId(9L, STAFF_ACCOUNT)).thenReturn(false);
        authenticateStaff();
        assertThat(evaluator.canAccessExtraction("EX-1")).isFalse();
    }

    private UserDetailsImpl currentUser() {
        return (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
