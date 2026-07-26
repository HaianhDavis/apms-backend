package com.apms.domain.score.dto.draft;

import com.apms.domain.score.enums.CriterionInputMethod;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpdateCriterionInputRequest {

    private BigDecimal rawScore; // 0 to 100

    private String explanation;

    private List<String> evidenceIds;

    private CriterionInputMethod inputMethod;
}
