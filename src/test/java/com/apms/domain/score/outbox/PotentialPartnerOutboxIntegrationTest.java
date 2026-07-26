package com.apms.domain.score.outbox;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.draft.CriterionSnapshot;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEventProcessorDelegator;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEventProcessorStrategy;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayload;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayloadHasher;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.outbox.SqlIdempotencyService;
import com.apms.domain.score.outbox.processor.PotentialPartnerEvaluationApprovedEventProcessor;
import com.apms.domain.score.repository.mongo.RoleEvaluationOutboxEventRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.score.service.CanonicalScoreSnapshotService;
import com.apms.domain.score.service.RoleEvaluationMongoContainerHolder;
import com.apms.domain.score.service.RoleEvaluationSqlServerContainerHolder;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PotentialPartnerOutboxIntegrationTest.MinimalConfig.class)
public class PotentialPartnerOutboxIntegrationTest {

    @Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {
            "com.apms.domain.project.repository.sql",
            "com.apms.domain.user.repository.sql",
            "com.apms.domain.audit.repository.sql",
            "com.apms.domain.score.repository.sql"
    })
    @EnableMongoRepositories(basePackages = "com.apms.domain.score.repository.mongo")
    public static class MinimalConfig {

        @Bean
        public DataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getJdbcUrl());
            dataSource.setUsername(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getUsername());
            dataSource.setPassword(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getPassword());
            dataSource.setDriverClassName(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getDriverClassName());
            dataSource.setMaximumPoolSize(5);

            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new FileSystemResource("src/test/resources/sql/role-evaluation-transition-schema.sql"));
            populator.addScript(new FileSystemResource("docs/sql-migrations/V1__Create_Processed_Outbox_Events.sql"));
            populator.execute(dataSource);

            return dataSource;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
            em.setDataSource(dataSource);
            em.setPackagesToScan("com.apms.domain.project", "com.apms.domain.user", "com.apms.domain.audit", "com.apms.domain.score");
            HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            vendorAdapter.setGenerateDdl(true); // Ensure entities are created
            em.setJpaVendorAdapter(vendorAdapter);
            
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            em.setJpaProperties(properties);
            
            return em;
        }

        @Bean
        public PlatformTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean entityManagerFactory) {
            JpaTransactionManager transactionManager = new JpaTransactionManager();
            transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
            return transactionManager;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public MongoTemplate mongoTemplate() throws Exception {
            com.mongodb.client.MongoClient mongoClient = com.mongodb.client.MongoClients.create(RoleEvaluationMongoContainerHolder.MONGO_CONTAINER.getReplicaSetUrl());
            return new MongoTemplate(mongoClient, "apms_test");
        }

        @Bean
        public RoleEvaluationOutboxEventProcessorDelegator processorDelegator(
                SqlIdempotencyService idempotencyService) {
            return new RoleEvaluationOutboxEventProcessorDelegator(idempotencyService);
        }

        @Bean
        public SqlIdempotencyService sqlIdempotencyService(JdbcTemplate jdbcTemplate) {
            return new SqlIdempotencyService(jdbcTemplate);
        }

        @Bean
        public RoleScoringEngine roleScoringEngine(
                RoleScoreRuleSetRepository ruleSetRepository,
                com.apms.domain.score.repository.sql.RoleCriterionRuleRepository ruleRepository) {
            return new RoleScoringEngine(ruleSetRepository, ruleRepository);
        }

        @Bean
        public CanonicalScoreSnapshotService canonicalScoreSnapshotService(
                RoleScoringEngine roleScoringEngine,
                ScoreSnapshotRepository snapshotRepository,
                RoleScoreRuleSetRepository ruleSetRepository,
                AccountRepository accountRepository) {
            
            com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService = org.mockito.Mockito.mock(com.apms.domain.profile.service.OwnerOrganizationService.class);
            com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository companyProfileVersionRepository = org.mockito.Mockito.mock(com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository.class);
            com.apms.domain.profile.CompanyProfileVersion mockProfile = com.apms.domain.profile.CompanyProfileVersion.builder().id("mock-profile-id").build();
            org.mockito.Mockito.when(companyProfileVersionRepository.findByCompanyProfileIdAndVersion(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.Optional.of(mockProfile));
            com.apms.domain.score.service.CanonicalScoreJsonMapper canonicalScoreJsonMapper = org.mockito.Mockito.mock(com.apms.domain.score.service.CanonicalScoreJsonMapper.class);
            org.mockito.Mockito.when(canonicalScoreJsonMapper.serializeMap(org.mockito.ArgumentMatchers.any())).thenReturn("{}");
            org.mockito.Mockito.when(canonicalScoreJsonMapper.serializeList(org.mockito.ArgumentMatchers.any())).thenReturn("[]");
            
            return new CanonicalScoreSnapshotService(
                    roleScoringEngine,
                    snapshotRepository,
                    ruleSetRepository,
                    ownerOrganizationService,
                    companyProfileVersionRepository,
                    canonicalScoreJsonMapper,
                    accountRepository
            );
        }

        @Bean
        public PotentialPartnerEvaluationApprovedEventProcessor approvedEventProcessor(
                ProjectTaskRepository taskRepository,
                ProjectTaskSubmissionRepository submissionRepository,
                com.apms.domain.audit.service.AuditLogService auditLogService,
                RoleEvaluationVersionRepository versionRepository,
                RoleScoringEngine scoringEngine,
                RoleScoreRuleSetRepository ruleSetRepository,
                CanonicalScoreSnapshotService canonicalScoreSnapshotService) {
            return new PotentialPartnerEvaluationApprovedEventProcessor(
                    taskRepository,
                    submissionRepository,
                    auditLogService,
                    versionRepository,
                    scoringEngine,
                    ruleSetRepository,
                    canonicalScoreSnapshotService
            );
        }

        @Bean
        public com.apms.domain.audit.service.AuditLogService auditLogService(
                AuditLogRepository auditLogRepository,
                AccountRepository accountRepository) {
            return new com.apms.domain.audit.service.AuditLogService(auditLogRepository, accountRepository);
        }
    }

    @Autowired private AccountRepository accountRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectTaskRepository taskRepository;
    @Autowired private ProjectTaskSubmissionRepository submissionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ScoreSnapshotRepository scoreSnapshotRepository;
    @Autowired private RoleScoreRuleSetRepository ruleSetRepository;
    @Autowired private RoleEvaluationVersionRepository versionRepository;
    @Autowired private RoleEvaluationOutboxEventRepository outboxEventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private RoleEvaluationOutboxEventProcessorDelegator delegator;
    @Autowired private PotentialPartnerEvaluationApprovedEventProcessor approvedEventProcessor;

    private Long accountId;
    private Long projectId;
    private Long taskId;
    private Long submissionId;
    private String evaluationId;
    private String versionId;

    @BeforeEach
    void setUp() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "audit_logs", "processed_outbox_events", "score_snapshots", "project_task_submissions", "project_tasks", "projects", "accounts", "role_score_rule_sets");
        mongoTemplate.dropCollection(RoleEvaluationOutboxEvent.class);
        mongoTemplate.dropCollection(RoleEvaluationVersion.class);

        Account account = new Account();
        account.setEmail("pp-integration@example.com");
        account.setPasswordHash("mockhash");
        account.setIsActive(true);
        account = accountRepository.save(account);
        accountId = account.getId();

        Project project = new Project();
        project.setProjectName("Test Project");
        project.setProjectType(com.apms.common.enums.ProjectType.RESEARCH_NEW_COMPANY);
        project.setTargetCompanyName("Mock Target Co");
        project.setStatus(com.apms.common.enums.ProjectStatus.ACTIVE);
        project.setCreatedByAccount(account);
        project = projectRepository.save(project);
        projectId = project.getId();

        ProjectTask task = new ProjectTask();
        task.setProject(project);
        task.setAssignedToAccount(account);
        task.setTitle("Test Task");
        task.setTaskType(com.apms.common.enums.TaskType.ROLE_EVALUATION);
        task.setPriority(TaskPriority.HIGH);
        task.setStatus(TaskStatus.TODO);
        task.setCreatedByAccount(account);
        task = taskRepository.save(task);
        taskId = task.getId();

        ProjectTaskSubmission submission = new ProjectTaskSubmission();
        submission.setProjectTask(task);
        submission.setProject(project);
        submission.setSubmittedByAccount(account);
        submission.setSubmissionType(SubmissionType.ROLE_EVALUATION);
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(LocalDateTime.now());
        submission = submissionRepository.save(submission);
        submissionId = submission.getId();

        evaluationId = UUID.randomUUID().toString();
        versionId = UUID.randomUUID().toString();

        // Seed POTENTIAL_PARTNER Rules
        RoleScoreRuleSet ruleSet = new RoleScoreRuleSet();
        ruleSet.setEvaluatedRole(CompanyRole.POTENTIAL_PARTNER);
        ruleSet.setRuleSetVersion("ROLE_SCORING_V1");
        ruleSet.setWeightSource(com.apms.domain.score.enums.WeightSource.EXPERT);
        ruleSet.setWeightVersion("V1");
        ruleSet.setWeightingMethod(com.apms.domain.score.enums.WeightingMethod.AHP);
        ruleSet.setActive(true);
        ruleSet.setCreatedByAccountId(account.getId());
        ruleSet.getRules().add(createRule(ruleSet, "strategicFitScore", "0.25", com.apms.domain.score.enums.ScoreDirection.BENEFIT));
        ruleSet.getRules().add(createRule(ruleSet, "capabilityComplementarityScore", "0.20", com.apms.domain.score.enums.ScoreDirection.BENEFIT));
        ruleSet.getRules().add(createRule(ruleSet, "trustReputationScore", "0.13", com.apms.domain.score.enums.ScoreDirection.BENEFIT));
        ruleSet.getRules().add(createRule(ruleSet, "financialAttractivenessScore", "0.16", com.apms.domain.score.enums.ScoreDirection.BENEFIT));
        ruleSet.getRules().add(createRule(ruleSet, "collaborationPotentialScore", "0.16", com.apms.domain.score.enums.ScoreDirection.BENEFIT));
        ruleSet.getRules().add(createRule(ruleSet, "partnershipRiskScore", "0.10", com.apms.domain.score.enums.ScoreDirection.BENEFIT));
        ruleSetRepository.save(ruleSet);
    }

    private com.apms.domain.score.RoleCriterionRule createRule(RoleScoreRuleSet ruleSet, String key, String weight, com.apms.domain.score.enums.ScoreDirection direction) {
        com.apms.domain.score.RoleCriterionRule rule = com.apms.domain.score.RoleCriterionRule.builder().criterionKey(key).weight(new BigDecimal(weight)).direction(direction).build();
        rule.setRuleSet(ruleSet);
        rule.setDirection(direction);
        rule.setCriterionName(key);
        rule.setRequired(true);
        rule.setDisplayOrder(1);
        rule.setActive(true);
        return rule;
    }

    @Test
    void testPotentialPartnerApprovalE2E() {
        // 1. Simulate Mongo Approval Transaction creating Version & Event
        Map<String, CriterionSnapshot> criteria = new HashMap<>();
        criteria.put("strategicFitScore", CriterionSnapshot.builder().rawScore(new BigDecimal("80.0")).build());
        criteria.put("capabilityComplementarityScore", CriterionSnapshot.builder().rawScore(new BigDecimal("70.0")).build());
        criteria.put("trustReputationScore", CriterionSnapshot.builder().rawScore(new BigDecimal("60.0")).build());
        criteria.put("financialAttractivenessScore", CriterionSnapshot.builder().rawScore(new BigDecimal("50.0")).build());
        criteria.put("collaborationPotentialScore", CriterionSnapshot.builder().rawScore(new BigDecimal("90.0")).build());
        criteria.put("partnershipRiskScore", CriterionSnapshot.builder().rawScore(new BigDecimal("75.0")).build());
        // Exact 71.70 from the documented example inputs

        RoleEvaluationVersion version = new RoleEvaluationVersion();
        version.setId(versionId);
        version.setEvaluationId(evaluationId);
        version.setEvaluatedRole(CompanyRole.POTENTIAL_PARTNER);
        version.setTargetCompanyProfileId("mock-profile-id");
        version.setCriteria(criteria);
        versionRepository.save(version);

        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .actorAccountId(accountId)
                .submissionId(submissionId)
                .approvedVersionId(versionId)
                .build();

        String eventId = UUID.randomUUID().toString();
        RoleEvaluationOutboxEvent event = RoleEvaluationOutboxEvent.builder()
                .id(eventId)
                .eventId(eventId)
                .evaluationId(evaluationId)
                .projectId(projectId)
                .taskId(taskId)
                .eventType(RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_APPROVED)
                .status(OutboxEventStatus.PENDING)
                .payload(payload)
                .payloadHash(RoleEvaluationOutboxPayloadHasher.hash(payload))
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(event);

        // 2. Process event through delegator
        delegator.processEventWithIdempotency(event, approvedEventProcessor);

        // 3. Verify exactly 1 processed outbox receipt
        int receipts = JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events");
        assertThat(receipts).isEqualTo(1);

        // 4. Verify Task / Submission transition
        ProjectTask updatedTask = taskRepository.findById(taskId).get();
        assertThat(updatedTask.getStatus()).isEqualTo(TaskStatus.DONE);

        ProjectTaskSubmission updatedSubmission = submissionRepository.findById(submissionId).get();
        assertThat(updatedSubmission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);

        // 5. Verify ScoreSnapshot & score of 71.70
        Iterable<ScoreSnapshot> snapshots = scoreSnapshotRepository.findAll();
        assertThat(snapshots).hasSize(1);
        ScoreSnapshot snapshot = snapshots.iterator().next();
        assertThat(snapshot.getOverallScore()).isEqualByComparingTo(new BigDecimal("71.70"));
        assertThat(snapshot.getEvaluatedRole()).isEqualTo(CompanyRole.POTENTIAL_PARTNER);

        // 6. Verify Audit Log
        int auditCount = JdbcTestUtils.countRowsInTable(jdbcTemplate, "audit_logs");
        assertThat(auditCount).isEqualTo(1);

        // 7. Verify Idempotency - duplicate processing does nothing
        delegator.processEventWithIdempotency(event, approvedEventProcessor);
        assertThat(JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events")).isEqualTo(1);
        assertThat(JdbcTestUtils.countRowsInTable(jdbcTemplate, "score_snapshots")).isEqualTo(1);

        // 8. Verify Tamper Rejection - different hash throws exception
        RoleEvaluationOutboxEvent tamperedEvent = RoleEvaluationOutboxEvent.builder()
                .eventId(eventId)
                .eventType(RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_APPROVED)
                .evaluationId("eval-123")
                .payloadHash("v1:sha256:fakehash123")
                .payload(payload)
                .build();
        org.junit.jupiter.api.Assertions.assertThrows(
                com.apms.domain.score.outbox.PayloadIntegrityException.class,
                () -> delegator.processEventWithIdempotency(tamperedEvent, approvedEventProcessor)
        );
    }
}
