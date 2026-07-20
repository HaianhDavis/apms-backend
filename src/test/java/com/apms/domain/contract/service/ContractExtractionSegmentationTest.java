package com.apms.domain.contract.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.config.ContractExtractionProperties;
import com.apms.domain.contract.dto.ContractDocumentSegment;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.document.RawDocument;

import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.DigestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ContractExtractionSegmentationTest {

    @InjectMocks
    private PartnerContractExtractionService extractionService;

    @Mock
    private RawDocumentRepository rawDocumentRepository;

    @Mock
    private ContractExtractionProperties config;

    private PartnerContract contract;
    private RawDocument rawDoc;
    private String sourceText;
    private String docHash;

    @BeforeEach
    void setup() {
        contract = new PartnerContract();
        contract.setRawDocumentId("doc-123");
        contract.setSourceProjectId(999L);

        sourceText = "This is a mock contract document text for segmentation testing purposes.";
        docHash = DigestUtils.md5DigestAsHex(sourceText.getBytes());

        RawDocument.Storage storage = new RawDocument.Storage();
        storage.setChecksum(docHash);

        RawDocument.Source source = new RawDocument.Source();
        source.setInputText(sourceText);
        source.setType("MANUAL_INPUT");

        rawDoc = new RawDocument();
        rawDoc.setId("doc-123");
        rawDoc.setStorage(storage);
        rawDoc.setSource(source);
        rawDoc.setProjectId("999");
    }

    @Test
    void shouldProduceSameSegmentIdForSameOffsets() {
        String id1 = extractionService.generateSegmentId(docHash, 0, 10);
        String id2 = extractionService.generateSegmentId(docHash, 0, 10);
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void shouldProduceDifferentSegmentIdForDifferentOffsets() {
        String id1 = extractionService.generateSegmentId(docHash, 0, 10);
        String id2 = extractionService.generateSegmentId(docHash, 0, 11);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void shouldValidateValidSegment() {
        when(rawDocumentRepository.findById("doc-123")).thenReturn(Optional.of(rawDoc));

        String segmentId = extractionService.generateSegmentId(docHash, 0, 20);
        String excerpt = sourceText.substring(0, 20);

        ContractDocumentSegment segment = extractionService.validateSegment(
                contract, segmentId, 0, 20, excerpt, docHash, "doc-123", "999"
        );

        assertThat(segment.getSegmentId()).isEqualTo(segmentId);
        assertThat(segment.getExcerptHash()).isEqualTo(DigestUtils.md5DigestAsHex(excerpt.getBytes()));
    }

    @Test
    void shouldRejectInvalidOffsets() {
        when(rawDocumentRepository.findById("doc-123")).thenReturn(Optional.of(rawDoc));
        String segmentId = extractionService.generateSegmentId(docHash, -1, 10);

        assertThatThrownBy(() -> extractionService.validateSegment(contract, segmentId, -1, 10, "abc", docHash, "doc-123", "999"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Invalid segment offsets");

        assertThatThrownBy(() -> extractionService.validateSegment(contract, segmentId, 10, 5, "abc", docHash, "doc-123", "999"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Invalid segment offsets");
    }

    @Test
    void shouldRejectUnknownSegmentId() {
        when(rawDocumentRepository.findById("doc-123")).thenReturn(Optional.of(rawDoc));
        String wrongSegmentId = "bad-id";
        String excerpt = sourceText.substring(0, 20);

        assertThatThrownBy(() -> extractionService.validateSegment(contract, wrongSegmentId, 0, 20, excerpt, docHash, "doc-123", "999"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Unknown segment reference");
    }

    @Test
    void shouldRejectWrongRawDocument() {
        contract.setRawDocumentId("other-doc");
        assertThatThrownBy(() -> extractionService.validateSegment(contract, "id", 0, 10, "exc", docHash, "doc-123", "999"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Segment RawDocument ID mismatch");
    }

    @Test
    void shouldRejectWrongProject() {
        contract.setSourceProjectId(888L);
        assertThatThrownBy(() -> extractionService.validateSegment(contract, "id", 0, 10, "exc", docHash, "doc-123", "999"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Segment Project mismatch");
    }

    @Test
    void shouldRejectExcerptMismatch() {
        when(rawDocumentRepository.findById("doc-123")).thenReturn(Optional.of(rawDoc));
        String segmentId = extractionService.generateSegmentId(docHash, 0, 20);
        String wrongExcerpt = "This is a wrong excr";

        assertThatThrownBy(() -> extractionService.validateSegment(contract, segmentId, 0, 20, wrongExcerpt, docHash, "doc-123", "999"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Segment excerpt mismatch");
    }

    @Test
    void shouldRejectChangedDocumentHash() {
        when(rawDocumentRepository.findById("doc-123")).thenReturn(Optional.of(rawDoc));
        String oldHash = "old-hash";
        String segmentId = extractionService.generateSegmentId(docHash, 0, 20);
        String excerpt = sourceText.substring(0, 20);

        assertThatThrownBy(() -> extractionService.validateSegment(contract, segmentId, 0, 20, excerpt, oldHash, "doc-123", "999"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Source document hash mismatch. The document has changed.");
    }
}
