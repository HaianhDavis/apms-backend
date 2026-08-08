package com.apms.domain.listing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingTabResponse<T> {
    private boolean hasData;
    private LocalDateTime crawledAt;
    private T data;
}
