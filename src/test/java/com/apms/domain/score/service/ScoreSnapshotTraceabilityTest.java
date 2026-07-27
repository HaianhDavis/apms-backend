package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.enums.ScoreTraceabilityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreSnapshotTraceabilityTest {

    @Test
    @DisplayName("COMPETITOR resolves to DRAFT_BASED")
    void competitorResolvesToDraftBased() {
        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setEvaluatedRole(CompanyRole.COMPETITOR);
        snapshot.setApprovedRoleEvaluationVersionId(null);
        snapshot.setApprovedRoleEvaluationVersionNumber(null);

        ScoreTraceabilityStatus status = ScoreTraceabilityStatus.resolve(snapshot);
        assertThat(status).isEqualTo(ScoreTraceabilityStatus.DRAFT_BASED);
    }

    @Test
    @DisplayName("Old async snapshots with null version linkage resolve to LEGACY_PARTIAL")
    void oldAsyncSnapshotsResolveToLegacyPartial() {
        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setEvaluatedRole(CompanyRole.POTENTIAL_PARTNER);
        snapshot.setApprovedRoleEvaluationVersionId(null);
        snapshot.setApprovedRoleEvaluationVersionNumber(null);
        snapshot.setEvidenceRefsJson("{\"stratFit\": [\"ref-1\"]}");

        ScoreTraceabilityStatus status = ScoreTraceabilityStatus.resolve(snapshot);
        assertThat(status).isEqualTo(ScoreTraceabilityStatus.LEGACY_PARTIAL);
    }

    @Test
    @DisplayName("Version linkage without evidence resolves to IMMUTABLE_WITHOUT_EVIDENCE")
    void versionLinkageWithoutEvidenceResolvesToImmutableWithoutEvidence() {
        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setEvaluatedRole(CompanyRole.CUSTOMER);
        snapshot.setApprovedRoleEvaluationVersionId("ver-abc");
        snapshot.setApprovedRoleEvaluationVersionNumber(2);

        // No evidence
        snapshot.setEvidenceRefsJson(null);
        ScoreTraceabilityStatus status1 = ScoreTraceabilityStatus.resolve(snapshot);
        assertThat(status1).isEqualTo(ScoreTraceabilityStatus.IMMUTABLE_WITHOUT_EVIDENCE);

        // Empty evidence JSON object
        snapshot.setEvidenceRefsJson("{}");
        ScoreTraceabilityStatus status2 = ScoreTraceabilityStatus.resolve(snapshot);
        assertThat(status2).isEqualTo(ScoreTraceabilityStatus.IMMUTABLE_WITHOUT_EVIDENCE);
    }

    @Test
    @DisplayName("Version linkage with evidence resolves to IMMUTABLE_COMPLETE")
    void versionLinkageWithEvidenceResolvesToImmutableComplete() {
        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setEvaluatedRole(CompanyRole.SUPPLIER);
        snapshot.setApprovedRoleEvaluationVersionId("ver-xyz");
        snapshot.setApprovedRoleEvaluationVersionNumber(3);
        snapshot.setEvidenceRefsJson("{\"costRisk\": [\"ref-123\", \"ref-456\"]}");

        ScoreTraceabilityStatus status = ScoreTraceabilityStatus.resolve(snapshot);
        assertThat(status).isEqualTo(ScoreTraceabilityStatus.IMMUTABLE_COMPLETE);
    }

    @Test
    @DisplayName("targetProfileVersion and referenceProfileVersion remain structurally separate from RoleEvaluationVersionNumber")
    void profileVersionsRemainSeparate() {
        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setTargetProfileVersion(10);
        snapshot.setReferenceProfileVersion(20);
        snapshot.setApprovedRoleEvaluationVersionNumber(5);

        assertThat(snapshot.getTargetProfileVersion()).isEqualTo(10);
        assertThat(snapshot.getReferenceProfileVersion()).isEqualTo(20);
        assertThat(snapshot.getApprovedRoleEvaluationVersionNumber()).isEqualTo(5);
        assertThat(snapshot.getTargetProfileVersion()).isNotEqualTo(snapshot.getApprovedRoleEvaluationVersionNumber());
    }
}
