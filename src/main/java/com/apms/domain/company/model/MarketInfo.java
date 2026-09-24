package com.apms.domain.company.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketInfo {
    private BigDecimal marketShare;
    private Integer brandRank;
    private Long clientCount;
    private List<String> mainMarkets;
}
