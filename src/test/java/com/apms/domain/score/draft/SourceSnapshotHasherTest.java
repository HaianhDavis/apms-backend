package com.apms.domain.score.draft;

import com.apms.domain.score.enums.ApprovedSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceSnapshotHasherTest {

    @Test
    void testEmptyListProducesDeterministicHash() {
        String hash1 = SourceSnapshotHasher.hash(new ArrayList<>());
        String hash2 = SourceSnapshotHasher.hash(null);

        assertNotNull(hash1);
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length()); // SHA-256 is 64 hex chars
    }

    @Test
    void testOrderIndependence() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1")
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .sourceVersionNumber(1)
                .criterionKey("crit1")
                .sharedAcrossCriteria(false)
                .build();

        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref2")
                .sourceType(ApprovedSourceType.COMPANY_PROFILE_VERSION)
                .mongoSourceId("profile1")
                .sourceVersionNumber(2)
                .criterionKey("crit2")
                .sharedAcrossCriteria(true)
                .build();

        String hash1 = SourceSnapshotHasher.hash(Arrays.asList(ref1, ref2));
        String hash2 = SourceSnapshotHasher.hash(Arrays.asList(ref2, ref1));

        assertEquals(hash1, hash2, "Hashing must be independent of input list order");
    }

    @Test
    void testHashChangesOnFieldModification() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1")
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .sourceVersionNumber(1)
                .criterionKey("crit1")
                .sharedAcrossCriteria(false)
                .build();

        String originalHash = SourceSnapshotHasher.hash(List.of(ref1));

        ApprovedSourceReference refModified = ApprovedSourceReference.builder()
                .referenceId("ref1")
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .sourceVersionNumber(2) // Changed version
                .criterionKey("crit1")
                .sharedAcrossCriteria(false)
                .build();

    }

    @Test
    void testSourceVersionNumberSensitivity() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit1").sharedAcrossCriteria(false).build();
        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref1").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(2).criterionKey("crit1").sharedAcrossCriteria(false).build();
        assertNotEquals(SourceSnapshotHasher.hash(List.of(ref1)), SourceSnapshotHasher.hash(List.of(ref2)));
    }

    @Test
    void testCriterionKeySensitivity() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit1").sharedAcrossCriteria(false).build();
        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref2").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit2").sharedAcrossCriteria(false).build();
        assertNotEquals(SourceSnapshotHasher.hash(List.of(ref1)), SourceSnapshotHasher.hash(List.of(ref2)));
    }

    @Test
    void testSharedAcrossCriteriaSensitivity() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit1").sharedAcrossCriteria(false).build();
        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref2").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit1").sharedAcrossCriteria(true).build();
        assertNotEquals(SourceSnapshotHasher.hash(List.of(ref1)), SourceSnapshotHasher.hash(List.of(ref2)));
    }

    @Test
    void testImmutableSourceIdSensitivity() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit1").sharedAcrossCriteria(false).build();
        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref2").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(2L).sourceVersionNumber(1).criterionKey("crit1").sharedAcrossCriteria(false).build();
        assertNotEquals(SourceSnapshotHasher.hash(List.of(ref1)), SourceSnapshotHasher.hash(List.of(ref2)));
    }

    @Test
    void testDeterministicReferenceIdBehavior() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref-A").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit1").build();
        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref-B").sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L).sourceVersionNumber(1).criterionKey("crit1").build();
        assertNotEquals(SourceSnapshotHasher.hash(List.of(ref1)), SourceSnapshotHasher.hash(List.of(ref2)),
                "Hash must be sensitive to the reference ID itself if other fields are identical (though validator should prevent this)");
    }

    @Test
    void testDuplicateReferenceIdRejected() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1")
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .sourceVersionNumber(1)
                .criterionKey("crit1")
                .build();

        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref1") // Duplicate referenceId
                .sourceType(ApprovedSourceType.COMPANY_PROFILE_VERSION)
                .mongoSourceId("profile1")
                .sourceVersionNumber(1)
                .criterionKey("crit2")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SourceSnapshotHasher.hash(Arrays.asList(ref1, ref2)));
        assertTrue(ex.getMessage().contains("Duplicate referenceId"));
    }

    @Test
    void testDuplicateImmutableIdentityRejected() {
        ApprovedSourceReference ref1 = ApprovedSourceReference.builder()
                .referenceId("ref1")
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .sourceVersionNumber(1)
                .criterionKey("crit1")
                .build();

        ApprovedSourceReference ref2 = ApprovedSourceReference.builder()
                .referenceId("ref2")
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .sourceVersionNumber(1)
                .criterionKey("crit1")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SourceSnapshotHasher.hash(Arrays.asList(ref1, ref2)));
        assertTrue(ex.getMessage().contains("Duplicate immutable source identity"));
    }
}
