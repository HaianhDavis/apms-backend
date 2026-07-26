package com.apms.domain.score.repository.mongo;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest(properties = "spring.data.mongodb.auto-index-creation=true")
public class RoleEvaluationVersionRepositoryTest {

    @Autowired
    private RoleEvaluationVersionRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    @Test
    void shouldEnforceUniqueIndexOnEvaluationIdAndVersionNumber() {
        // Assert index exists at runtime
        List<IndexInfo> indexInfoList = mongoTemplate.indexOps(RoleEvaluationVersion.class).getIndexInfo();
        boolean indexExists = indexInfoList.stream().anyMatch(info ->
            info.isUnique() &&
            info.getIndexFields().size() == 2 &&
            info.getIndexFields().get(0).getKey().equals("evaluationId") &&
            info.getIndexFields().get(1).getKey().equals("versionNumber")
        );
        assertTrue(indexExists, "evaluation_version_idx compound unique index must exist at runtime");

        // Create first version
        RoleEvaluationVersion v1 = RoleEvaluationVersion.builder()
                .id("mongo_id_1")
                .evaluationId("eval123")
                .versionNumber(1)
                .projectId(1L)
                .taskId(10L)
                .evaluatedRole(CompanyRole.PARTNER)
                .status(RoleEvaluationStatus.APPROVED)
                .build();
        repository.save(v1);

        // Attempt duplicate version 1 for same evaluation, different Mongo ID
        RoleEvaluationVersion duplicate = RoleEvaluationVersion.builder()
                .id("mongo_id_2")
                .evaluationId("eval123")
                .versionNumber(1)
                .projectId(1L)
                .taskId(10L)
                .evaluatedRole(CompanyRole.PARTNER)
                .status(RoleEvaluationStatus.APPROVED)
                .build();

        assertThrows(DuplicateKeyException.class, () -> repository.save(duplicate));

        // Create version 2 for the same evaluation (should succeed)
        RoleEvaluationVersion v2 = RoleEvaluationVersion.builder()
                .evaluationId("eval123")
                .versionNumber(2)
                .projectId(1L)
                .taskId(10L)
                .evaluatedRole(CompanyRole.PARTNER)
                .status(RoleEvaluationStatus.APPROVED)
                .build();
        assertDoesNotThrow(() -> repository.save(v2));

        Optional<RoleEvaluationVersion> foundV1 = repository.findByEvaluationIdAndVersionNumber("eval123", 1);
        Optional<RoleEvaluationVersion> foundV2 = repository.findByEvaluationIdAndVersionNumber("eval123", 2);

        assertTrue(foundV1.isPresent());
        assertTrue(foundV2.isPresent());
    }
}
