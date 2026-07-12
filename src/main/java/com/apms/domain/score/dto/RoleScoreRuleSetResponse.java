package com.apms.domain.score.dto;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.ScoreDirection;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.enums.WeightingMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class RoleScoreRuleSetResponse {
    private Long id;
    private CompanyRole evaluatedRole;
    private String ruleSetVersion;
    private WeightingMethod weightingMethod;
    private WeightSource weightSource;
    private String weightVersion;
    private Boolean active;
    private List<CriterionRuleResponse> criteria;

    @Data
    @Builder
    public static class CriterionRuleResponse {
        private String criterionKey;
        private String criterionName;
        private BigDecimal weight;
        private ScoreDirection direction;
        private Boolean required;
        private Integer displayOrder;
    }
}
