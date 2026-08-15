package com.apms.domain.security.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Profile("prod")
public class UnavailableOtpDeliveryProvider  {
}
