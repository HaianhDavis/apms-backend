package com.apms.domain.score.draft;

import com.apms.domain.score.enums.CriterionInputMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriterionInput {

    private String criterionKey;

    private BigDecimal rawScore;

    private CriterionInputMethod inputMethod;

    private String explanation;

    @Builder.Default
    private List<String> evidenceIds = new ArrayList<>();

    private Long preparedByAccountId;
    private LocalDateTime preparedAt;

    private Long reviewedByAccountId;
    private LocalDateTime reviewedAt;

    @Builder.Default
    private Boolean managerConfirmed = false;

    private BigDecimal previousValue;
    private String overrideReason;
}
