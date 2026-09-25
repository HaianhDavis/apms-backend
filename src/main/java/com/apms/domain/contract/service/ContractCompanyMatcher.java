package com.apms.domain.contract.service;

import com.apms.domain.ai.service.CompanyNameNormalizer;
import com.apms.domain.contract.enums.CompanyMatchStatus;
import com.apms.domain.contract.model.ContractParty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ContractCompanyMatcher {

    private final CompanyNameNormalizer companyNameNormalizer;

    public CompanyMatchStatus evaluateCompanyMatch(String targetCompanyName, List<ContractParty> parties) {
        if (!StringUtils.hasText(targetCompanyName)) {
            return CompanyMatchStatus.UNKNOWN;
        }

        if (parties == null || parties.isEmpty()) {
            return CompanyMatchStatus.UNKNOWN;
        }

        boolean hasPossibleMatch = false;

        for (ContractParty party : parties) {
            String partyName = party.getLegalName();
            if (!StringUtils.hasText(partyName)) {
                continue;
            }

            if (companyNameNormalizer.isSameCompany(partyName, targetCompanyName)) {
                return CompanyMatchStatus.MATCH;
            }

            String normParty = companyNameNormalizer.normalize(partyName).toLowerCase();
            String normTarget = companyNameNormalizer.normalize(targetCompanyName).toLowerCase();

            if (normParty.contains(normTarget) || normTarget.contains(normParty)) {
                hasPossibleMatch = true;
            }
        }

        if (hasPossibleMatch) {
            return CompanyMatchStatus.POSSIBLE_MATCH;
        }

        return CompanyMatchStatus.MISMATCH;
    }
}
