package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class IpWhitelistResponse {
    private List<IpWhitelistEntryDto> entries;
    private Boolean enabled;
}
