//package com.apms.domain.project.fieldapproval;
//
//import com.apms.ApmsIntegrationTestBase;
//import com.apms.domain.candidate.CompanyCandidate;
//import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
//import com.apms.domain.project.Project;
//import com.apms.domain.project.ProjectTask;
//import com.apms.domain.project.ProjectTaskSubmission;
//import com.apms.domain.project.dto.FieldReviewDecisionItem;
//import com.apms.domain.project.dto.FieldReviewRequest;
//import com.apms.domain.project.repository.sql.ProjectRepository;
//import com.apms.domain.project.repository.sql.ProjectTaskRepository;
//import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
//import com.apms.domain.user.Account;
//import com.apms.domain.user.repository.sql.AccountRepository;
//import com.apms.security.UserDetailsImpl;
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.dao.OptimisticLockingFailureException;
//import org.springframework.http.MediaType;
//import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
//import org.springframework.security.core.authority.SimpleGrantedAuthority;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.test.web.servlet.MockMvc;
//import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
//
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
//import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@AutoConfigureMockMvc
//public class OptimisticLockingIntegrationTest extends ApmsIntegrationTestBase {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @Autowired
//    private CompanyCandidateRepository candidateRepository;
//
//    @Autowired
//    private ProjectRepository projectRepository;
//
//    @Autowired
//    private ProjectTaskRepository taskRepository;
//
//    @Autowired
//    private ProjectTaskSubmissionRepository submissionRepository;
//
//    @Autowired
//    private AccountRepository accountRepository;
//
//    private Account manager;
//    private Project project;
//    private ProjectTask task;
//    private ProjectTaskSubmission submission;
//    private CompanyCandidate candidate;
//
//    private ObjectMapper objectMapper = new ObjectMapper();
//
//    @BeforeEach
//    void setUp() {
//        manager = new Account();
//        manager.setEmail("mgr-opt-" + java.util.UUID.randomUUID().toString() + "@example.com");
//        manager.setPasswordHash("pwd");
//        manager = accountRepository.save(manager);
//
//        project = new Project();
//        project.setProjectName("Opt Lock Project");
//        project.setTargetCompanyName("Test Co Opt");
//        project.setProjectType(com.apms.common.enums.ProjectType.RESEARCH_NEW_COMPANY);
//        project.setStatus(com.apms.common.enums.ProjectStatus.ACTIVE);
//        project.setCreatedByAccount(manager);
//        project = projectRepository.save(project);
//
//        // Ensure manager is a member
//        project.getMembers().add(new com.apms.domain.project.ProjectMember(null, project, manager, com.apms.common.enums.MemberRole.MANAGER, java.time.LocalDateTime.now()));
//        projectRepository.save(project);
//
//        task = new ProjectTask();
//        task.setProject(project);
//        task.setTaskType(com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION);
//        task.setAssignedToAccount(manager);
//        task.setCreatedByAccount(manager);
//        task.setStatus(com.apms.common.enums.TaskStatus.IN_PROGRESS);
//        task.setTitle("Prepare Data");
//        task = taskRepository.save(task);
//
//        candidate = new CompanyCandidate();
//        candidate.setProjectId(String.valueOf(project.getId()));
//        candidate.setRevisionNumber(1);
//        candidate.setStatus(com.apms.common.enums.CandidateStatus.PENDING_REVIEW);
//        // Mongo handles @Version, so we don't set it explicitly
//        candidate = candidateRepository.save(candidate);
//
//        submission = new ProjectTaskSubmission();
//        submission.setProject(project);
//        submission.setProjectTask(task);
//        submission.setTargetEntityType("CompanyCandidate");
//        submission.setSubmissionType(com.apms.common.enums.SubmissionType.COMPANY_CANDIDATE);
//        submission.setTargetEntityId(candidate.getId());
//        submission.setSubmittedRevisionNumber(1);
//        submission.setStatus(com.apms.common.enums.SubmissionStatus.IN_REVIEW);
//        submission.setSubmittedAt(java.time.LocalDateTime.now());
//        submission.setSubmittedByAccount(manager);
//        submission = submissionRepository.save(submission);
//    }
//
//    @AfterEach
//    void tearDown() {
//        SecurityContextHolder.clearContext();
//    }
//
//    private UserDetailsImpl managerUser() {
//        return new UserDetailsImpl(
//                manager.getId(), "mgr", "pwd",
//                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")), true
//        );
//    }
//
//    @Test
//    void testExpectedVersionValidation() throws Exception {
//        // Refresh candidate to get the real version (e.g. 0)
//        candidate = candidateRepository.findById(candidate.getId()).orElseThrow();
//        Long actualVersion = candidate.getDocumentVersion();
//        assertNotNull(actualVersion);
//
//        FieldReviewRequest req = new FieldReviewRequest();
//        req.setExpectedRevisionNumber(1);
//        req.setExpectedDocumentVersion(actualVersion + 99L); // Deliberately wrong version
//
//        FieldReviewDecisionItem item = new FieldReviewDecisionItem();
//        item.setFieldPath("identity.legalName");
//        item.setDecision(com.apms.common.enums.FieldApprovalStatus.APPROVED);
//        req.setDecisions(List.of(item));
//
//        // Expect 400 Bad Request due to BusinessValidationException mapped to 400
//        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/tasks/" + task.getId() + "/submissions/" + submission.getId() + "/field-reviews")
//                        .with(user(managerUser())).with(csrf())
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(req)))
//                .andExpect(status().isBadRequest());
//
//        // Ensure no data was modified
//        CompanyCandidate unchanged = candidateRepository.findById(candidate.getId()).orElseThrow();
//        assertTrue(unchanged.getFieldApprovals() == null || unchanged.getFieldApprovals().isEmpty());
//    }
//
//    @Test
//    void testRealMongoVersionConflict() {
//        // Load the same document into two separate instances
//        CompanyCandidate instance1 = candidateRepository.findById(candidate.getId()).orElseThrow();
//        CompanyCandidate instance2 = candidateRepository.findById(candidate.getId()).orElseThrow();
//
//        assertEquals(instance1.getDocumentVersion(), instance2.getDocumentVersion());
//
//        // Save the first instance
//        instance1.setRelationshipConfidenceScore(90.0);
//        candidateRepository.save(instance1);
//
//        // Save the second stale instance
//        instance2.setRelationshipConfidenceScore(80.0);
//        assertThrows(OptimisticLockingFailureException.class, () -> {
//            candidateRepository.save(instance2);
//        });
//
//        // Verify the first saved decision remains intact
//        CompanyCandidate finalCandidate = candidateRepository.findById(candidate.getId()).orElseThrow();
//        assertEquals(90.0, finalCandidate.getRelationshipConfidenceScore());
//    }
//}
