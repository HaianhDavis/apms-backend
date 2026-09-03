package com.apms.domain.candidate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Tracks the monotonically increasing draft sequence for a given task ID.
 * Scoped to projectTaskId. Ensures sequences are not reused even if drafts are deleted.
 */
@Document(collection = "candidate_draft_sequences")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateDraftSequence {

    @Id
    private String id; // taskId as String

    private Integer currentSequence;
}
