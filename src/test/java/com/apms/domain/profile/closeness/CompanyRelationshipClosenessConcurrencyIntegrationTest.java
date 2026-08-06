package com.apms.domain.profile.closeness;

import com.apms.common.enums.SystemRole;
import com.apms.domain.profile.closeness.dto.RelationshipClosenessResponse;
import com.apms.domain.profile.closeness.dto.UpdateRelationshipClosenessRequest;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import org.springframework.boot.test.mock.mockito.MockBean;
import com.apms.domain.profile.CompanyProfile;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.apms.ApmsIntegrationTestBase;

public class CompanyRelationshipClosenessConcurrencyIntegrationTest extends ApmsIntegrationTestBase {

    @Autowired
    private CompanyRelationshipClosenessService closenessService;

    @Autowired
    private CompanyRelationshipClosenessRepository closenessRepository;

    @MockBean
    private CompanyProfileRepository companyProfileRepository;

    @MockBean
    private OwnerOrganizationService ownerOrganizationService;

    @MockBean
    private com.apms.domain.audit.service.AuditLogService auditLogService;

    private final String TARGET_COMPANY_ID = "6a31a0000000000000000002";
    private final String OWNER_COMPANY_ID = "6a31a0000000000000000001";
    private UserDetailsImpl ownerUser;

    @BeforeEach
    void setUp() {
        ownerUser = new UserDetailsImpl(1L, "owner@test.com", "pass", Set.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")), true);
        closenessRepository.deleteAll();

        CompanyProfile target = new CompanyProfile();
        target.setId(TARGET_COMPANY_ID);
        org.mockito.Mockito.when(companyProfileRepository.findById(TARGET_COMPANY_ID)).thenReturn(Optional.of(target));
        org.mockito.Mockito.when(ownerOrganizationService.isOwnerCompany(TARGET_COMPANY_ID)).thenReturn(false);
        org.mockito.Mockito.when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(OWNER_COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        closenessRepository.deleteAll();
    }

    @Test
    void testConcurrentUpsertOnlyCreatesOneRowAndUpdatesExisting() throws Exception {
        int threads = 4;
        ExecutorService executorService = Executors.newFixedThreadPool(threads);

        List<Callable<RelationshipClosenessResponse>> tasks = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            final int stars = (i % 5) + 1;
            tasks.add(() -> {
                UpdateRelationshipClosenessRequest req = new UpdateRelationshipClosenessRequest();
                req.setStars(stars);
                req.setNote("Concurrent request " + stars);
                return closenessService.updateCloseness(TARGET_COMPANY_ID, req, ownerUser);
            });
        }

        List<Future<RelationshipClosenessResponse>> futures = executorService.invokeAll(tasks);
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);

        // Verify no exceptions were thrown (all futures completed successfully)
        for (Future<RelationshipClosenessResponse> future : futures) {
            future.get(); // Will throw exception if execution failed
        }

        // Verify exactly one row remains
        assertEquals(1, closenessRepository.count());

        CompanyRelationshipCloseness entity = closenessRepository.findAll().get(0);
        assertTrue(entity.getStars() >= 1 && entity.getStars() <= 5);
        assertEquals(ownerUser.getId(), entity.getRatedByAccountId());
    }
}
