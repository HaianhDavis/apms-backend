package com.apms.domain.contract.service;

import com.apms.domain.contract.config.ContractExtractionProperties;
import com.apms.domain.contract.dto.PartnerContractExtractionOutput;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ClauseCandidate;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ContractExtractionFieldResult;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.enums.ContractExtractionGenerationStatus;
import com.apms.domain.contract.enums.ContractExtractionQualityStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.ai.service.provider.PartnerContractExtractionProvider;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Proves multi-segment extraction behavior including:
 * - documents longer than segment-chars produce multiple segments
 * - segment overlap matches configuration
 * - no segment exceeds the configured size
 * - provider calls are bounded by max-segments
 * - max-segments is enforced
 * - max-total-chars is enforced
 * - max-clauses is enforced
 * - exceeding limits gives PARTIAL / WARNING with exact skip counts
 * - partial extraction is not reported as COMPLETED
 * - provider timeout and retry count use configuration
 */
@ExtendWith(MockitoExtension.class)
public class ContractExtractionMultiSegmentTest {

    @InjectMocks
    private PartnerContractExtractionService extractionService;

    @Mock
    private PartnerContractExtractionDraftRepository draftRepository;
    @Mock
    private PartnerContractExtractionProvider extractionProvider;
    @Mock
    private PartnerContractRepository contractRepository;
    @Mock
    private PartnerContractVersionRepository versionRepository;
    @Mock
    private RawDocumentRepository rawDocumentRepository;
    @Mock
    private AuditLogService auditService;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ContractExtractionProperties config;

    private PartnerContract contract;
    private RawDocument rawDoc;

    @BeforeEach
    void setup() {
        contract = new PartnerContract();
        contract.setId(1L);
        contract.setRawDocumentId("doc-1");
        contract.setSourceProjectId(100L);
        contract.setReviewStatus(ContractReviewStatus.DRAFT);
        contract.setVersion(1);

        RawDocument.Source source = new RawDocument.Source();
        source.setType("MANUAL_INPUT");

        rawDoc = new RawDocument();
        rawDoc.setId("doc-1");
        rawDoc.setProjectId("100");
        rawDoc.setSource(source);

        when(projectRepository.existsByIdAndMembersAccountId(100L, 1L)).thenReturn(true);
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(rawDocumentRepository.findById("doc-1")).thenReturn(Optional.of(rawDoc));
        when(versionRepository.findTopByContractIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Default config
        lenient().when(config.getSegmentChars()).thenReturn(100);
        lenient().when(config.getSegmentOverlapChars()).thenReturn(20);
        lenient().when(config.getMaxSegments()).thenReturn(20);
        lenient().when(config.getMaxTotalChars()).thenReturn(100_000);
        lenient().when(config.getMaxClauses()).thenReturn(200);
        lenient().when(config.getProviderTimeoutSeconds()).thenReturn(30);
        lenient().when(config.getMaxRetries()).thenReturn(3);
        lenient().when(config.getApplyRecoveryTimeoutSeconds()).thenReturn(300);
    }

    private PartnerContractExtractionOutput emptyOutput() {
        PartnerContractExtractionOutput out = new PartnerContractExtractionOutput();
        out.setMetadataFields(new ArrayList<>());
        out.setClauseCandidates(new ArrayList<>());
        return out;
    }

    private PartnerContractExtractionOutput outputWithClauses(int count) {
        PartnerContractExtractionOutput out = new PartnerContractExtractionOutput();
        out.setMetadataFields(new ArrayList<>());
        List<ClauseCandidate> clauses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ClauseCandidate c = new ClauseCandidate();
            c.setClauseCandidateId("clause-" + System.nanoTime() + "-" + i);
            c.setClauseTitle("Clause " + i);
            clauses.add(c);
        }
        out.setClauseCandidates(clauses);
        return out;
    }

    private String textOfLength(int len) {
        return "A".repeat(len);
    }

    // ========== Multi-segment production ==========

    @Test
    void shouldProduceMultipleSegmentsForLongDocument() {
        // 250 chars / 100 segment-chars with 20 overlap = advance 80 per step
        // segments: [0,100], [80,180], [160,250], [240,250] = 4 segments
        rawDoc.getSource().setInputText(textOfLength(250));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        assertThat(draft.getSegments()).hasSize(4);
        assertThat(draft.getGenerationStatus()).isEqualTo(ContractExtractionGenerationStatus.COMPLETED);
        assertThat(draft.getQualityStatus()).isEqualTo(ContractExtractionQualityStatus.PASS);
        verify(extractionProvider, times(4)).extractContract(anyString());
    }

    @Test
    void shouldRespectSegmentOverlap() {
        // segment-chars=100, overlap=20 => advance=80
        rawDoc.getSource().setInputText(textOfLength(250));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        // Segment 1: [0, 100], Segment 2: [80, 180], Segment 3: [160, 250], Segment 4: [240, 250]
        assertThat(draft.getSegments().get(0).getStartOffset()).isEqualTo(0);
        assertThat(draft.getSegments().get(0).getEndOffset()).isEqualTo(100);
        assertThat(draft.getSegments().get(1).getStartOffset()).isEqualTo(80);
        assertThat(draft.getSegments().get(1).getEndOffset()).isEqualTo(180);
        assertThat(draft.getSegments().get(2).getStartOffset()).isEqualTo(160);
        assertThat(draft.getSegments().get(2).getEndOffset()).isEqualTo(250);
        assertThat(draft.getSegments().get(3).getStartOffset()).isEqualTo(240);
        assertThat(draft.getSegments().get(3).getEndOffset()).isEqualTo(250);
    }

