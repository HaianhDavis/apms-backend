package com.apms.common.enums;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    RETRY,
    PROCESSED,
    DEAD_LETTER
}
