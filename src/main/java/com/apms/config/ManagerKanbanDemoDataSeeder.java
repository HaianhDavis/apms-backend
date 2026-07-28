package com.apms.config;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.MemberRole;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectMember;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Supplies a compact, repeatable project workspace for demonstrating the Manager Kanban board.
 * Existing records are never changed, including candidates the user has moved on the board.
 */
@Slf4j
@Component
@Profile("dev")
@Order(4)
@RequiredArgsConstructor
public class ManagerKanbanDemoDataSeeder implements CommandLineRunner {

    private static final String PROJECT_NAME = "Demo - Partner Discovery Kanban";
    private static final String APPROVED_CANDIDATE_ID = "demo-kanban-approved-candidate";
    private static final String APPROVED_PROFILE_ID = "demo-kanban-approved-profile";

    private final AccountRepository accountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final CompanyProfileRepository profileRepository;

    @Override
    public void run(String... args) {
        try {
            Account manager = accountRepository.findByEmail("manager@apms.com").orElse(null);
            Account staff = accountRepository.findByEmail("staff@apms.com").orElse(null);
            if (manager == null || staff == null) {
                log.warn("ManagerKanbanDemoDataSeeder skipped because demo manager or staff is unavailable.");
                return;
            }

            Project project = projectRepository.findFirstByProjectNameOrderByIdAsc(PROJECT_NAME)
                    .orElseGet(() -> projectRepository.save(Project.builder()
                            .projectName(PROJECT_NAME)
                            .projectType(ProjectType.RESEARCH_MULTIPLE_COMPANIES)
                            .targetCompanyName("Vietnam digital-services partners")
                            .targetRelationshipType(RelationshipType.POTENTIAL_PARTNER_OF)
                            .description("Demo workspace showing each company-candidate stage on the Manager Kanban board.")
                            .status(ProjectStatus.ACTIVE)
                            .createdByAccount(manager)
                            .build()));

            ensureMember(project, manager, MemberRole.MANAGER);
            ensureMember(project, staff, MemberRole.STAFF);
            seedTasks(project, manager, staff);
            seedCandidates(project, manager);
            seedApprovedProfile(project);

            // Add new seeding for all other projects
            List<Project> allProjects = projectRepository.findAll();
            for (Project p : allProjects) {
                if (p.getProjectName().equals(PROJECT_NAME)) continue;
                
                ensureMember(p, manager, MemberRole.MANAGER);
                ensureMember(p, staff, MemberRole.STAFF);
                seedTasksForProject(p, manager, staff);
                seedCandidatesForProject(p, manager);
            }

            log.info("Manager Kanban demo ready: projectId={}, projectName={}", project.getId(), PROJECT_NAME);
        } catch (Exception exception) {
            log.warn("ManagerKanbanDemoDataSeeder failed. The application will continue without Kanban demo data.", exception);
        }
    }

    private void ensureMember(Project project, Account account, MemberRole role) {
        if (!projectMemberRepository.existsByProject_IdAndAccount_Id(project.getId(), account.getId())) {
            projectMemberRepository.save(ProjectMember.builder()
                    .project(project)
                    .account(account)
                    .memberRole(role)
                    .build());
        }
    }

    private void seedTasks(Project project, Account manager, Account staff) {
        ensureTask(project, manager, staff, "Collect public company material", "Build the source pack for companies in the new queue.", TaskStatus.TODO, TaskPriority.HIGH, TaskType.DOCUMENT_COLLECTION, 2);
        ensureTask(project, manager, staff, "Normalise candidate company data", "Resolve duplicate names and missing industry details before review.", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, TaskType.COMPANY_DATA_PREPARATION, 4);
        ensureTask(project, manager, staff, "Prepare partner-fit recommendation", "Summarise strategic fit and risks for the manager review.", TaskStatus.IN_REVIEW, TaskPriority.HIGH, TaskType.ROLE_EVALUATION, 1);
        ensureTask(project, manager, staff, "Archive verified partner profile", "Completed example task for the project work queue.", TaskStatus.DONE, TaskPriority.LOW, TaskType.GENERAL_TASK, -1);
    }

