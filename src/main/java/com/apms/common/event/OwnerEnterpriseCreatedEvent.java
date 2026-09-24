package com.apms.common.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OwnerEnterpriseCreatedEvent implements DomainEvent {

    private final String companyProfileId;
    private final String companyId;
}
