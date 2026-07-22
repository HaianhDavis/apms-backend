package com.apms.domain.score.draft;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
public class RoleEvaluationPointerFoundationTest {

    @Autowired
    private RoleEvaluationDraftRepository draftRepository;

    @Autowired
    private RoleEvaluationVersionRepository versionRepository;

    @AfterEach
    void tearDown() {
        draftRepository.deleteAll();
        versionRepository.deleteAll();
    }

    @Test
    void shouldUpdatePointerWithoutModifyingApprovedVersion() {
        // 1. Create and persist an approved version
        RoleEvaluationVersion version1 = RoleEvaluationVersion.builder()
                .evaluationId("eval1")
                .versionNumber(1)
                .projectId(10L)
                .taskId(20L)
                .evaluatedRole(CompanyRole.PARTNER)
                .status(RoleEvaluationStatus.APPROVED)
                .build();
        version1 = versionRepository.save(version1); // Saved, so now has MongoDB ID and truncated dates

        // 2. Create and persist a working draft pointing to version 1
        RoleEvaluationDraft draft = RoleEvaluationDraft.builder()
                .projectId(10L)
                .taskId(20L)
                .evaluatedRole(CompanyRole.PARTNER)
                .status(RoleEvaluationStatus.DRAFT)
                .currentApprovedVersionId(version1.getId())
                .currentApprovedVersionNumber(version1.getVersionNumber())
                .workingRevisionNumber(2)
                .build();
        draft = draftRepository.save(draft);

        assertNotNull(draft.getId());
        assertEquals(version1.getId(), draft.getCurrentApprovedVersionId());
        assertEquals(1, draft.getCurrentApprovedVersionNumber());
        assertEquals(2, draft.getWorkingRevisionNumber());
        assertEquals(0L, draft.getOptimisticVersion());

        // Capture version 1 fields for comparison AFTER saving (to have truncated dates)
        String initialEvalId = version1.getEvaluationId();
        Integer initialVersion = version1.getVersionNumber();
        CompanyRole initialRole = version1.getEvaluatedRole();
        RoleEvaluationStatus initialStatus = version1.getStatus();
        RoleEvaluationVersion snapshotOfVersion1 = versionRepository.findById(version1.getId()).orElseThrow();

        // 3. Create a new approved version 2
        RoleEvaluationVersion version2 = RoleEvaluationVersion.builder()
                .evaluationId("eval1")
                .versionNumber(2)
                .projectId(10L)
                .taskId(20L)
                .evaluatedRole(CompanyRole.PARTNER)
                .status(RoleEvaluationStatus.APPROVED)
                .build();
        version2 = versionRepository.save(version2);

        // 4. Update the draft pointer to version 2
        draft.setCurrentApprovedVersionId(version2.getId());
        draft.setCurrentApprovedVersionNumber(version2.getVersionNumber());
        draft.setWorkingRevisionNumber(3);
        draft = draftRepository.save(draft);

        // 5. Reload and prove version 1 is unchanged byte-for-byte (not modified by draft updates)
        RoleEvaluationVersion loadedVersion1 = versionRepository.findById(version1.getId()).orElseThrow();
        assertEquals(initialEvalId, loadedVersion1.getEvaluationId());
        assertEquals(initialVersion, loadedVersion1.getVersionNumber());
        assertEquals(initialRole, loadedVersion1.getEvaluatedRole());
        assertEquals(initialStatus, loadedVersion1.getStatus());
        assertEquals(snapshotOfVersion1, loadedVersion1); // Assert all persisted fields match via equals()

        // Assert draft pointer changed
        RoleEvaluationDraft reloadedDraft = draftRepository.findById(draft.getId()).orElseThrow();
        assertEquals(version2.getId(), reloadedDraft.getCurrentApprovedVersionId());
        assertEquals(2, reloadedDraft.getCurrentApprovedVersionNumber());
        assertEquals(3, reloadedDraft.getWorkingRevisionNumber());
        assertEquals(1L, reloadedDraft.getOptimisticVersion());
    }
}