    private void seedTasksForProject(Project p, Account manager, Account staff) {
        if (p.getProjectName().contains("Fintech")) {
            ensureTask(p, manager, staff, "Thu thập danh sách Fintech startup", "Thu thập thông tin cơ bản về các Fintech nổi bật.", TaskStatus.TODO, TaskPriority.HIGH, TaskType.DOCUMENT_COLLECTION, 3);
            ensureTask(p, manager, staff, "Phân tích thị phần ví điện tử", "Đánh giá thị phần của MoMo, ZaloPay, ViettelPay.", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, TaskType.COMPANY_DATA_PREPARATION, 5);
            ensureTask(p, manager, staff, "Báo cáo tuân thủ pháp lý", "Kiểm tra giấy phép hoạt động trung gian thanh toán.", TaskStatus.IN_REVIEW, TaskPriority.HIGH, TaskType.ROLE_EVALUATION, 1);
        } else if (p.getProjectName().contains("FPT")) {
            ensureTask(p, manager, staff, "Xác minh doanh thu Outsourcing", "Kiểm tra báo cáo tài chính mảng xuất khẩu phần mềm.", TaskStatus.TODO, TaskPriority.MEDIUM, TaskType.DOCUMENT_COLLECTION, 2);
            ensureTask(p, manager, staff, "Đánh giá hạ tầng FPT AI", "Khảo sát năng lực xử lý dữ liệu và dịch vụ Cloud của FPT.", TaskStatus.IN_PROGRESS, TaskPriority.HIGH, TaskType.COMPANY_DATA_PREPARATION, 4);
        } else if (p.getProjectName().contains("Tư vấn")) {
            ensureTask(p, manager, staff, "Khảo sát văn phòng Big4 tại VN", "Lập danh sách địa điểm và nhân sự chủ chốt.", TaskStatus.TODO, TaskPriority.LOW, TaskType.DOCUMENT_COLLECTION, 6);
            ensureTask(p, manager, staff, "So sánh biểu phí dịch vụ tư vấn", "Thu thập thông tin giá từ các dự án đấu thầu trước.", TaskStatus.DONE, TaskPriority.MEDIUM, TaskType.ROLE_EVALUATION, -2);
        } else {
            ensureTask(p, manager, staff, "Thu thập hồ sơ dự án sơ bộ", "Tài liệu cơ bản phục vụ nghiên cứu.", TaskStatus.TODO, TaskPriority.LOW, TaskType.GENERAL_TASK, 7);
            ensureTask(p, manager, staff, "Phân tích đối thủ cạnh tranh chính", "Phác thảo sơ đồ đối thủ và thị phần tương đối.", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, TaskType.COMPANY_DATA_PREPARATION, 3);
        }
    }

    private void seedCandidatesForProject(Project p, Account manager) {
        if (p.getProjectName().contains("Fintech")) {
            saveDemoCandidate(p, "cand-momo", "MoMo E-Wallet", "MoMo", "FinTech", CandidateStatus.PENDING_REVIEW, 0.92, 1, manager);
            saveDemoCandidate(p, "cand-zalopay", "ZaloPay", "ZaloPay", "FinTech", CandidateStatus.DRAFT, 0.85, 2, manager);
            saveDemoCandidate(p, "cand-vnpay", "VNPAY", "VNPAY", "FinTech", CandidateStatus.CORRECTED, 0.88, 3, manager);
        } else if (p.getProjectName().contains("FPT")) {
            saveDemoCandidate(p, "cand-fptsoft", "FPT Software", "FPT Soft", "Công nghệ thông tin", CandidateStatus.APPROVED, 0.95, 1, manager);
            saveDemoCandidate(p, "cand-fptcloud", "FPT Smart Cloud", "FPT Cloud", "Cloud Computing", CandidateStatus.PENDING_REVIEW, 0.91, 2, manager);
        } else if (p.getProjectName().contains("Tư vấn")) {
            saveDemoCandidate(p, "cand-kpmg", "KPMG Vietnam", "KPMG", "Kiểm toán", CandidateStatus.APPROVED, 0.96, 1, manager);
            saveDemoCandidate(p, "cand-pwc", "PwC Vietnam", "PwC", "Tư vấn", CandidateStatus.DRAFT, 0.82, 2, manager);
        } else {
            saveDemoCandidate(p, "cand-generic-1", "Công ty Cổ phần Công nghệ mới", "NewTech", "Phần mềm", CandidateStatus.DRAFT, 0.70, 1, manager);
        }
    }

