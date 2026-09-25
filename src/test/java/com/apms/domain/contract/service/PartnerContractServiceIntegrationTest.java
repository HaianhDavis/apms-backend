//package com.apms.domain.contract.service;
//
//import com.apms.ApmsIntegrationTestBase;
//import com.apms.domain.contract.dto.ReviewPartnerContractRequest;
//import com.apms.domain.contract.enums.ContractReviewStatus;
//import com.apms.domain.contract.entity.PartnerContract;
//import com.apms.domain.contract.repository.sql.PartnerContractRepository;
//import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
//import com.apms.domain.project.Project;
//import com.apms.domain.project.repository.sql.ProjectRepository;
//import com.apms.domain.user.Account;
//import com.apms.domain.user.repository.sql.AccountRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
//import org.springframework.transaction.support.TransactionTemplate;
//
//import java.util.Optional;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.doThrow;
//
//class PartnerContractServiceIntegrationTest extends ApmsIntegrationTestBase {
//
//    @Autowired
//    private PartnerContractService contractService;
//
//    @Autowired
//    private PartnerContractRepository contractRepository;
//
//    @Autowired
//    private ProjectRepository projectRepository;
//
//    @Autowired
//    private AccountRepository accountRepository;
//
//    @MockitoSpyBean
//    private PartnerContractVersionRepository versionRepository;
//
//    private Long testProjectId;
//
//    @Autowired
//    private TransactionTemplate transactionTemplate;
//
//    private Long testContractId;
//
//    @BeforeEach
//    void setUp() {
//        transactionTemplate.executeWithoutResult(status -> {
//            Account account = accountRepository.findByEmail("test@example.com").orElseGet(() -> {
//                Account acc = new Account();
//                acc.setEmail("test@example.com");
//                acc.setPasswordHash("hash");
//                return accountRepository.save(acc);
//            });
//
//            Project project = new Project();
//            project.setTargetCompanyProfileId("target-company");
//            project.setTargetCompanyName("Target Company");
//            project.setCreatedByAccount(account);
//            project.setCreatedAt(java.time.LocalDateTime.now());
//            project.setProjectName("Test Project");
//            project.setProjectType(com.apms.common.enums.ProjectType.RESEARCH_NEW_COMPANY);
//            project = projectRepository.save(project);
//            testProjectId = project.getId();
//
//            PartnerContract contract = new PartnerContract();
//            contract.setSourceProjectId(testProjectId);
//            contract.setPartnerCompanyId("partner-company");
//            contract.setReferenceCompanyId("reference-company");
//            contract.setReviewStatus(ContractReviewStatus.IN_REVIEW);
//            contract.setCurrentVersion(0);
//            contract.setCreatedByAccountId(1L);
//            contract.setCreatedAt(java.time.LocalDateTime.now());
//            contract = contractRepository.save(contract);
//            testContractId = contract.getId();
//        });
//    }
//
//    @Test
//    void testTransactionalRollbackOnVersionSaveFailure() {
//        Long contractId = testContractId;
//        Long approverId = 99L;
//
//        ReviewPartnerContractRequest request = new ReviewPartnerContractRequest();
//        request.setDecision("APPROVE");
//
//        // Force a failure during version save to trigger a transaction rollback
//        doThrow(new RuntimeException("Simulated version save failure"))
//                .when(versionRepository).save(any());
//
//        assertThrows(RuntimeException.class, () -> contractService.reviewContract(contractId, request, approverId));
//
//        // Start a fresh transaction/session to read from DB
//        transactionTemplate.executeWithoutResult(status -> {
//            Optional<PartnerContract> reloadedOpt = contractRepository.findById(contractId);
//            assertTrue(reloadedOpt.isPresent());
//            PartnerContract reloaded = reloadedOpt.get();
//
//            // Ensure the contract was rolled back and did NOT get approved
//            assertEquals(ContractReviewStatus.IN_REVIEW, reloaded.getReviewStatus());
//            assertEquals(0, reloaded.getCurrentVersion());
//            assertNull(reloaded.getApprovedByAccountId());
//            assertNull(reloaded.getApprovedAt());
//
//            // Assert that no PartnerContractVersion was saved
//            assertEquals(0, versionRepository.count());
//        });
//    }
//}
