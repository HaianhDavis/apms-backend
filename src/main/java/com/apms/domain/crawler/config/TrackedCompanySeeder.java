package com.apms.domain.crawler.config;

import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps the crawler focused on the configured target companies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackedCompanySeeder implements CommandLineRunner {

    private final TrackedCompanyRepository trackedCompanyRepository;

    @Override
    public void run(String... args) {
        List<TrackedCompany> targets = List.of(
                TrackedCompany.builder()
                        .companyName("FPT")
                        .aliases(List.of("FPT Corporation", "FPT Software", "FPT Telecom", "FPT IS"))
                        .subsidiaries(List.of("FPT Software", "FPT Telecom", "FPT Information System", "FPT Education", "FPT Retail"))
                        .products(List.of("FPT.AI", "FPT Cloud", "FPT Play", "FPT Shop"))
                        .keyPeople(List.of("Truong Gia Binh", "Nguyen Van Khoa"))
                        .industry("Technology")
                        .isActive(true)
                        .build(),
                TrackedCompany.builder()
                        .companyName("Viettel")
                        .aliases(List.of("Viettel Group", "Tap doan Viettel", "Viettel Military Industry"))
                        .subsidiaries(List.of("Viettel Telecom", "Viettel Networks", "Viettel Post", "Viettel Digital", "Viettel Cyber Security"))
                        .products(List.of("Viettel++", "Viettel Money", "Viettel AI", "Mocha"))
                        .keyPeople(List.of("Tao Duc Thang"))
                        .industry("Telecommunications")
                        .isActive(true)
                        .build(),
                TrackedCompany.builder()
                        .companyName("VNPT")
                        .aliases(List.of("Vietnam Posts and Telecommunications Group", "Tap doan VNPT"))
                        .subsidiaries(List.of("VinaPhone", "VNPT-IT", "VNPT Media", "VNPT Technology"))
                        .products(List.of("VinaPhone", "VNPT SmartCloud", "VNPT-iOffice"))
                        .keyPeople(List.of("Huynh Quang Liem"))
                        .industry("Telecommunications")
                        .isActive(true)
                        .build(),
                TrackedCompany.builder()
                        .companyName("Samsung")
                        .aliases(List.of("Samsung Electronics", "Samsung Group", "SEC"))
                        .subsidiaries(List.of("Samsung Display", "Samsung SDI", "Samsung Electro-Mechanics", "Samsung SDS", "Harman"))
                        .products(List.of("Galaxy", "Exynos", "OLED", "QLED", "Samsung Knox", "One UI", "SmartThings"))
                        .keyPeople(List.of("Lee Jae-yong", "Jong-Hee Han"))
                        .industry("Electronics")
                        .isActive(true)
                        .build(),
                TrackedCompany.builder()
                        .companyName("Microsoft")
                        .aliases(List.of("MSFT", "Microsoft Corporation", "Microsoft Corp"))
                        .subsidiaries(List.of("Azure", "LinkedIn", "GitHub", "Xbox", "Activision Blizzard", "Nuance"))
                        .products(List.of("Windows", "Office 365", "Microsoft 365", "Copilot", "Teams", "Visual Studio", "VS Code", "Bing", "Power BI", "Dynamics 365"))
                        .keyPeople(List.of("Satya Nadella", "Brad Smith"))
                        .industry("Technology")
                        .isActive(true)
                        .build(),
                TrackedCompany.builder()
                        .companyName("NVIDIA")
                        .aliases(List.of("NVDA", "NVIDIA Corporation"))
                        .subsidiaries(List.of("Mellanox Technologies"))
                        .products(List.of("GeForce", "RTX", "CUDA", "Tesla GPU", "DGX", "Omniverse", "NVIDIA AI Enterprise", "Blackwell", "Hopper", "Grace"))
                        .keyPeople(List.of("Jensen Huang"))
                        .industry("Semiconductors")
                        .isActive(true)
                        .build()
        );

        Map<String, TrackedCompany> existingByName = trackedCompanyRepository.findAll().stream()
                .collect(Collectors.toMap(
                        company -> normalize(company.getCompanyName()),
                        Function.identity(),
                        (first, ignored) -> first));

        List<String> targetNames = targets.stream()
                .map(company -> normalize(company.getCompanyName()))
                .toList();

        for (TrackedCompany target : targets) {
            TrackedCompany existing = existingByName.get(normalize(target.getCompanyName()));
            if (existing == null) {
                trackedCompanyRepository.save(target);
                continue;
            }

            existing.setAliases(target.getAliases());
            existing.setSubsidiaries(target.getSubsidiaries());
            existing.setProducts(target.getProducts());
            existing.setKeyPeople(target.getKeyPeople());
            existing.setIndustry(target.getIndustry());
            existing.setIsActive(true);
            trackedCompanyRepository.save(existing);
        }

        trackedCompanyRepository.findAll().stream()
                .filter(company -> !targetNames.contains(normalize(company.getCompanyName())))
                .filter(company -> Boolean.TRUE.equals(company.getIsActive()))
                .forEach(company -> {
                    company.setIsActive(false);
                    trackedCompanyRepository.save(company);
                });

        log.info("TrackedCompanySeeder: Active tracked companies limited to {}.", targetNames);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
