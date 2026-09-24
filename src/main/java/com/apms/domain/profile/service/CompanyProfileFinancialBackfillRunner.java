package com.apms.domain.profile.service;

import com.apms.domain.financial.FinancialResearch;
import com.apms.domain.financial.FinancialResearchStatus;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup runner to backfill canonical CompanyProfileFinancialRows from any legacy approved FinancialResearch.
 * Strictly idempotent: will skip any metrics already promoted.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class CompanyProfileFinancialBackfillRunner implements CommandLineRunner {

    private final FinancialResearchRepository researchRepository;
    private final CompanyProfileFinancialService financialService;

    @Override
    public void run(String... args) {
        try {
            List<FinancialResearch> approvedResearchList = researchRepository.findByStatus(FinancialResearchStatus.APPROVED);
            if (approvedResearchList == null || approvedResearchList.isEmpty()) {
                log.info("No approved FinancialResearch records found for canonical financial backfill.");
                return;
            }

            int totalPromoted = 0;
            for (FinancialResearch research : approvedResearchList) {
                totalPromoted += financialService.promoteFromApprovedResearch(research);
            }

            if (totalPromoted > 0) {
                log.info("CompanyProfileFinancialBackfillRunner backfilled {} canonical financial rows from {} approved research records.",
                        totalPromoted, approvedResearchList.size());
            } else {
                log.info("CompanyProfileFinancialBackfillRunner: All approved research records already promoted.");
            }
        } catch (Exception e) {
            log.warn("CompanyProfileFinancialBackfillRunner encountered an error during startup backfill: {}", e.getMessage());
        }
    }
}
