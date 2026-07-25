package com.apms.domain.score.outbox;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.processor.PartnerEvaluationSubmittedEventProcessor;
import com.apms.domain.score.repository.mongo.RoleEvaluationOutboxEventRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.audit.AuditLog;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.score.service.RoleEvaluationMongoContainerHolder;
import com.apms.domain.score.service.RoleEvaluationSqlServerContainerHolder;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoClient;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RoleEvaluationOutboxRecoveryIntegrationTest.MinimalRecoveryConfig.class)
public class RoleEvaluationOutboxRecoveryIntegrationTest {

    @Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {
            "com.apms.domain.project.repository.sql",
            "com.apms.domain.user.repository.sql",
            "com.apms.domain.audit.repository.sql"
    })
    @EnableMongoRepositories(basePackages = "com.apms.domain.score.repository.mongo")
    public static class MinimalRecoveryConfig {

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
            em.setPackagesToScan("com.apms.domain.project", "com.apms.domain.user", "com.apms.domain.audit");
            HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            vendorAdapter.setGenerateDdl(false);
            em.setJpaVendorAdapter(vendorAdapter);

            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            em.setJpaProperties(properties);

            return em;
        }

        @Bean
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public MongoClient mongoClient() {
            return MongoClients.create(RoleEvaluationMongoContainerHolder.MONGO_CONTAINER.getReplicaSetUrl("rs0"));
        }

        @Bean
        public MongoTemplate mongoTemplate(MongoClient mongoClient) {
            return new MongoTemplate(mongoClient, "test");
        }

        @Bean
        public SqlIdempotencyService sqlIdempotencyService(JdbcTemplate jdbcTemplate) {
            return new SqlIdempotencyService(jdbcTemplate);
        }

        @Bean
        public com.apms.domain.audit.service.AuditLogService auditLogService(
                com.apms.domain.audit.repository.sql.AuditLogRepository auditLogRepository,
                com.apms.domain.user.repository.sql.AccountRepository accountRepository) {
            return new com.apms.domain.audit.service.AuditLogService(auditLogRepository, accountRepository);
        }

        @Bean
        public PartnerEvaluationSubmittedEventProcessor partnerEvaluationSubmittedEventProcessor(
                ProjectTaskRepository projectTaskRepository,
                ProjectTaskSubmissionRepository projectTaskSubmissionRepository,
                com.apms.domain.user.repository.sql.AccountRepository accountRepository,
                com.apms.domain.audit.service.AuditLogService auditLogService) {
            return Mockito.spy(new PartnerEvaluationSubmittedEventProcessor(
                    projectTaskRepository, projectTaskSubmissionRepository, accountRepository, auditLogService));
        }

        @Bean
        public RoleEvaluationOutboxEventProcessorDelegator delegator(
                SqlIdempotencyService sqlIdempotencyService) {
            return new RoleEvaluationOutboxEventProcessorDelegator(sqlIdempotencyService);
        }
    }

    @Autowired
    private RoleEvaluationOutboxEventRepository outboxEventRepository;

    @Autowired
    private RoleEvaluationOutboxEventProcessorDelegator delegator;

    @Autowired
    private PartnerEvaluationSubmittedEventProcessor processor;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectTaskRepository taskRepository;

    @Autowired
    private ProjectTaskSubmissionRepository submissionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditLogRepository auditLogEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private Account account;

    @BeforeEach
    void setUp() {
        submissionRepository.deleteAll();
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        accountRepository.deleteAll();
        auditLogEntryRepository.deleteAll();
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "processed_outbox_events");
        outboxEventRepository.deleteAll();
        Mockito.reset(processor);

        account = new Account();
        account.setEmail("testuser@example.com");
        account.setPasswordHash("hash");
        account = accountRepository.save(account);

        Project project = new Project();
        project.setProjectName("Test Project");
        project.setProjectType(com.apms.common.enums.ProjectType.UPDATE_EXISTING_COMPANY);
        project.setTargetCompanyName("Test Company");
        project.setCreatedByAccount(account);
        project.setStatus(com.apms.common.enums.ProjectStatus.ACTIVE);
        project = projectRepository.save(project);

        task = new ProjectTask();
        task.setProject(project);
        task.setAssignedToAccount(account);
        task.setCreatedByAccount(account);
        task.setStatus(TaskStatus.TODO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setTitle("Test Task");
        task = taskRepository.save(task);

        submission = new ProjectTaskSubmission();
        submission.setProjectTask(task);
        submission.setProject(project);
        submission.setSubmittedByAccount(account);
        submission.setSubmissionType(SubmissionType.ROLE_EVALUATION);
        submission.setTargetEntityType("ROLE_EVALUATION_DRAFT");
        submission.setTargetEntityId("draft-1");
        submission.setSubmittedAt(LocalDateTime.now());
        submission.setStatus(SubmissionStatus.DRAFT);
        submission = submissionRepository.save(submission);
    }

    private void assertMongoCheckpoints(String checkpointName, String id, RoleEvaluationOutboxEvent mappedEvent,
                                       String expectedEventId, String expectedEventType, String expectedEvaluationId,
                                       Long expectedTaskId, Long expectedActorAccountId, Long expectedSubmissionId) {
        // mapped repository reload
        RoleEvaluationOutboxEvent reloaded = outboxEventRepository.findById(id).orElseThrow();
        if (mappedEvent != null) {
            org.junit.jupiter.api.Assertions.assertNotNull(mappedEvent.getPayload(), checkpointName + " - mappedEvent payload is null");
            assertEquals(expectedSubmissionId, mappedEvent.getPayload().getSubmissionId(), checkpointName + " - mappedEvent payload submissionId mismatch");
        }
        org.junit.jupiter.api.Assertions.assertNotNull(reloaded.getPayload(), checkpointName + " - reloaded payload is null");
        assertEquals(expectedSubmissionId, reloaded.getPayload().getSubmissionId(), checkpointName + " - reloaded payload submissionId mismatch");

        // raw Mongo BSON
        org.bson.Document raw = mongoTemplate.getCollection("role_evaluation_outbox_events")
            .find(com.mongodb.client.model.Filters.eq("_id", id)).first();
        org.junit.jupiter.api.Assertions.assertNotNull(raw, checkpointName + " - raw Document is null");
        assertEquals(expectedEventId, raw.getString("eventId"), checkpointName + " - raw eventId mismatch");
        assertEquals(expectedEventType, raw.getString("eventType"), checkpointName + " - raw eventType mismatch");
        assertEquals(expectedEvaluationId, raw.getString("evaluationId"), checkpointName + " - raw evaluationId mismatch");
        assertEquals(expectedTaskId, raw.getLong("taskId"), checkpointName + " - raw taskId mismatch");

        org.bson.Document rawPayload = (org.bson.Document) raw.get("payload");
        org.junit.jupiter.api.Assertions.assertNotNull(rawPayload, checkpointName + " - raw payload is null");
        assertEquals(expectedActorAccountId, rawPayload.getLong("actorAccountId"), checkpointName + " - raw payload actorAccountId mismatch");
        assertEquals(expectedSubmissionId, rawPayload.getLong("submissionId"), checkpointName + " - raw payload submissionId mismatch");
    }

    @Test
    void testCrossDatabaseRecovery() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String evaluationId = "draft-1";
        Long taskId = task.getId();
        Long submissionId = submission.getId();
        Long actorAccountId = account.getId();
        RoleEvaluationOutboxEventType eventType = RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED;
        String payloadHash = null;

        // A. Worker A claims
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId(UUID.randomUUID().toString());
        event.setEventId(eventId);
        event.setEvaluationId(evaluationId);
        event.setEventType(eventType);
        event.setStatus(OutboxEventStatus.PENDING);
        event.setCreatedAt(LocalDateTime.now().minusDays(1));
        event.setAttemptCount(0);

        RoleEvaluationOutboxPayload payload = new RoleEvaluationOutboxPayload();
        payload.setActorAccountId(actorAccountId);
        payload.setSubmissionId(submissionId);
        event.setPayload(payload);
        event.setTaskId(taskId);

        outboxEventRepository.save(event);

        assertMongoCheckpoints("Checkpoint A", event.getId(), null, eventId, eventType.name(), evaluationId, taskId, actorAccountId, submissionId);

        LocalDateTime now = LocalDateTime.now();
        Optional<RoleEvaluationOutboxEvent> claimedWorkerA = outboxEventRepository.claimNextEvent("Worker-A", now, now.plusMinutes(5));
        assertTrue(claimedWorkerA.isPresent());
        RoleEvaluationOutboxEvent workerAEvent = claimedWorkerA.get();

        assertEquals(OutboxEventStatus.PROCESSING, workerAEvent.getStatus());
        assertEquals("Worker-A", workerAEvent.getLockedBy());
        assertEquals(1, workerAEvent.getAttemptCount());

        assertMongoCheckpoints("Checkpoint B", workerAEvent.getId(), workerAEvent, eventId, eventType.name(), evaluationId, taskId, actorAccountId, submissionId);

        // Assert identity before first SQL processing
        assertEquals(eventId, workerAEvent.getEventId());
        assertEquals(eventType, workerAEvent.getEventType());
        assertEquals(evaluationId, workerAEvent.getEvaluationId());
        assertEquals(taskId, workerAEvent.getTaskId());
        assertEquals(submissionId, workerAEvent.getPayload().getSubmissionId());
        assertEquals(actorAccountId, workerAEvent.getPayload().getActorAccountId());
        // payloadHash is null, no getter on payload
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(delegator));

        // B. SQL processing commits
        delegator.processEventWithIdempotency(workerAEvent, processor);

        assertEquals(1, JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events"));

        // Assert SQL Entity and Audit Identity
        ProjectTask reloadedTask = taskRepository.findById(taskId).get();
        assertEquals(taskId, reloadedTask.getId());
        assertEquals(TaskStatus.IN_REVIEW, reloadedTask.getStatus());

        ProjectTaskSubmission reloadedSubmission = submissionRepository.findById(submissionId).get();
        assertEquals(submissionId, reloadedSubmission.getId());
        assertEquals(taskId, reloadedSubmission.getProjectTask().getId());
        assertEquals(actorAccountId, reloadedSubmission.getSubmittedByAccount().getId());
        assertEquals(SubmissionStatus.IN_REVIEW, reloadedSubmission.getStatus());

        assertEquals(1, auditLogEntryRepository.count());
        AuditLog log = auditLogEntryRepository.findAll().iterator().next();
        assertEquals(com.apms.common.enums.AuditAction.PARTNER_EVALUATION_SUBMITTED, log.getAction());
        assertEquals(actorAccountId, log.getActorAccountId());
        assertEquals(evaluationId, log.getEntityId());
        assertEquals("ROLE_EVALUATION_DRAFT", log.getEntityType());

        // Assert receipt
        java.util.Map<String, Object> receipt1 = jdbcTemplate.queryForMap("SELECT * FROM processed_outbox_events WHERE event_id = ?", eventId);
        assertEquals(eventId, receipt1.get("event_id"));
        assertEquals(eventType.name(), receipt1.get("event_type"));
        assertEquals(evaluationId, receipt1.get("aggregate_id"));
        assertEquals(payloadHash, receipt1.get("payload_hash"));

        verify(processor, times(1)).process(any());

        assertMongoCheckpoints("Checkpoint C", workerAEvent.getId(), null, eventId, eventType.name(), evaluationId, taskId, actorAccountId, submissionId);

        // C. Expire Worker A lock
        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(workerAEvent.getId())
        );
        org.springframework.data.mongodb.core.query.Update update = new org.springframework.data.mongodb.core.query.Update()
                .set("lockedAt", LocalDateTime.now().minusMinutes(1));
        mongoTemplate.updateFirst(query, update, RoleEvaluationOutboxEvent.class);

        RoleEvaluationOutboxEvent expiredEvent = outboxEventRepository.findById(workerAEvent.getId()).get();
        assertEquals(OutboxEventStatus.PROCESSING, expiredEvent.getStatus());
        assertEquals("Worker-A", expiredEvent.getLockedBy());
        assertEquals(1, expiredEvent.getAttemptCount());

        assertMongoCheckpoints("Checkpoint D", workerAEvent.getId(), null, eventId, eventType.name(), evaluationId, taskId, actorAccountId, submissionId);

        // D. Worker B reclaims
        Optional<RoleEvaluationOutboxEvent> claimedWorkerB = outboxEventRepository.claimNextEvent("Worker-B", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertTrue(claimedWorkerB.isPresent());
        RoleEvaluationOutboxEvent workerBEvent = claimedWorkerB.get();

        assertEquals(OutboxEventStatus.PROCESSING, workerBEvent.getStatus());
        assertEquals("Worker-B", workerBEvent.getLockedBy());
        assertEquals(2, workerBEvent.getAttemptCount());

        assertMongoCheckpoints("Checkpoint E", workerBEvent.getId(), workerBEvent, eventId, eventType.name(), evaluationId, taskId, actorAccountId, submissionId);

        // Assert Identity Survives Reclaim
        assertEquals(eventId, workerBEvent.getEventId());
        assertEquals(eventType, workerBEvent.getEventType());
        assertEquals(evaluationId, workerBEvent.getEvaluationId());
        assertEquals(taskId, workerBEvent.getTaskId());
        assertEquals(submissionId, workerBEvent.getPayload().getSubmissionId());
        assertEquals(actorAccountId, workerBEvent.getPayload().getActorAccountId());

        // E. Stale Worker A finalization is rejected
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> {
            outboxEventRepository.finalizeAsProcessed(workerBEvent.getId(), "Worker-A");
        });

        RoleEvaluationOutboxEvent eventAfterRejected = outboxEventRepository.findById(workerBEvent.getId()).get();
        assertEquals(OutboxEventStatus.PROCESSING, eventAfterRejected.getStatus());
        assertEquals("Worker-B", eventAfterRejected.getLockedBy());
        assertEquals(2, eventAfterRejected.getAttemptCount());

        // F. Worker B retries SQL processing
        delegator.processEventWithIdempotency(workerBEvent, processor);

        // Assert identity after duplicate SQL attempt
        assertEquals(1, JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events"));
        assertEquals(1, auditLogEntryRepository.count());
        assertEquals(taskId, taskRepository.findById(task.getId()).get().getId());
        assertEquals(submissionId, submissionRepository.findById(submission.getId()).get().getId());
        assertEquals(actorAccountId, submissionRepository.findById(submission.getId()).get().getSubmittedByAccount().getId());

        java.util.Map<String, Object> receipt2 = jdbcTemplate.queryForMap("SELECT * FROM processed_outbox_events WHERE event_id = ?", eventId);
        assertEquals(eventId, receipt2.get("event_id"));
        assertEquals(eventType.name(), receipt2.get("event_type"));
        assertEquals(evaluationId, receipt2.get("aggregate_id"));
        assertEquals(payloadHash, receipt2.get("payload_hash"));

        verify(processor, times(1)).process(any()); // Should not have increased

        // G. Worker B finalizes Mongo
        outboxEventRepository.finalizeAsProcessed(workerBEvent.getId(), "Worker-B");

        RoleEvaluationOutboxEvent finalEvent = outboxEventRepository.findById(workerBEvent.getId()).get();
        // Assert Final Mongo Identity
        assertEquals(OutboxEventStatus.PROCESSED, finalEvent.getStatus());
        assertEquals(2, finalEvent.getAttemptCount());
        assertNull(finalEvent.getLockedBy());
        assertNull(finalEvent.getLockedAt());

        assertEquals(eventId, finalEvent.getEventId());
        assertEquals(eventType, finalEvent.getEventType());
        assertEquals(evaluationId, finalEvent.getEvaluationId());
        assertEquals(taskId, finalEvent.getTaskId());
        assertEquals(submissionId, finalEvent.getPayload().getSubmissionId());
        assertEquals(actorAccountId, finalEvent.getPayload().getActorAccountId());
    }
}
