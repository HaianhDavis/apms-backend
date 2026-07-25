package com.apms.domain.score.outbox;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.AuditLog;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.processor.PartnerEvaluationApprovedEventProcessor;
import com.apms.domain.score.outbox.processor.PartnerEvaluationRevisionRequestedEventProcessor;
import com.apms.domain.score.outbox.processor.PartnerEvaluationSubmittedEventProcessor;
import com.apms.domain.score.service.RoleEvaluationSqlServerContainerHolder;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RoleEvaluationSqlTransitionServiceIntegrationTest.TestConfig.class)
public class RoleEvaluationSqlTransitionServiceIntegrationTest {

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {
            "com.apms.domain.project.repository.sql",
            "com.apms.domain.user.repository.sql",
            "com.apms.domain.audit.repository.sql"
    })
    public static class TestConfig {

        @Bean
        public DataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getJdbcUrl());
            dataSource.setUsername(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getUsername());
            dataSource.setPassword(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getPassword());
            dataSource.setDriverClassName(RoleEvaluationSqlServerContainerHolder.SQL_CONTAINER.getDriverClassName());

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
            em.setPackagesToScan("com.apms.domain.project", "com.apms.domain.user", "com.apms.domain.audit");

            HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            em.setJpaVendorAdapter(vendorAdapter);

            Properties properties = new Properties();
            properties.setProperty("hibernate.hbm2ddl.auto", "validate");
            properties.setProperty("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
            properties.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            em.setJpaProperties(properties);

            return em;
        }

        @Bean(name = "transactionManager")
        public JpaTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean entityManagerFactory, DataSource dataSource) {
            JpaTransactionManager txManager = new JpaTransactionManager();
            txManager.setEntityManagerFactory(entityManagerFactory.getObject());
            txManager.setDataSource(dataSource);
            return txManager;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public SqlIdempotencyService sqlIdempotencyService(JdbcTemplate jdbcTemplate) {
            return new SqlIdempotencyService(jdbcTemplate);
        }

        @Bean
        public AuditLogService auditLogService(AuditLogRepository auditLogRepository, AccountRepository accountRepository) {
            return new AuditLogService(auditLogRepository, accountRepository);
        }

        @Bean
        public PartnerEvaluationSubmittedEventProcessor partnerEvaluationSubmittedEventProcessor(
                ProjectTaskRepository taskRepository,
                ProjectTaskSubmissionRepository submissionRepository,
                AccountRepository accountRepository,
                AuditLogService auditLogService) {
            return new PartnerEvaluationSubmittedEventProcessor(taskRepository, submissionRepository, accountRepository, auditLogService);
        }

        @Bean
        public PartnerEvaluationApprovedEventProcessor partnerEvaluationApprovedEventProcessor(
                ProjectTaskRepository taskRepository,
                ProjectTaskSubmissionRepository submissionRepository,
                AuditLogService auditLogService) {
            return new PartnerEvaluationApprovedEventProcessor(taskRepository, submissionRepository, auditLogService);
        }

        @Bean
        public PartnerEvaluationRevisionRequestedEventProcessor partnerEvaluationRevisionRequestedEventProcessor(
                ProjectTaskRepository taskRepository,
                ProjectTaskSubmissionRepository submissionRepository,
                AuditLogService auditLogService) {
            return new PartnerEvaluationRevisionRequestedEventProcessor(taskRepository, submissionRepository, auditLogService);
        }

        @Bean
        public RoleEvaluationOutboxEventProcessorDelegator delegator(SqlIdempotencyService sqlIdempotencyService) {
            return new RoleEvaluationOutboxEventProcessorDelegator(sqlIdempotencyService);
        }
    }

    @Autowired
    private RoleEvaluationOutboxEventProcessorDelegator delegator;

    @Autowired
    private PartnerEvaluationSubmittedEventProcessor submittedProcessor;

    @Autowired
    private PartnerEvaluationApprovedEventProcessor approvedProcessor;

    @Autowired
    private PartnerEvaluationRevisionRequestedEventProcessor revisionProcessor;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectTaskRepository taskRepository;

    @Autowired
    private ProjectTaskSubmissionRepository submissionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account account;
    private Project project;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private RoleEvaluationOutboxEvent event;

    @BeforeEach
    void setUp() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "audit_logs", "project_task_submissions", "project_tasks", "projects", "account_roles", "accounts", "processed_outbox_events");

        account = new Account();
        account.setEmail("testuser@example.com");
        account.setPasswordHash("hash");
        account = accountRepository.save(account);

        project = new Project();
        project.setProjectName("Test Project");
        project.setProjectType(com.apms.common.enums.ProjectType.UPDATE_EXISTING_COMPANY);
        project.setTargetCompanyName("Test Company");
        project.setCreatedByAccount(account);
        project = projectRepository.save(project);

        task = new ProjectTask();
        task.setProject(project);
        task.setAssignedToAccount(account);
        task.setCreatedByAccount(account);
        task.setStatus(TaskStatus.TODO);
        task.setPriority(com.apms.common.enums.TaskPriority.MEDIUM);
        task.setTitle("Test Task");
        task = taskRepository.save(task);

        submission = new ProjectTaskSubmission();
        submission.setProjectTask(task);
        submission.setProject(project);
        submission.setSubmittedByAccount(account);
        submission.setSubmissionType(SubmissionType.ROLE_EVALUATION);
        submission.setSubmittedAt(LocalDateTime.now());
        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setTargetEntityId("draft-1");
        submission = submissionRepository.save(submission);

        event = new RoleEvaluationOutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEvaluationId("draft-1");

        RoleEvaluationOutboxPayload payload = new RoleEvaluationOutboxPayload();
        payload.setActorAccountId(account.getId());
        payload.setSubmissionId(submission.getId());
        event.setPayload(payload);
        event.setTaskId(task.getId());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_submission_update");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_audit_insert");
    }

    @Test
    void testSqlReceiptTransitionAndAuditCommitTogether() {
        assertTrue(AopUtils.isAopProxy(delegator));

        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED);
        delegator.processEventWithIdempotency(event, submittedProcessor);

        assertEquals(1, JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events"));

        ProjectTaskSubmission updatedSub = submissionRepository.findById(submission.getId()).orElseThrow();
        assertEquals(SubmissionStatus.IN_REVIEW, updatedSub.getStatus());

        ProjectTask updatedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.IN_REVIEW, updatedTask.getStatus());

        assertEquals(1, auditLogRepository.count());
        AuditLog log = auditLogRepository.findAll().get(0);
        assertEquals(AuditAction.PARTNER_EVALUATION_SUBMITTED, log.getAction());
        assertEquals(account.getId(), log.getActorAccountId());
        assertEquals("ROLE_EVALUATION_DRAFT", log.getEntityType());
        assertEquals("draft-1", log.getEntityId());
    }

    @Test
    void testTransitionFailureRollsBackReceiptAndAudit() {
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED);

        jdbcTemplate.execute("CREATE TRIGGER trg_fail_submission_update ON project_task_submissions AFTER UPDATE AS BEGIN RAISERROR('Simulated transition failure', 16, 1); ROLLBACK TRANSACTION; END;");

        Exception e = assertThrows(Exception.class, () -> delegator.processEventWithIdempotency(event, submittedProcessor));

        assertEquals(0, JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events"));

        ProjectTaskSubmission updatedSub = submissionRepository.findById(submission.getId()).orElseThrow();
        assertEquals(SubmissionStatus.DRAFT, updatedSub.getStatus());

        ProjectTask updatedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.TODO, updatedTask.getStatus());

        assertEquals(0, auditLogRepository.count());
    }

    @Test
    void testAuditFailureRollsBackReceiptAndTransition() {
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED);

        jdbcTemplate.execute("CREATE TRIGGER trg_fail_audit_insert ON audit_logs AFTER INSERT AS BEGIN RAISERROR('Simulated audit failure', 16, 1); ROLLBACK TRANSACTION; END;");

        Exception e = assertThrows(Exception.class, () -> delegator.processEventWithIdempotency(event, submittedProcessor));

        assertEquals(0, JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events"));

        ProjectTaskSubmission updatedSub = submissionRepository.findById(submission.getId()).orElseThrow();
        assertEquals(SubmissionStatus.DRAFT, updatedSub.getStatus());

        ProjectTask updatedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.TODO, updatedTask.getStatus());

        assertEquals(0, JdbcTestUtils.countRowsInTable(jdbcTemplate, "audit_logs"));
    }

    @Test
    void testDuplicateProcessingCreatesNoSecondTransitionOrAudit() {
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED);

        delegator.processEventWithIdempotency(event, submittedProcessor);
        assertEquals(1, JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events"));
        assertEquals(1, auditLogRepository.count());

        LocalDateTime firstTaskUpdatedAt = taskRepository.findById(task.getId()).orElseThrow().getUpdatedAt();
        LocalDateTime firstSubUpdatedAt = submissionRepository.findById(submission.getId()).orElseThrow().getUpdatedAt();

        delegator.processEventWithIdempotency(event, submittedProcessor);

        assertEquals(1, JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events"));
        assertEquals(1, auditLogRepository.count());

        assertEquals(firstTaskUpdatedAt, taskRepository.findById(task.getId()).orElseThrow().getUpdatedAt());
        assertEquals(firstSubUpdatedAt, submissionRepository.findById(submission.getId()).orElseThrow().getUpdatedAt());
    }

    @Test
    void testApprovedMapping() {
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_APPROVED);
        delegator.processEventWithIdempotency(event, approvedProcessor);

        ProjectTaskSubmission updatedSub = submissionRepository.findById(submission.getId()).orElseThrow();
        assertEquals(SubmissionStatus.APPROVED, updatedSub.getStatus());

        ProjectTask updatedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.DONE, updatedTask.getStatus());

        AuditLog log = auditLogRepository.findAll().get(0);
        assertEquals(AuditAction.PARTNER_EVALUATION_APPROVED, log.getAction());
    }

    @Test
    void testRevisionRequestedMapping() {
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_REVISION_REQUESTED);
        event.getPayload().setManagerFeedback("Needs fixing");

        delegator.processEventWithIdempotency(event, revisionProcessor);

        ProjectTaskSubmission updatedSub = submissionRepository.findById(submission.getId()).orElseThrow();
        assertEquals(SubmissionStatus.REJECTED, updatedSub.getStatus(), "REJECTED status represents the existing submission revision state");

        ProjectTask updatedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.IN_PROGRESS, updatedTask.getStatus());

        AuditLog log = auditLogRepository.findAll().get(0);
        assertEquals(AuditAction.PARTNER_EVALUATION_REVISION_REQUESTED, log.getAction());
    }
}
