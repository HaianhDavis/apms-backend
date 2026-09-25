package com.apms.domain.project;

import com.apms.common.enums.ProjectType;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.document.ImportJob;
import com.apms.domain.profile.closeness.CompanyRelationshipCloseness;
import com.apms.domain.user.UserProfile;
import org.hibernate.annotations.Nationalized;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectUnicodePersistenceTest {

    @Test
    @DisplayName("Project entity supports Vietnamese diacritics in all text fields")
    void projectEntityHoldsVietnameseDiacritics() {
        String projectName = "Dự án nghiên cứu đối tác chiến lược 2025";
        String targetCompanyName = "Công ty Cổ phần Công nghệ & Viễn thông Ánh Dương";
        String description = "Mục tiêu nghiên cứu đánh giá toàn diện năng lực tài chính và quan hệ đối tác";
        String objective = "Xác định mức độ gắn kết và khả năng mở rộng hợp tác kinh doanh";
        String closeReason = "Dự án đã hoàn thành tất cả các mục tiêu đề ra";

        Project project = Project.builder()
                .projectName(projectName)
                .projectType(ProjectType.RESEARCH_NEW_COMPANY)
                .targetCompanyName(targetCompanyName)
                .description(description)
                .objective(objective)
                .closeReason(closeReason)
                .plannedEndDate(LocalDate.now().plusDays(30))
                .build();

        assertThat(project.getProjectName()).isEqualTo(projectName);
        assertThat(project.getTargetCompanyName()).isEqualTo(targetCompanyName);
        assertThat(project.getDescription()).isEqualTo(description);
        assertThat(project.getObjective()).isEqualTo(objective);
        assertThat(project.getCloseReason()).isEqualTo(closeReason);
    }

    @Test
    @DisplayName("Project entity fields have @Nationalized annotation for SQL Server NVARCHAR mapping")
    void projectEntityFieldsHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(Project.class, "projectName");
        assertFieldIsNationalized(Project.class, "targetCompanyName");
        assertFieldIsNationalized(Project.class, "description");
        assertFieldIsNationalized(Project.class, "objective");
        assertFieldIsNationalized(Project.class, "closeReason");
    }

    @Test
    @DisplayName("ProjectTask entity fields have @Nationalized annotation")
    void projectTaskEntityFieldsHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(ProjectTask.class, "title");
        assertFieldIsNationalized(ProjectTask.class, "description");
    }

    @Test
    @DisplayName("ProjectKeyResult entity fields have @Nationalized annotation")
    void projectKeyResultEntityFieldsHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(ProjectKeyResult.class, "name");
        assertFieldIsNationalized(ProjectKeyResult.class, "description");
    }

    @Test
    @DisplayName("ProjectTaskSubmission entity fields have @Nationalized annotation")
    void projectTaskSubmissionEntityFieldsHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(ProjectTaskSubmission.class, "note");
        assertFieldIsNationalized(ProjectTaskSubmission.class, "reviewComment");
        assertFieldIsNationalized(ProjectTaskSubmission.class, "targetItemIds");
    }

    @Test
    @DisplayName("UserProfile entity text fields have @Nationalized annotation")
    void userProfileEntityFieldsHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(UserProfile.class, "firstName");
        assertFieldIsNationalized(UserProfile.class, "lastName");
        assertFieldIsNationalized(UserProfile.class, "department");
        assertFieldIsNationalized(UserProfile.class, "position");
        assertFieldIsNationalized(UserProfile.class, "address");
        assertFieldIsNationalized(UserProfile.class, "bio");
    }

    @Test
    @DisplayName("ImportJob entity fileName has @Nationalized annotation")
    void importJobEntityFieldsHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(ImportJob.class, "fileName");
        assertFieldIsNationalized(ImportJob.class, "errorMessage");
    }

    @Test
    @DisplayName("PartnerContract entities have @Nationalized annotation on business text")
    void partnerContractEntitiesHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(PartnerContract.class, "contractTitle");
        assertFieldIsNationalized(PartnerContract.class, "contractType");
        assertFieldIsNationalized(PartnerContractVersion.class, "contractTitle");
        assertFieldIsNationalized(PartnerContractVersion.class, "contractType");
        assertFieldIsNationalized(PartnerContractClauseVersion.class, "clauseTitle");
        assertFieldIsNationalized(PartnerContractClauseVersion.class, "targetValue");
        assertFieldIsNationalized(PartnerContractClauseVersion.class, "penaltyDescription");
        assertFieldIsNationalized(PartnerContractClauseVersion.class, "evidenceReference");
        assertFieldIsNationalized(PartnerContractClauseVersion.class, "sourceExcerpt");
    }

    @Test
    @DisplayName("CompanyRelationshipCloseness note fields have @Nationalized annotation")
    void companyRelationshipClosenessNotesHaveNationalizedAnnotation() throws NoSuchFieldException {
        assertFieldIsNationalized(CompanyRelationshipCloseness.class, "note");
        assertFieldIsNationalized(CompanyRelationshipCloseness.class, "managerNote");
        assertFieldIsNationalized(CompanyRelationshipCloseness.class, "ownerNote");
    }

    private void assertFieldIsNationalized(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Field field = clazz.getDeclaredField(fieldName);
        assertThat(field.isAnnotationPresent(Nationalized.class))
                .as("Field '%s' in class '%s' must be annotated with @Nationalized", fieldName, clazz.getSimpleName())
                .isTrue();
    }
}
