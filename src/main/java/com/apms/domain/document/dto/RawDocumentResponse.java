package com.apms.domain.document.dto;

import com.apms.domain.document.RawDocument;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RawDocumentResponse {
    private String id;
    private String projectId;
    private String importJobId;
    private RawDocument.Source source;
    private RawDocument.Storage storage;
    private RawDocument.Processing processing;
    private RawDocument.Metadata metadata;
}
