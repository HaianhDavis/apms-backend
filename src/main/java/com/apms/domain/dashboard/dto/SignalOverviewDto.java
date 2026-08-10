package com.apms.domain.dashboard.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SignalOverviewDto {
    private long totalCount;
    private List<SignalEntryDto> recentSignals;
}
