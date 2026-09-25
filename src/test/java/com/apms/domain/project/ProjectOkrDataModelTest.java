package com.apms.domain.project;

import com.apms.common.enums.ProjectKeyResultType;
import com.apms.common.enums.ProjectType;
import com.apms.domain.project.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectOkrDataModelTest {

    @Test
    public void projectCanStoreObjective() {
        Project project = Project.builder()
                .projectName("Test Project")
                .projectType(ProjectType.RESEARCH_NEW_COMPANY)
                .targetCompanyName("Test Co")
                .objective("Test Objective")
                .plannedEndDate(LocalDate.now().plusDays(10))
                .build();

        assertThat(project.getObjective()).isEqualTo("Test Objective");
    }

    @Test
    public void legacyProjectCanHaveObjectiveNull() {
        Project project = Project.builder()
                .projectName("Legacy Project")
                .projectType(ProjectType.RESEARCH_NEW_COMPANY)
                .targetCompanyName("Legacy Co")
                // No objective set
                .plannedEndDate(LocalDate.now().plusDays(10))
                .build();

        assertThat(project.getObjective()).isNull();
    }

    @Test
    public void projectKeyResultCanBeCreated() {
        Project project = Project.builder()
                .id(1L)
                .projectName("OKR Project")
                .build();

        ProjectKeyResult kr1 = ProjectKeyResult.builder()
                .id(1L)
                .project(project)
                .type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION)
                .name("Basic Info")
                .weight(40)
                .build();

        ProjectKeyResult kr2 = ProjectKeyResult.builder()
                .id(2L)
                .project(project)
                .type(ProjectKeyResultType.FINANCIAL_INFORMATION)
                .name("Financial Info")
                .weight(60)
                .build();

        assertThat(kr1.getProject().getId()).isEqualTo(1L);
        assertThat(kr2.getWeight()).isEqualTo(60);
    }

    @Test
    public void projectTaskCanReferenceKeyResult() {
        Project project = Project.builder().id(1L).build();

        ProjectKeyResult kr = ProjectKeyResult.builder()
                .id(100L)
                .project(project)
                .type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION)
                .weight(100)
                .build();

        ProjectTask task = new ProjectTask();
        task.setProject(project);
        task.setTitle("Test task with KR");
        task.setTaskType(com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION);
        task.setKeyResult(kr);
        
        assertThat(task.getKeyResult()).isNotNull();
        assertThat(task.getKeyResult().getId()).isEqualTo(100L);
    }

    @Test
    public void legacyProjectTaskCanHaveKeyResultNull() {
        ProjectTask task = new ProjectTask();
        task.setTitle("Legacy task without KR");
        task.setTaskType(com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION);
        // No keyResult set
        
        assertThat(task.getKeyResult()).isNull();
    }
}

