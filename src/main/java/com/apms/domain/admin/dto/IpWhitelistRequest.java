package com.apms.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class IpWhitelistRequest {

    @NotBlank(message = "IP address is required")
    @Pattern(
            regexp = "^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(\\/([0-9]|[1-2][0-9]|3[0-2]))?$",
            message = "Must be a valid IPv4 address or CIDR notation (e.g. 192.168.1.1 or 10.0.0.0/24)"
    )
    private String ipAddress;

    private String description;

    private Boolean enabled = true;
}
