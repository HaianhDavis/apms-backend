package com.apms.domain.contract.enums;

public enum ContractExtractionStage {
    QUEUED,
    PARSING_DOCUMENT,
    CLASSIFYING_CONTRACT,
    EXTRACTING_FIELDS,
    VALIDATING_RESULTS,
    SAVING_RESULTS
}