    private void saveDemoCandidate(Project p, String id, String legalName, String tradeName, String industry,
                                   CandidateStatus status, double confidence, int order, Account manager) {
        if (candidateRepository.existsById(id)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        candidateRepository.save(CompanyCandidate.builder()
                .id(id)
                .projectId(String.valueOf(p.getId()))
                .candidateOrder(order)
                .revisionNumber(1)
                .status(status)
                .suggestedRelationshipType(RelationshipType.POTENTIAL_PARTNER_OF)
                .relationshipConfidenceScore(confidence)
                .identity(CompanyCandidate.Identity.builder().legalName(legalName).tradeName(tradeName).build())
                .business(CompanyCandidate.Business.builder().industries(List.of(industry)).businessModel("B2B").build())
                .lifecycle(CompanyCandidate.Lifecycle.builder().status(status).build())
                .review(status == CandidateStatus.REJECTED
                        ? CompanyCandidate.Review.builder().rejectionReason("Outside selection criteria.").reviewedBy(String.valueOf(manager.getId())).reviewedAt(now.minusDays(1)).build()
                        : null)
                .metadata(CompanyCandidate.Metadata.builder()
                        .createdBy("manager-kanban-demo-seeder")
                        .createdAt(now.minusDays(order))
                        .lastModifiedBy("manager-kanban-demo-seeder")
                        .updatedAt(now)
                        .build())
                .build());
    }

    private void ensureTask(Project project, Account manager, Account staff, String title, String description,
                             TaskStatus status, TaskPriority priority, TaskType type, int dueInDays) {
        if (projectTaskRepository.existsByProject_IdAndTitle(project.getId(), title)) {
            return;
        }
        LocalDateTime dueDate = LocalDateTime.now().plusDays(dueInDays);
        projectTaskRepository.save(ProjectTask.builder()
                .project(project)
                .assignedToAccount(staff)
                .createdByAccount(manager)
                .title(title)
                .description(description)
                .status(status)
                .priority(priority)
                .taskType(type)
                .dueDate(dueDate)
                .completedAt(status == TaskStatus.DONE ? LocalDateTime.now().minusDays(1) : null)
                .build());
    }

    private void seedCandidates(Project project, Account manager) {
        List<DemoCandidate> candidates = List.of(
                new DemoCandidate("demo-kanban-green-grid", "GreenGrid Energy", "GreenGrid", "Clean energy technology", CandidateStatus.DRAFT, 0.81, 1),
                new DemoCandidate("demo-kanban-orbit-commerce", "Orbit Commerce", "Orbit", "Retail technology", CandidateStatus.DRAFT, 0.72, 2),
                new DemoCandidate("demo-kanban-nimbus-logistics", "Nimbus Logistics", "Nimbus", "Logistics technology", CandidateStatus.CORRECTED, 0.76, 3),
                new DemoCandidate("demo-kanban-novapay", "NovaPay Vietnam", "NovaPay", "Financial technology", CandidateStatus.PENDING_REVIEW, 0.89, 4),
                new DemoCandidate("demo-kanban-kite-health", "Kite Health Systems", "Kite Health", "Healthcare technology", CandidateStatus.PENDING_REVIEW, 0.78, 5),
                new DemoCandidate(APPROVED_CANDIDATE_ID, "Sao Mai Cloud", "Sao Mai", "Cloud infrastructure", CandidateStatus.APPROVED, 0.94, 6),
                new DemoCandidate("demo-kanban-legacy-link", "Legacy Link Services", "Legacy Link", "IT services", CandidateStatus.REJECTED, 0.34, 7)
        );

        for (DemoCandidate item : candidates) {
            if (candidateRepository.existsById(item.id())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            candidateRepository.save(CompanyCandidate.builder()
                    .id(item.id())
                    .projectId(String.valueOf(project.getId()))
                    .candidateOrder(item.order())
                    .revisionNumber(item.status() == CandidateStatus.CORRECTED ? 2 : 1)
                    .status(item.status())
                    .suggestedRelationshipType(RelationshipType.POTENTIAL_PARTNER_OF)
                    .relationshipConfidenceScore(item.confidence())
                    .identity(CompanyCandidate.Identity.builder().legalName(item.legalName()).tradeName(item.tradeName()).build())
                    .business(CompanyCandidate.Business.builder().industries(List.of(item.industry())).businessModel("B2B").build())
                    .lifecycle(CompanyCandidate.Lifecycle.builder()
                            .status(item.status())
                            .convertedCompanyProfileId(item.status() == CandidateStatus.APPROVED ? APPROVED_PROFILE_ID : null)
                            .build())
                    .review(item.status() == CandidateStatus.REJECTED
                            ? CompanyCandidate.Review.builder().rejectionReason("Outside the current partner-selection criteria.").reviewedBy(String.valueOf(manager.getId())).reviewedAt(now.minusDays(1)).build()
                            : null)
                    .metadata(CompanyCandidate.Metadata.builder()
                            .createdBy("manager-kanban-demo-seeder")
                            .createdAt(now.minusDays(item.order()))
                            .lastModifiedBy("manager-kanban-demo-seeder")
                            .updatedAt(now)
                            .build())
                    .build());
        }
    }

    private void seedApprovedProfile(Project project) {
        if (profileRepository.findByCompanyId(APPROVED_PROFILE_ID).isPresent()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        profileRepository.save(CompanyProfile.builder()
                .id(APPROVED_PROFILE_ID)
                .companyId(APPROVED_PROFILE_ID)
                .identity(CompanyProfile.Identity.builder().legalName("Sao Mai Cloud").tradeName("Sao Mai").build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Cloud infrastructure"))
                        .businessModel("B2B")
                        .markets(List.of("Vietnam", "Southeast Asia"))
                        .build())
                .contact(CompanyProfile.Contact.builder().website("https://demo.apms.local/sao-mai-cloud").build())
                .reviewStatus("VERIFIED")
                .tags(List.of("demo", "potential-partner", "approved"))
                .sourceRefs(CompanyProfile.SourceRefs.builder()
                        .projectIds(Set.of(String.valueOf(project.getId())))
                        .candidateIds(Set.of(APPROVED_CANDIDATE_ID))
                        .build())
                .metadata(CompanyProfile.Metadata.builder()
                        .createdBy("manager-kanban-demo-seeder")
                        .createdAt(now.minusDays(6))
                        .lastModifiedBy("manager-kanban-demo-seeder")
                        .updatedAt(now)
                        .build())
                .build());
    }

    private record DemoCandidate(String id, String legalName, String tradeName, String industry,
                                 CandidateStatus status, double confidence, int order) {
    }
}