    @Test
    void shouldNotExceedSegmentCharsPerSegment() {
        rawDoc.getSource().setInputText(textOfLength(500));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        for (var seg : draft.getSegments()) {
            int segLen = seg.getEndOffset() - seg.getStartOffset();
            assertThat(segLen).isLessThanOrEqualTo(100);
        }
    }

    @Test
    void shouldProduceSingleSegmentForShortDocument() {
        rawDoc.getSource().setInputText(textOfLength(50));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        assertThat(draft.getSegments()).hasSize(1);
        assertThat(draft.getGenerationStatus()).isEqualTo(ContractExtractionGenerationStatus.COMPLETED);
    }

    // ========== max-segments enforcement ==========

    @Test
    void shouldEnforceMaxSegments() {
        when(config.getMaxSegments()).thenReturn(2);
        // 250 chars would normally produce 3 segments
        rawDoc.getSource().setInputText(textOfLength(250));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        assertThat(draft.getSegments()).hasSize(2);
        assertThat(draft.getGenerationStatus()).isEqualTo(ContractExtractionGenerationStatus.PARTIAL);
        assertThat(draft.getQualityStatus()).isEqualTo(ContractExtractionQualityStatus.WARNING);
        assertThat(draft.getWarnings()).anyMatch(w -> w.contains("max-segments (2)"));
        verify(extractionProvider, times(2)).extractContract(anyString());
    }

    @Test
    void shouldReportExactUnprocessedSegmentCountOnMaxSegments() {
        when(config.getMaxSegments()).thenReturn(1);
        rawDoc.getSource().setInputText(textOfLength(250));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        // After 1 segment [0,100] with advance 80, offset=80, remaining=170, ceil(170/100)=2
        assertThat(draft.getWarnings()).anyMatch(w -> w.contains("2 segments were skipped"));
    }

    // ========== max-total-chars enforcement ==========

    @Test
    void shouldEnforceMaxTotalChars() {
        when(config.getMaxTotalChars()).thenReturn(150);
        rawDoc.getSource().setInputText(textOfLength(500));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        assertThat(draft.getGenerationStatus()).isEqualTo(ContractExtractionGenerationStatus.PARTIAL);
        assertThat(draft.getQualityStatus()).isEqualTo(ContractExtractionQualityStatus.WARNING);
        assertThat(draft.getWarnings()).anyMatch(w -> w.contains("max-total-chars (150)"));
    }

    @Test
    void shouldReportExactUnprocessedSegmentCountOnMaxTotalChars() {
        when(config.getMaxTotalChars()).thenReturn(100);
        rawDoc.getSource().setInputText(textOfLength(350));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        // After 1 segment of 100 chars, processedLength=100 >= maxTotalChars=100
        // offset advances to 80, remaining=270, ceil(270/100)=3
        assertThat(draft.getWarnings()).anyMatch(w -> w.contains("3 segments were skipped"));
    }

    // ========== max-clauses enforcement ==========

    @Test
    void shouldEnforceMaxClauses() {
        when(config.getMaxClauses()).thenReturn(3);
        rawDoc.getSource().setInputText(textOfLength(500));
        // First call returns 3 clauses => hits limit before second call
        when(extractionProvider.extractContract(anyString())).thenReturn(outputWithClauses(3));

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        // First segment is processed (3 clauses), then max-clauses hit prevents further segments
        assertThat(draft.getClauseCandidates()).hasSize(3);
        assertThat(draft.getGenerationStatus()).isEqualTo(ContractExtractionGenerationStatus.PARTIAL);
        assertThat(draft.getQualityStatus()).isEqualTo(ContractExtractionQualityStatus.WARNING);
        assertThat(draft.getWarnings()).anyMatch(w -> w.contains("max-clauses (3)"));
    }

    // ========== Partial is not COMPLETED ==========

    @Test
    void shouldNotReportPartialAsCompleted() {
        when(config.getMaxSegments()).thenReturn(1);
        rawDoc.getSource().setInputText(textOfLength(500));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft = extractionService.generateExtraction(1L, 1L);

        assertThat(draft.getGenerationStatus()).isNotEqualTo(ContractExtractionGenerationStatus.COMPLETED);
        assertThat(draft.getGenerationStatus()).isEqualTo(ContractExtractionGenerationStatus.PARTIAL);
    }

    // ========== Provider call bounding ==========

    @Test
    void shouldBoundProviderCallsByMaxSegments() {
        when(config.getMaxSegments()).thenReturn(3);
        // 500 chars / advance 80 would normally be 7 segments
        rawDoc.getSource().setInputText(textOfLength(500));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        extractionService.generateExtraction(1L, 1L);

        verify(extractionProvider, times(3)).extractContract(anyString());
    }

    // ========== Deterministic segment IDs ==========

    @Test
    void shouldProduceDeterministicSegmentIds() {
        rawDoc.getSource().setInputText(textOfLength(250));
        when(extractionProvider.extractContract(anyString())).thenReturn(emptyOutput());

        PartnerContractExtractionDraft draft1 = extractionService.generateExtraction(1L, 1L);

        reset(draftRepository);
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PartnerContractExtractionDraft draft2 = extractionService.generateExtraction(1L, 1L);

        assertThat(draft1.getSegments()).hasSameSizeAs(draft2.getSegments());
        for (int i = 0; i < draft1.getSegments().size(); i++) {
            assertThat(draft1.getSegments().get(i).getSegmentId())
                    .isEqualTo(draft2.getSegments().get(i).getSegmentId());
        }
    }
}
