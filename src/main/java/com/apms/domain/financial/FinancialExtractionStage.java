package com.apms.domain.financial;

public enum FinancialExtractionStage {
    QUEUED,
    PARSING_DOCUMENT,
    EXTRACTING_METRICS,
    VALIDATING_RESULTS,
    SAVING_RESULTS,
    COMPLETED,
    FAILED
}
