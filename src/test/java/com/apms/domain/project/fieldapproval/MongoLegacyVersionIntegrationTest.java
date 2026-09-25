//package com.apms.domain.project.fieldapproval;
//
//import com.apms.ApmsIntegrationTestBase;
//import com.apms.domain.candidate.CompanyCandidate;
//import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
//import com.apms.domain.profile.CompanyProfileUpdateProposal;
//import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
//import org.bson.Document;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.mongodb.core.MongoTemplate;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//public class MongoLegacyVersionIntegrationTest extends ApmsIntegrationTestBase {
//
//    @Autowired
//    private MongoTemplate mongoTemplate;
//
//    @Autowired
//    private CompanyCandidateRepository candidateRepository;
//
//    @Autowired
//    private CompanyProfileUpdateProposalRepository proposalRepository;
//
//    @Test
//    void testLegacyCandidateWithoutDocumentVersionIsSafeToUpdate() {
//        // 1. Insert a legacy document directly without documentVersion
//        Document legacyDoc = new Document();
//        legacyDoc.put("_id", "legacy_candidate_1");
//        legacyDoc.put("projectId", "100");
//        legacyDoc.put("revisionNumber", 1);
//        // Notice: no documentVersion field
//        mongoTemplate.getCollection("company_candidates").insertOne(legacyDoc);
//
//        // 2. Load it through Repository
//        CompanyCandidate candidate = candidateRepository.findById("legacy_candidate_1").orElseThrow();
//        assertThat(candidate.getDocumentVersion()).isNull();
//
//        // 3. Attempting to save it with null documentVersion will throw DuplicateKeyException
//        // because Spring Data thinks it's a new entity and tries to insert instead of update.
//        candidate.setRevisionNumber(2);
//        org.junit.jupiter.api.Assertions.assertThrows(
//                org.springframework.dao.DuplicateKeyException.class,
//                () -> candidateRepository.save(candidate)
//        );
//
//        // 4. Simulate the manual backfill script
//        mongoTemplate.getCollection("company_candidates").updateOne(
//                new Document("_id", "legacy_candidate_1"),
//                new Document("$set", new Document("documentVersion", 0L))
//        );
//
//        // 5. Now it loads with version 0 and saves successfully
//        CompanyCandidate backfilled = candidateRepository.findById("legacy_candidate_1").orElseThrow();
//        assertThat(backfilled.getDocumentVersion()).isEqualTo(0L);
//
//        backfilled.setRevisionNumber(3);
//        CompanyCandidate saved = candidateRepository.save(backfilled);
//        assertThat(saved.getDocumentVersion()).isEqualTo(1L);
//    }
//
//    @Test
//    void testLegacyProposalWithoutDocumentVersionIsSafeToUpdate() {
//        // 1. Insert a legacy document directly without documentVersion
//        Document legacyDoc = new Document();
//        legacyDoc.put("_id", "legacy_proposal_1");
//        legacyDoc.put("projectId", 100L);
//        legacyDoc.put("revisionNumber", 1);
//        // Notice: no documentVersion field
//        mongoTemplate.getCollection("company_profile_update_proposals").insertOne(legacyDoc);
//
//        // 2. Load it through Repository
//        CompanyProfileUpdateProposal proposal = proposalRepository.findById("legacy_proposal_1").orElseThrow();
//        assertThat(proposal.getDocumentVersion()).isNull();
//
//        // 3. Attempting to save it fails due to DuplicateKeyException
//        proposal.setRevisionNumber(2);
//        org.junit.jupiter.api.Assertions.assertThrows(
//                org.springframework.dao.DuplicateKeyException.class,
//                () -> proposalRepository.save(proposal)
//        );
//
//        // 4. Simulate manual backfill
//        mongoTemplate.getCollection("company_profile_update_proposals").updateOne(
//                new Document("_id", "legacy_proposal_1"),
//                new Document("$set", new Document("documentVersion", 0L))
//        );
//
//        // 5. Load and save succeeds
//        CompanyProfileUpdateProposal backfilled = proposalRepository.findById("legacy_proposal_1").orElseThrow();
//        backfilled.setRevisionNumber(3);
//        CompanyProfileUpdateProposal saved = proposalRepository.save(backfilled);
//        assertThat(saved.getDocumentVersion()).isEqualTo(1L);
//    }
//    @Test
//    void testCandidateDotPathPersistence() {
//        CompanyCandidate candidate = new CompanyCandidate();
//        candidate.setProjectId("proj1");
//
//        java.util.List<FieldApprovalRecord> approvals = new java.util.ArrayList<>();
//        approvals.add(FieldApprovalRecord.builder().fieldPath("identity.legalName").status(com.apms.common.enums.FieldApprovalStatus.APPROVED).build());
//        approvals.add(FieldApprovalRecord.builder().fieldPath("contact.website").status(com.apms.common.enums.FieldApprovalStatus.APPROVED).build());
//        approvals.add(FieldApprovalRecord.builder().fieldPath("business.products").status(com.apms.common.enums.FieldApprovalStatus.APPROVED).build());
//
//        candidate.setFieldApprovals(approvals);
//        candidate = candidateRepository.save(candidate);
//
//        CompanyCandidate loaded = candidateRepository.findById(candidate.getId()).orElseThrow();
//        assertThat(loaded.getFieldApprovals()).hasSize(3);
//        assertThat(loaded.getFieldApprovals()).extracting("fieldPath")
//                .containsExactlyInAnyOrder("identity.legalName", "contact.website", "business.products");
//    }
//
//    @Test
//    void testProposalDotPathPersistence() {
//        CompanyProfileUpdateProposal proposal = CompanyProfileUpdateProposal.builder()
//                .projectId(100L)
//                .build();
//
//        java.util.List<FieldApprovalRecord> approvals = new java.util.ArrayList<>();
//        approvals.add(FieldApprovalRecord.builder().fieldPath("identity.legalName").status(com.apms.common.enums.FieldApprovalStatus.APPROVED).build());
//        approvals.add(FieldApprovalRecord.builder().fieldPath("contact.website").status(com.apms.common.enums.FieldApprovalStatus.APPROVED).build());
//        approvals.add(FieldApprovalRecord.builder().fieldPath("business.products").status(com.apms.common.enums.FieldApprovalStatus.APPROVED).build());
//
//        proposal.setFieldApprovals(approvals);
//        proposal = proposalRepository.save(proposal);
//
//        CompanyProfileUpdateProposal loaded = proposalRepository.findById(proposal.getId()).orElseThrow();
//        assertThat(loaded.getFieldApprovals()).hasSize(3);
//        assertThat(loaded.getFieldApprovals()).extracting("fieldPath")
//                .containsExactlyInAnyOrder("identity.legalName", "contact.website", "business.products");
//    }
//
//    @Test
//    void testCandidateMissingFieldApprovals() {
//        Document legacyDoc = new Document();
//        legacyDoc.put("_id", "legacy_cand_null_approvals");
//        legacyDoc.put("projectId", "100");
//        legacyDoc.put("documentVersion", 0L);
//        // fieldApprovals is missing
//        mongoTemplate.getCollection("company_candidates").insertOne(legacyDoc);
//
//        CompanyCandidate loaded = candidateRepository.findById("legacy_cand_null_approvals").orElseThrow();
//        assertThat(loaded.getFieldApprovals()).isNull();
//
//        // Service should treat it as empty
//        java.util.Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(loaded.getFieldApprovals());
//        assertThat(map).isEmpty();
//    }
//
//    @Test
//    void testProposalMissingFieldApprovals() {
//        Document legacyDoc = new Document();
//        legacyDoc.put("_id", "legacy_prop_null_approvals");
//        legacyDoc.put("projectId", 100L);
//        legacyDoc.put("documentVersion", 0L);
//        // fieldApprovals is missing
//        mongoTemplate.getCollection("company_profile_update_proposals").insertOne(legacyDoc);
//
//        CompanyProfileUpdateProposal loaded = proposalRepository.findById("legacy_prop_null_approvals").orElseThrow();
//        assertThat(loaded.getFieldApprovals()).isNull();
//
//        // Service should treat it as empty
//        java.util.Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(loaded.getFieldApprovals());
//        assertThat(map).isEmpty();
//    }
//}
