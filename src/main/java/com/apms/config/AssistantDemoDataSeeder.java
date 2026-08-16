package com.apms.config;

import com.apms.common.enums.MemberRole;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectMember;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
@Order(2)
public class AssistantDemoDataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final Neo4jClient neo4jClient;
    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyProfileVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    private static final String PROJECT_ID = "1";

    // ============================================================
    // OWNER / CENTRAL COMPANY - CAFE F TICKER: FPT
    // ============================================================
    private static final String FPT_ID = "6a31a0000000000000000001";

    // ============================================================
    // CAFE F COMPANIES
    // New IDs are intentionally different from the previous demo set.
    // ============================================================
    private static final String VIETNAM_AIRLINES_ID = "6a31a0000000000000000030"; // HVN
    private static final String PVOIL_ID = "6a31a0000000000000000031";            // OIL
    private static final String VINAMILK_ID = "6a31a0000000000000000032";         // VNM
    private static final String THIEN_LONG_ID = "6a31a0000000000000000033";       // TLG
    private static final String PJICO_ID = "6a31a0000000000000000034";            // PGI
    private static final String PETROSETCO_ID = "6a31a0000000000000000035";        // PET
    private static final String ELCOM_ID = "6a31a0000000000000000036";            // ELC
    private static final String CTX_ID = "6a31a0000000000000000037";              // CTX

    // ============================================================
    // PREVIOUS DEMO COMPANIES
    // Delete them from MongoDB + Neo4j when this seeder runs again.
    // This includes both the first Vietnamese demo set and the later
    // foreign-company demo set.
    // ============================================================
    private static final List<String> LEGACY_COMPANY_IDS = List.of(
            "6a31a0000000000000000002",
            "6a31a0000000000000000003",
            "6a31a0000000000000000004",
            "6a31a0000000000000000005",
            "6a31a0000000000000000006",
            "6a31a0000000000000000007",
            "6a31a0000000000000000008",
            "6a31a0000000000000000009",
            "6a31a0000000000000000010",
            "6a31a0000000000000000011",
            "6a31a0000000000000000012",
            "6a31a0000000000000000013",
            "6a31a0000000000000000014",
            "6a31a0000000000000000015",
            "6a31a0000000000000000016",
            "6a31a0000000000000000017",
            "6a31a0000000000000000018",
            "6a31a0000000000000000019",
            "6a31a0000000000000000020",
            "6a31a0000000000000000021",
            "6a31a0000000000000000022"
    );

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Running AssistantDemoDataSeeder with CafeF-listed FPT-centric data...");

        ensureProjectExists();
        seedCompanyProfiles();
        seedNeo4jGraph();

        log.info("");
        log.info("============================================================");
        log.info("CAFEF-LISTED FPT-CENTRIC DEMO DATA SEEDED");
        log.info("============================================================");
        log.info("Owner organization: {}", ownerOrganizationService.getOwnerCompanyId());
        log.info("OWNER - FPT (FPT): {}", FPT_ID);
        log.info("PARTNER - Vietnam Airlines (HVN): {}", VIETNAM_AIRLINES_ID);
        log.info("PARTNER - PVOIL (OIL): {}", PVOIL_ID);
        log.info("CUSTOMER - Vinamilk (VNM): {}", VINAMILK_ID);
        log.info("CUSTOMER - Thien Long Group (TLG): {}", THIEN_LONG_ID);
        log.info("CUSTOMER - PJICO (PGI): {}", PJICO_ID);
        log.info("POTENTIAL PARTNER - PETROSETCO (PET): {}", PETROSETCO_ID);
        log.info("COMPETITOR - ELCOM (ELC): {}", ELCOM_ID);
        log.info("SUPPLIER - CTX Holdings (CTX): {}", CTX_ID);
        log.info("============================================================");
        log.info("");
    }

    // ============================================================
    // PROJECT
    // ============================================================

    private Project ensureProjectExists() {
        Account manager = accountRepository.findByEmail("manager@apms.com").orElseThrow();

        Project project = projectRepository.findById(1L).orElse(null);
        if (project == null) {
            project = Project.builder()
                    .projectName("AI Assistant Demo Project")
                    .projectType(ProjectType.RESEARCH_NEW_COMPANY)
                    .targetCompanyName("FPT Business Ecosystem - CafeF Companies")
                    .description("FPT-centric company data using enterprises that have company/ticker profiles on CafeF.")
                    .status(ProjectStatus.ACTIVE)
                    .createdByAccount(manager)
                    .build();

            project = projectRepository.save(project);
        }

        ensureMembership(project, "manager@apms.com", MemberRole.MANAGER);
        ensureMembership(project, "staff@apms.com", MemberRole.STAFF);
        return project;
    }

    private void ensureMembership(Project project, String email, MemberRole role) {
        accountRepository.findByEmail(email).ifPresent(account -> {
            boolean exists = projectMemberRepository
                    .existsByProject_IdAndAccount_Id(project.getId(), account.getId());

            if (!exists) {
                projectMemberRepository.save(
                        ProjectMember.builder()
                                .project(project)
                                .account(account)
                                .memberRole(role)
                                .build()
                );
            }
        });
    }

    // ============================================================
    // MONGODB - COMPANY PROFILES
    // ============================================================

    private void seedCompanyProfiles() {
        cleanupLegacyMongoProfiles();

        // ------------------------------------------------------------
        // FPT CORPORATION - OWNER - CafeF ticker: FPT
        // CafeF: https://cafef.vn/du-lieu/hose/fpt-cong-ty-co-phan-fpt.chn
        // Official contact/annual-report data retained from verified seed.
        // ------------------------------------------------------------
        CompanyProfile fpt = verifiedProfile(
                FPT_ID,
                "Công ty Cổ phần FPT",
                "FPT Corporation",
                "FPT",
                "0101248141",
                54110,
                "54,110 employees (31 Dec 2025)",
                "https://fpt.com",
                List.of("ir@fpt.com"),
                List.of("+84 24 7300 7300"),
                "Tòa nhà FPT, số 10 phố Phạm Văn Bạch, phường Cầu Giấy, Thành phố Hà Nội, Việt Nam.",
                List.of("Information Technology", "Software Services", "Artificial Intelligence", "Cloud Services", "Digital Transformation"),
                "Technology corporation providing IT services, software engineering, digital transformation, AI, cloud, telecommunications and education services.",
                List.of(
                        product("Software Engineering Services", "IT Services", "Software development, modernization and global technology services."),
                        product("Digital Transformation", "Consulting", "Enterprise digital transformation consulting and implementation services."),
                        product("AI Services", "Artificial Intelligence", "AI infrastructure, platforms and enterprise AI services."),
                        product("Cloud Services", "Cloud", "Cloud migration, managed cloud and enterprise infrastructure services.")
                ),
                List.of("Vietnam", "Japan", "United States", "Europe", "APAC"),
                List.of("Enterprise", "Banking", "Manufacturing", "Automotive", "Aviation", "Public Sector"),
                List.of("Large global delivery capability.", "Strong technology ecosystem across AI, cloud and digital transformation.", "Established Vietnamese technology brand."),
                List.of("Large operating structure can increase coordination complexity.", "International technology demand is exposed to global economic cycles."),
                List.of("Continued expansion in AI, cloud, automotive software and enterprise transformation."),
                List.of("Strong competition from large global IT services companies.")
        );

        // ------------------------------------------------------------
        // VIETNAM AIRLINES - PARTNER - CafeF ticker: HVN
        // CafeF: https://cafef.vn/du-lieu/upcom/hvn-tai-lieu.chn
        // FPT relationship: comprehensive strategic partnership.
        // Official enterprise registration no.: 0100107518
        // Official published workforce: approximately 6,000 people.
        // ------------------------------------------------------------
        CompanyProfile vietnamAirlines = verifiedProfile(
                VIETNAM_AIRLINES_ID,
                "Tổng công ty Hàng không Việt Nam - CTCP",
                "Vietnam Airlines",
                "HVN",
                "0100107518",
                6000,
                "Approximately 6,000 employees (official statement, 15 May 2025)",
                "https://www.vietnamairlines.com",
                List.of("nhadautu@vietnamairlines.com"),
                List.of("+84 24 3827 2289"),
                "Số 200 Nguyễn Sơn, Phường Bồ Đề, Hà Nội, Việt Nam.",
                List.of("Aviation", "Air Transportation", "Travel Services"),
                "Airline group providing passenger air transportation, cargo services and related aviation services.",
                List.of(
                        product("Passenger Air Transportation", "Aviation", "Domestic and international passenger air services."),
                        product("Air Cargo", "Logistics", "Air freight and cargo transportation services."),
                        product("Digital Passenger Services", "Digital Services", "Digital channels supporting booking and passenger experience.")
                ),
                List.of("Vietnam", "Asia", "Europe", "International"),
                List.of("Passengers", "Corporate Travelers", "Cargo Customers", "Travel Partners"),
                List.of("Established national aviation brand.", "Domestic and international route network."),
                List.of("Operations are sensitive to fuel costs and aviation demand cycles."),
                List.of("Strategic digital transformation and aviation technology collaboration with FPT."),
                List.of("High operational complexity and strong regional airline competition.")
        );

        // ------------------------------------------------------------
        // PVOIL - PARTNER - CafeF ticker: OIL
        // CafeF: https://cafef.vn/du-lieu/OIL/bao-cao-tai-chinh.chn
        // FPT Digital was selected as a strategic partner for the
        // 2024-2030 digital transformation roadmap.
        // Tax code: 0305795054
        // Official company overview: more than 7,400 employees.
        // employeeCount stores the published lower-bound 7,400.
        // ------------------------------------------------------------
        CompanyProfile pvoil = verifiedProfile(
                PVOIL_ID,
                "Tổng Công ty Dầu Việt Nam - CTCP",
                "PVOIL",
                "OIL",
                "0305795054",
                7400,
                "More than 7,400 employees (official company overview)",
                "https://www.pvoil.com.vn",
                List.of("contact@pvoil.com.vn"),
                List.of("+84 28 3910 6990"),
                "Tầng 14-18, Tòa nhà PetroVietnam Tower, Số 1-5 Lê Duẩn, Phường Sài Gòn, Thành phố Hồ Chí Minh, Việt Nam.",
                List.of("Energy", "Petroleum", "Fuel Distribution", "Retail"),
                "Petroleum company operating import, export, distribution, retail and related energy services.",
                List.of(
                        product("Petroleum Distribution", "Energy", "Wholesale and distribution of petroleum products."),
                        product("Fuel Retail Network", "Retail", "Retail fuel distribution through service-station networks."),
                        product("Energy Operations", "Energy Services", "Operational services supporting petroleum supply and distribution.")
                ),
                List.of("Vietnam"),
                List.of("Consumers", "Transport Companies", "Industrial Customers", "Enterprise"),
                List.of("Large petroleum distribution network in Vietnam.", "Strong fuel retail and enterprise distribution presence."),
                List.of("Business performance is exposed to energy-price and regulatory movements."),
                List.of("Strategic digital transformation roadmap collaboration with FPT Digital."),
                List.of("Energy transition and changing mobility patterns may reshape petroleum demand.")
        );

        // ------------------------------------------------------------
        // VINAMILK - CUSTOMER - CafeF ticker: VNM
        // CafeF: https://cafef.vn/du-lieu/hose/vnm-cong-ty-co-phan-sua-viet-nam.chn
        // Official data:
        //   Tax code: 0300588569
        //   Phone: 1900 636 979
        //   Email: vinamilk@vinamilk.com.vn
        //   Head office: 10 Tan Trao, HCMC
        //   Workforce: 9,960 employees (2024 sustainability reporting)
        // FPT relationship: Vinamilk has used FPT's Kyta platform for years.
        // ------------------------------------------------------------
        CompanyProfile vinamilk = verifiedProfile(
                VINAMILK_ID,
                "Công ty Cổ phần Sữa Việt Nam",
                "Vinamilk",
                "VNM",
                "0300588569",
                9960,
                "9,960 employees (2024)",
                "https://www.vinamilk.com.vn",
                List.of("vinamilk@vinamilk.com.vn"),
                List.of("1900 636 979"),
                "Số 10 Tân Trào, Phường Tân Mỹ, Thành phố Hồ Chí Minh, Việt Nam.",
                List.of("Dairy", "Food and Beverage", "Nutrition", "Consumer Goods"),
                "Dairy and nutrition company producing and distributing milk, dairy products, beverages and nutritional products.",
                List.of(
                        product("Liquid Milk", "Dairy", "Fresh and processed liquid milk products."),
                        product("Yogurt", "Dairy", "Yogurt and fermented dairy products."),
                        product("Nutrition Products", "Nutrition", "Powdered milk and nutritional products for multiple consumer segments.")
                ),
                List.of("Vietnam", "Asia", "International"),
                List.of("Consumers", "Retailers", "Distributors", "Institutional Customers"),
                List.of("Leading Vietnamese dairy brand.", "Large manufacturing and distribution network."),
                List.of("Consumer demand and input-cost volatility can affect margins."),
                List.of("Further digitalization of contracts, governance and supply-chain operations."),
                List.of("Strong competition in dairy, beverages and nutrition markets.")
        );

        // ------------------------------------------------------------
        // THIEN LONG GROUP - CUSTOMER - CafeF ticker: TLG
        // CafeF: https://cafef.vn/du-lieu/hose/tlg-cong-ty-co-phan-tap-doan-thien-long.chn
        // Official data:
        //   Enterprise no.: 0301464830
        //   Employees: 2,977, data as of May 2025
        //   Phone: +84 28 3750 5555
        //   Email: info@thienlonggroup.com
        // FPT relationship: Thiên Long selected FPT Digital to build a
        // comprehensive digital transformation roadmap.
        // ------------------------------------------------------------
        CompanyProfile thienLong = verifiedProfile(
                THIEN_LONG_ID,
                "Công ty Cổ phần Tập đoàn Thiên Long",
                "Thien Long Group",
                "TLG",
                "0301464830",
                2977,
                "2,977 employees (May 2025; including factory workers)",
                "https://thienlonggroup.com",
                List.of("info@thienlonggroup.com"),
                List.of("+84 28 3750 5555"),
                "Tầng 10, Sofic Tower, Số 10 Đường Mai Chí Thọ, Phường An Khánh, Thành phố Hồ Chí Minh, Việt Nam.",
                List.of("Stationery", "Consumer Goods", "Manufacturing", "Education Supplies"),
                "Manufacturer and distributor of writing instruments, stationery, office supplies and education products.",
                List.of(
                        product("Writing Instruments", "Stationery", "Pens, markers and writing products."),
                        product("Office Supplies", "Stationery", "Office and professional stationery products."),
                        product("School Supplies", "Education", "Learning and school stationery products.")
                ),
                List.of("Vietnam", "Southeast Asia", "International Export Markets"),
                List.of("Consumers", "Students", "Schools", "Offices", "Retailers", "Distributors"),
                List.of("Strong stationery brand in Vietnam.", "Established manufacturing and distribution capabilities."),
                List.of("Business has seasonal demand linked to school and office consumption cycles."),
                List.of("Digital-core and process-modernization initiatives supported by FPT Digital."),
                List.of("Competition from domestic and international stationery brands.")
        );

        // ------------------------------------------------------------
        // PJICO - CUSTOMER - CafeF ticker: PGI
        // CafeF: https://cafef.vn/du-lieu/hose/pgi-tong-cong-ty-co-phan-bao-hiem-petrolimex.chn
        // Official data:
        //   Tax code: 0100110768
        //   Employees: 1,599 as of 30 Jun 2025
        //   Phone: +84 24 3776 0867
        //   Email: pjico@petrolimex.com.vn
        // FPT relationship: PJICO selected FPT Digital as strategic partner
        // for its digital transformation roadmap/project.
        // ------------------------------------------------------------
        CompanyProfile pjico = verifiedProfile(
                PJICO_ID,
                "Tổng Công ty Cổ phần Bảo hiểm Petrolimex",
                "PJICO",
                "PGI",
                "0100110768",
                1599,
                "1,599 employees (30 Jun 2025)",
                "https://www.pjico.com.vn",
                List.of("pjico@petrolimex.com.vn"),
                List.of("+84 24 3776 0867"),
                "Tầng 21-22, Tòa nhà MIPEC, số 229 Tây Sơn, Phường Kim Liên, Hà Nội, Việt Nam.",
                List.of("Insurance", "Non-life Insurance", "Financial Services"),
                "Non-life insurance company providing insurance products for individuals and enterprises.",
                List.of(
                        product("Motor Insurance", "Insurance", "Insurance products for motor vehicles."),
                        product("Property Insurance", "Insurance", "Property and commercial insurance products."),
                        product("Health and Personal Insurance", "Insurance", "Health and personal accident insurance products.")
                ),
                List.of("Vietnam"),
                List.of("Individuals", "Enterprise", "Transport Companies", "Industrial Customers"),
                List.of("Established non-life insurer with a nationwide operating network."),
                List.of("Insurance profitability is exposed to claim volatility and investment-market conditions."),
                List.of("Digital insurance, process automation and data modernization with FPT Digital."),
                List.of("Highly competitive non-life insurance market.")
        );

        // ------------------------------------------------------------
        // PETROSETCO - POTENTIAL PARTNER - CafeF ticker: PET
        // CafeF: https://cafef.vn/du-lieu/hose/pet-tong-cong-ty-co-phan-dich-vu-tong-hop-dau-khi.chn
        // Official data:
        //   Enterprise no.: 0300452060
        //   More than 3,200 employees in 2025
        //   Phone: +84 28 3911 7777
        //   Email: info@petrosetco.com.vn
        // FPT relationship basis: PETROSETCO and FPT Digital jointly held a
        // digital-transformation workshop in May 2025. The APMS role is kept
        // as POTENTIAL_PARTNER because this evidence supports early-stage
        // collaboration, not a claim of a signed long-term strategic contract.
        // employeeCount stores the published lower-bound 3,200.
        // ------------------------------------------------------------
        CompanyProfile petrosetco = verifiedProfile(
                PETROSETCO_ID,
                "Tổng Công ty Cổ phần Dịch vụ Tổng hợp Dầu khí",
                "PETROSETCO",
                "PET",
                "0300452060",
                3200,
                "More than 3,200 employees (2025)",
                "https://www.petrosetco.com.vn",
                List.of("info@petrosetco.com.vn"),
                List.of("+84 28 3911 7777"),
                "Lầu 6, Tòa nhà PetroVietnam, Số 1-5 Lê Duẩn, Phường Sài Gòn, Thành phố Hồ Chí Minh, Việt Nam.",
                List.of("Distribution", "Oil and Gas Services", "Logistics", "Catering", "Real Estate Services"),
                "General services corporation operating distribution, oil-and-gas services, logistics, catering and real-estate services.",
                List.of(
                        product("Distribution Services", "Distribution", "Distribution of technology and other products."),
                        product("Materials and Equipment Supply", "Oil and Gas Services", "Supply of petroleum materials and technical equipment."),
                        product("Logistics and Manpower Services", "Business Services", "Logistics and manpower services for projects and enterprises.")
                ),
                List.of("Vietnam"),
                List.of("Enterprise", "Oil and Gas Companies", "Technology Brands", "Industrial Customers"),
                List.of("Diversified service portfolio.", "Large distribution and oil-and-gas service ecosystem."),
                List.of("Distribution margins and demand can be sensitive to market cycles."),
                List.of("Potential for deeper digital-transformation collaboration with FPT after joint capability-building activities."),
                List.of("Competition and supply-chain volatility across distribution and services markets.")
        );

        // ------------------------------------------------------------
        // ELCOM - COMPETITOR - CafeF ticker: ELC
        // CafeF: https://cafef.vn/du-lieu/hose/elc-cong-ty-co-phan-cong-nghe-vien-thong-elcom.chn
        // Official legal/contact data:
        //   Enterprise no.: 0101435127
        //   Phone: +84 24 3835 9359
        //   Email: contact@elcom.com.vn
        // Workforce value below is a reported market-data figure:
        //   250 employees as of 31 Mar 2026.
        // COMPETITOR_OF is an APMS analytical classification based on overlap
        // in digital transformation, telecom/software and technology solutions;
        // it is NOT a contractual relationship declared by either company.
        // ------------------------------------------------------------
        CompanyProfile elcom = verifiedProfile(
                ELCOM_ID,
                "Công ty Cổ phần Công nghệ - Viễn thông ELCOM",
                "ELCOM",
                "ELC",
                "0101435127",
                250,
                "250 employees (31 Mar 2026; reported market-data figure)",
                "https://www.elcom.com.vn",
                List.of("contact@elcom.com.vn"),
                List.of("+84 24 3835 9359"),
                "Tòa nhà ELCOM, Phố Duy Tân, Phường Cầu Giấy, Hà Nội, Việt Nam.",
                List.of("Information Technology", "Telecommunications", "Intelligent Transportation", "Digital Transformation", "Security and Defence Technology"),
                "Technology company providing telecommunications, intelligent transportation, digital transformation and security-related technology solutions.",
                List.of(
                        product("Intelligent Transportation Systems", "Smart Transportation", "Technology platforms for traffic monitoring and intelligent transport operations."),
                        product("Telecommunications Solutions", "Telecommunications", "Technology solutions for telecommunications infrastructure and operations."),
                        product("Digital Transformation Solutions", "Information Technology", "Software and digital platforms supporting organizational modernization.")
                ),
                List.of("Vietnam"),
                List.of("Government", "Transport Operators", "Telecommunications", "Enterprise"),
                List.of("Strong specialization in intelligent transportation and telecommunications technology."),
                List.of("Smaller scale than large diversified technology groups."),
                List.of("Growth from smart infrastructure, AI and public-sector digitalization."),
                List.of("Overlapping technology-service markets create competitive pressure with larger IT providers such as FPT.")
        );

        // ------------------------------------------------------------
        // CTX HOLDINGS - SUPPLIER - CafeF ticker: CTX
        // CafeF: https://cafef.vn/du-lieu/upcom/ctx-tong-cong-ty-co-phan-dau-tu-xay-dung-va-thuong-mai-viet-nam.chn
        // Official HNX/legal/contact data:
        //   Tax/enterprise no.: 0100109441
        //   Phone: +84 24 6281 2000
        //   Email: info@ctx.vn
        //   Current office: Floor 2, HH2, 4 Duong Dinh Nghe, Cau Giay, Hanoi
        // Company-size figure: 50 employees in a 2025 public-company profile.
        // Supplier relationship basis: CTX's official site states that CTX
        // developed FPT Tower under a turnkey model, including land-use rights
        // and the building. This is why APMS classifies CTX as a supplier/service
        // provider to FPT.
        // ------------------------------------------------------------
        CompanyProfile ctx = verifiedProfile(
                CTX_ID,
                "Tổng Công ty Cổ phần Đầu tư Xây dựng và Thương mại Việt Nam",
                "CTX Holdings",
                "CTX",
                "0100109441",
                50,
                "50 employees (2025 public-company profile)",
                "https://ctx.vn",
                List.of("info@ctx.vn"),
                List.of("+84 24 6281 2000"),
                "Tầng 2, Tòa nhà HH2, Số 4 Đường Dương Đình Nghệ, Phường Cầu Giấy, Hà Nội, Việt Nam.",
                List.of("Construction", "Real Estate", "Property Development", "Turnkey Development"),
                "Construction and real-estate development company delivering property development and turnkey building projects.",
                List.of(
                        product("Turnkey Building Development", "Construction", "Turnkey development of office and commercial buildings."),
                        product("Real Estate Development", "Real Estate", "Development and operation of real-estate projects."),
                        product("Construction Services", "Construction", "Construction and project-development services.")
                ),
                List.of("Vietnam"),
                List.of("Enterprise", "Property Investors", "Corporate Occupiers"),
                List.of("Experience in large corporate office and real-estate projects."),
                List.of("Project-based revenue can be volatile and dependent on real-estate cycles."),
                List.of("Potential future corporate real-estate and facility projects."),
                List.of("Construction-cycle, financing and real-estate market risks.")
        );

        companyProfileRepository.saveAll(
                List.of(
                        fpt,
                        vietnamAirlines,
                        pvoil,
                        vinamilk,
                        thienLong,
                        pjico,
                        petrosetco,
                        elcom,
                        ctx
                )
        );

        if (versionRepository
                .findByCompanyProfileIdAndVersion(fpt.getId(), fpt.getVersion())
                .isEmpty()) {

            CompanyProfileVersion version = CompanyProfileVersion.builder()
                    .companyProfileId(fpt.getId())
                    .companyId(fpt.getCompanyId())
                    .version(fpt.getVersion())
                    .snapshot(objectMapper.convertValue(
                            fpt,
                            new TypeReference<Map<String, Object>>() {}
                    ))
                    .changeSummary("Initial CafeF-listed FPT-centric seed")
                    .createdBy(-1L)
                    .build();

            versionRepository.save(version);
        }
    }

    // ============================================================
    // VERIFIED PROFILE BUILDER
    // No fake/dummy tax code, email, phone, address or company size
    // is generated here. Every value is passed explicitly.
    // ============================================================

    private CompanyProfile verifiedProfile(
            String id,
            String legalName,
            String tradeName,
            String stockTicker,
            String taxIdentifier,
            int employeeCount,
            String employeeTier,
            String website,
            List<String> emails,
            List<String> phones,
            String address,
            List<String> industries,
            String businessModel,
            List<CompanyProfile.Product> products,
            List<String> markets,
            List<String> targetCustomers,
            List<String> strengths,
            List<String> weaknesses,
            List<String> opportunities,
            List<String> threats
    ) {
        return CompanyProfile.builder()
                .id(id)
                .companyId(id)
                .identity(
                        CompanyProfile.Identity.builder()
                                .legalName(legalName)
                                .tradeName(tradeName)
                                .stockTicker(stockTicker)
                                .taxCode(taxIdentifier)
                                .build()
                )
                .companySize(
                        CompanyProfile.CompanySize.builder()
                                .employeeCount(employeeCount)
                                .employeeTier(employeeTier)
                                .build()
                )
                .business(
                        CompanyProfile.Business.builder()
                                .industries(industries)
                                .businessModel(businessModel)
                                .products(products)
                                .markets(markets)
                                .targetCustomers(targetCustomers)
                                .build()
                )
                .contact(
                        CompanyProfile.Contact.builder()
                                .website(website)
                                .emails(emails)
                                .phones(phones)
                                .addresses(List.of(
                                        CompanyProfile.Address.builder()
                                                .fullAddress(address)
                                                .build()
                                ))
                                .build()
                )
                .insights(
                        CompanyProfile.Insights.builder()
                                .strengths(strengths)
                                .weaknesses(weaknesses)
                                .opportunities(opportunities)
                                .threats(threats)
                                .build()
                )
                .reviewStatus("APPROVED")
                .sourceRefs(
                        CompanyProfile.SourceRefs.builder()
                                .projectIds(Set.of(PROJECT_ID))
                                .build()
                )
                .metadata(
                        CompanyProfile.Metadata.builder()
                                .createdBy("system-seed")
                                .build()
                )
                .build();
    }

    private CompanyProfile.Product product(String name, String category, String description) {
        return CompanyProfile.Product.builder()
                .name(name)
                .category(category)
                .description(description)
                .build();
    }

    // ============================================================
    // REMOVE OLD MONGODB PROFILES
    // ============================================================

    private void cleanupLegacyMongoProfiles() {
        for (String legacyCompanyId : LEGACY_COMPANY_IDS) {
            companyProfileRepository
                    .findById(legacyCompanyId)
                    .ifPresent(companyProfileRepository::delete);
        }
    }

    // ============================================================
    // NEO4J
    // ============================================================

    private void seedNeo4jGraph() {
        // Delete old demo relationships first so no stale external-to-external
        // relationships survive from previous seeder versions.
        clearSeededRelationships();
        cleanupLegacyNeo4jCompanies();

        // Central owner node.
        mergeCompanyNode(FPT_ID, "FPT Corporation", "Information Technology");

        // CafeF-listed external companies.
        mergeCompanyNode(VIETNAM_AIRLINES_ID, "Vietnam Airlines", "Aviation");
        mergeCompanyNode(PVOIL_ID, "PVOIL", "Energy & Petroleum");
        mergeCompanyNode(VINAMILK_ID, "Vinamilk", "Dairy & Consumer Goods");
        mergeCompanyNode(THIEN_LONG_ID, "Thien Long Group", "Stationery & Consumer Goods");
        mergeCompanyNode(PJICO_ID, "PJICO", "Insurance");
        mergeCompanyNode(PETROSETCO_ID, "PETROSETCO", "Distribution & Oil and Gas Services");
        mergeCompanyNode(ELCOM_ID, "ELCOM", "Information Technology & Telecommunications");
        mergeCompanyNode(CTX_ID, "CTX Holdings", "Construction & Real Estate");

        // ========================================================
        // FPT-CENTRIC RELATIONSHIPS ONLY
        //
        // IMPORTANT:
        // The existing backend relationship names are preserved for
        // compatibility. The business meaning is the role of the TARGET
        // company relative to FPT.
        //
        // NO external-company-to-external-company relationship is created.
        // ========================================================

        // PARTNERS OF FPT
        createRelationship(FPT_ID, VIETNAM_AIRLINES_ID, "PARTNER_WITH");
        createRelationship(FPT_ID, PVOIL_ID, "PARTNER_WITH");

        // CUSTOMERS / CLIENTS OF FPT SERVICES
        createRelationship(FPT_ID, VINAMILK_ID, "CUSTOMER_OF");
        createRelationship(FPT_ID, THIEN_LONG_ID, "CUSTOMER_OF");
        createRelationship(FPT_ID, PJICO_ID, "CUSTOMER_OF");

        // EARLY-STAGE / POTENTIAL PARTNER
        createRelationship(FPT_ID, PETROSETCO_ID, "POTENTIAL_PARTNER_OF");

        // ANALYTICAL COMPETITOR CLASSIFICATION
        createRelationship(FPT_ID, ELCOM_ID, "COMPETITOR_OF");

        // SUPPLIER / SERVICE PROVIDER TO FPT
        createRelationship(FPT_ID, CTX_ID, "SUPPLIER_OF");

        // Remove the old APMS demo organization node if it still exists.
        String deleteLegacyOrganization = """
                MATCH (c:Company {companyId: '6a31a0000000000000000000'})
                DETACH DELETE c
                """;

        neo4jClient.query(deleteLegacyOrganization).run();
    }

    private void clearSeededRelationships() {
        String cypher = """
                MATCH (:Company)-[r]->(:Company)
                WHERE r.projectId = $projectId
                  AND r.confirmedBy = 'system-seed'
                DELETE r
                """;

        neo4jClient.query(cypher)
                .bind(PROJECT_ID).to("projectId")
                .run();
    }

    private void cleanupLegacyNeo4jCompanies() {
        String cypher = """
                MATCH (c:Company)
                WHERE c.companyId IN $legacyCompanyIds
                DETACH DELETE c
                """;

        neo4jClient.query(cypher)
                .bind(LEGACY_COMPANY_IDS).to("legacyCompanyIds")
                .run();
    }

    private void mergeCompanyNode(String companyId, String name, String industry) {
        String cypher = """
                MERGE (c:Company {companyId: $companyId})
                ON CREATE SET
                    c.name = $name,
                    c.industry = $industry,
                    c.createdAt = datetime()
                ON MATCH SET
                    c.name = $name,
                    c.industry = $industry,
                    c.updatedAt = datetime()
                """;

        neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "companyId", companyId,
                        "name", name,
                        "industry", industry
                ))
                .run();
    }

    private void createRelationship(String sourceId, String targetId, String relType) {
        String cypher = String.format("""
                MATCH (c1:Company {companyId: $sourceId})
                MATCH (c2:Company {companyId: $targetId})
                MERGE (c1)-[r:%s]->(c2)
                SET r.confidenceScore = 1.0,
                    r.confirmedBy = 'system-seed',
                    r.confirmedAt = datetime(),
                    r.projectId = $projectId
                """, relType);

        neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "sourceId", sourceId,
                        "targetId", targetId,
                        "projectId", PROJECT_ID
                ))
                .run();
    }
}