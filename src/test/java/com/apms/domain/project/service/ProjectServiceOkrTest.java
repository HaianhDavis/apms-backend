package com.apms.domain.project.service;

import com.apms.common.enums.ProjectKeyResultType;
import com.apms.common.enums.ProjectType;
import com.apms.common.enums.RelationshipType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectKeyResult;
import com.apms.domain.project.dto.CreateProjectKeyResultRequest;
import com.apms.domain.project.dto.CreateProjectRequest;
import com.apms.domain.project.dto.ProjectResponse;
import com.apms.domain.project.repository.sql.ProjectKeyResultRepository;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProjectServiceOkrTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectKeyResultRepository projectKeyResultRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    @Mock private com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;
    @Mock private TaskGeneratorService taskGeneratorService;
    @Mock private com.apms.domain.profile.repository.mongo.CompanyProfileRepository companyProfileRepository;

    @InjectMocks
    private ProjectService projectService;

    private CreateProjectRequest request;
    private Account creator;

    @BeforeEach
    void setUp() {
        creator = new Account();
        creator.setId(1L);

        request = new CreateProjectRequest();
        request.setProjectName("Test Project");
        request.setProjectType(ProjectType.RESEARCH_NEW_COMPANY);
        request.setTargetCompanyName("Test Co");
        request.setTargetRelationshipType(RelationshipType.PARTNER_WITH);
        request.setPlannedEndDate(LocalDate.now().plusDays(10));
    }

    private void mockSave() {
        lenient().when(accountRepository.getReferenceById(1L)).thenReturn(creator);
        lenient().when(companyProfileRepository.save(any())).thenAnswer(i -> {
            com.apms.domain.profile.CompanyProfile cp = i.getArgument(0);
            if (cp.getCompanyId() == null) {
                cp.setCompanyId(java.util.UUID.randomUUID().toString());
            }
            return cp;
        });
        lenient().when(projectRepository.save(any(Project.class))).thenAnswer(i -> {
            Project p = i.getArgument(0);
            p.setId(100L);
            return p;
        });
        lenient().when(projectKeyResultRepository.findByProject_Id(anyLong())).thenReturn(List.of());
        lenient().when(projectTaskRepository.getProjectTaskStatsIn(any(), any(), any())).thenReturn(List.of());
        lenient().when(userProfileRepository.findByAccountId(any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void createProjectWithValidKeyResults() {
        mockSave();
        
        CreateProjectKeyResultRequest kr1 = new CreateProjectKeyResultRequest();
        kr1.setType(ProjectKeyResultType.BASIC_COMPANY_INFORMATION);
        kr1.setWeight(60);

        CreateProjectKeyResultRequest kr2 = new CreateProjectKeyResultRequest();
        kr2.setType(ProjectKeyResultType.FINANCIAL_INFORMATION);
        kr2.setWeight(40);

        request.setKeyResults(List.of(kr1, kr2));

        ProjectResponse response = projectService.createProject(request, 1L);

        assertThat(response).isNotNull();
        verify(projectKeyResultRepository).saveAll(anyList());
    }

    @Test
    void createProjectRejectsWeightTotalBelow100() {
        CreateProjectKeyResultRequest kr1 = new CreateProjectKeyResultRequest();
        kr1.setType(ProjectKeyResultType.BASIC_COMPANY_INFORMATION);
        kr1.setWeight(60);

        request.setKeyResults(List.of(kr1));

        assertThatThrownBy(() -> projectService.createProject(request, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("exactly 100");
    }

    @Test
    void createProjectRejectsWeightTotalAbove100() {
        CreateProjectKeyResultRequest kr1 = new CreateProjectKeyResultRequest();
        kr1.setType(ProjectKeyResultType.BASIC_COMPANY_INFORMATION);
        kr1.setWeight(110);

        request.setKeyResults(List.of(kr1));

        assertThatThrownBy(() -> projectService.createProject(request, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("exactly 100");
    }

    @Test
    void unsupportedRelationshipRejectsContractInformationKr() {
        CreateProjectKeyResultRequest kr = new CreateProjectKeyResultRequest();
        kr.setType(ProjectKeyResultType.CONTRACT_INFORMATION);
        kr.setWeight(100);

        request.setKeyResults(List.of(kr));
        request.setTargetRelationshipType(RelationshipType.COMPETITOR_OF); 

        assertThatThrownBy(() -> projectService.createProject(request, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("CONTRACT_INFORMATION");
    }

    @Test
    void createProjectRejectsZeroOrNegativeWeight() {
        CreateProjectKeyResultRequest kr1 = new CreateProjectKeyResultRequest();
        kr1.setType(ProjectKeyResultType.BASIC_COMPANY_INFORMATION);
        kr1.setWeight(100);

        CreateProjectKeyResultRequest kr2 = new CreateProjectKeyResultRequest();
        kr2.setType(ProjectKeyResultType.FINANCIAL_INFORMATION);
        kr2.setWeight(0);

        request.setKeyResults(List.of(kr1, kr2));

        assertThatThrownBy(() -> projectService.createProject(request, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("greater than 0"); 
                
        // Let's make it pass total but fail > 0 check
        kr1.setWeight(110);
        kr2.setWeight(-10);
        
        assertThatThrownBy(() -> projectService.createProject(request, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("greater than 0");
    }

    @Test
    void createProjectRejectsDuplicateKeyResultType() {
        CreateProjectKeyResultRequest kr1 = new CreateProjectKeyResultRequest();
        kr1.setType(ProjectKeyResultType.BASIC_COMPANY_INFORMATION);
        kr1.setWeight(50);

        CreateProjectKeyResultRequest kr2 = new CreateProjectKeyResultRequest();
        kr2.setType(ProjectKeyResultType.BASIC_COMPANY_INFORMATION);
        kr2.setWeight(50);

        request.setKeyResults(List.of(kr1, kr2));

        assertThatThrownBy(() -> projectService.createProject(request, 1L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void partnerProjectCanUseContractInformationKr() {
        mockSave();
        
        request.setTargetRelationshipType(RelationshipType.PARTNER_WITH);
        
        CreateProjectKeyResultRequest kr1 = new CreateProjectKeyResultRequest();
        kr1.setType(ProjectKeyResultType.CONTRACT_INFORMATION);
        kr1.setWeight(100);

        request.setKeyResults(List.of(kr1));

        ProjectResponse response = projectService.createProject(request, 1L);
        assertThat(response).isNotNull();
    }


    @Test
    void legacyProjectWithoutKeyResultsStillWorks() {
        mockSave();
        request.setKeyResults(null);
        
        ProjectResponse response = projectService.createProject(request, 1L);
        assertThat(response).isNotNull();
        verify(projectKeyResultRepository, never()).saveAll(anyList());
    }
}
