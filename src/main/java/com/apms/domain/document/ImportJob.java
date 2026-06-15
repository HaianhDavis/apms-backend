package com.apms.domain.document;

import com.apms.common.enums.ImportJobStatus;
import com.apms.common.enums.InputType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "import_jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * FK reference to SQL Server projects.id
     */
    @Column(nullable = false)
    private Long projectId;

    /**
     * MongoDB ObjectId of the linked RawDocument.
     * Set after RawDocument is created.
     */
    private String rawDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InputType inputType;

    /**
     * Detected file type: PDF, DOCX, DOC, TXT, EXCEL, CSV, MANUAL, OTHER.
     */
    private String sourceType;

    /**
     * Original file name. Null for MANUAL_INPUT.
     */
    private String fileName;

    /**
     * Relative path in uploads/ directory. Null for MANUAL_INPUT.
     */
    private String localFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ImportJobStatus status = ImportJobStatus.PENDING;

    /**
     * ID of the User who uploaded/submitted this document.
     */
    @Column(nullable = false)
    private Long uploadedBy;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String errorMessage;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
