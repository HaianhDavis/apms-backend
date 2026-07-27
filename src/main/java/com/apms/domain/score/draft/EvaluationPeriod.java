package com.apms.domain.score.draft;

import com.apms.domain.score.enums.EvaluationPeriodType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationPeriod {
    private EvaluationPeriodType type;
    private LocalDate asOfDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;

    public void validate() {
        if (type == null) {
            throw new com.apms.common.exception.BusinessValidationException("EvaluationPeriodType is required");
        }

        switch (type) {
            case AS_OF_DATE:
                if (asOfDate == null) {
                    throw new com.apms.common.exception.BusinessValidationException("asOfDate is required for AS_OF_DATE period type");
                }
                if (periodStart != null || periodEnd != null) {
                    throw new com.apms.common.exception.BusinessValidationException("periodStart and periodEnd must be null for AS_OF_DATE period type");
                }
                break;
            case ANNUAL:
                if (periodStart == null || periodEnd == null) {
                    throw new com.apms.common.exception.BusinessValidationException("periodStart and periodEnd are required for ANNUAL period type");
                }
                if (periodStart.getDayOfYear() != 1 || periodEnd.getDayOfYear() != periodEnd.lengthOfYear() || periodStart.getYear() != periodEnd.getYear()) {
                    throw new com.apms.common.exception.BusinessValidationException("Dates must form exactly one calendar year for ANNUAL period type");
                }
                break;
            case QUARTERLY:
                if (periodStart == null || periodEnd == null) {
                    throw new com.apms.common.exception.BusinessValidationException("periodStart and periodEnd are required for QUARTERLY period type");
                }
                if (periodStart.getYear() != periodEnd.getYear()) {
                    throw new com.apms.common.exception.BusinessValidationException("Dates must be within the same year for QUARTERLY period type");
                }
                int startMonth = periodStart.getMonthValue();
                int endMonth = periodEnd.getMonthValue();
                if ((startMonth - 1) / 3 != (endMonth - 1) / 3 || periodStart.getDayOfMonth() != 1 || periodEnd.getDayOfMonth() != periodEnd.lengthOfMonth() || (endMonth - startMonth != 2)) {
                    throw new com.apms.common.exception.BusinessValidationException("Dates must form exactly one calendar quarter for QUARTERLY period type");
                }
                break;
            case CUSTOM:
                if (periodStart == null || periodEnd == null) {
                    throw new com.apms.common.exception.BusinessValidationException("periodStart and periodEnd are required for CUSTOM period type");
                }
                if (periodStart.isAfter(periodEnd)) {
                    throw new com.apms.common.exception.BusinessValidationException("periodStart cannot be after periodEnd for CUSTOM period type");
                }
                break;
        }
    }
}
