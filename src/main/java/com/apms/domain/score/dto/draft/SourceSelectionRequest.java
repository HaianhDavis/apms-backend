package com.apms.domain.score.dto.draft;

import com.apms.domain.score.enums.ApprovedSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceSelectionRequest {

    @NotNull(message = "sourceType is required")
    private ApprovedSourceType sourceType;

    private Long sqlSourceId;

    private String mongoSourceId;

    @NotNull(message = "criterionKey is required")
    private String criterionKey;
}
