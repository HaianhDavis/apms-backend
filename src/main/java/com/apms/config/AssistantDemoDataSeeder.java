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
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
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
    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyProfileVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    private static final String PROJECT_ID = "1";

    // ============================================================
    // OWNER / CENTRAL COMPANY
    // ============================================================

    private static final String FPT_ID =
            "6a31a0000000000000000001";


    // ============================================================
    // EXTERNAL COMPANIES
    // ============================================================

    // PARTNER
    private static final String NVIDIA_ID =
            "6a31a0000000000000000010";

    private static final String SAP_ID =
            "6a31a0000000000000000011";


    // SUPPLIER
    private static final String DELL_ID =
            "6a31a0000000000000000012";


    // CUSTOMER
    private static final String VIETNAM_AIRLINES_ID =
            "6a31a0000000000000000013";

    private static final String PVOIL_ID =
            "6a31a0000000000000000014";


    // POTENTIAL PARTNER
    private static final String MITSUBISHI_MOTORS_ID =
            "6a31a0000000000000000015";


    // COMPETITOR
    private static final String TCS_ID =
            "6a31a0000000000000000016";

    private static final String INFOSYS_ID =
            "6a31a0000000000000000017";


    // ============================================================
    // OLD DEMO COMPANIES
    // These companies are removed from MongoDB and Neo4j.
    // ============================================================

    private static final List<String> LEGACY_COMPANY_IDS = List.of(
            "6a31a0000000000000000002", // CMC
            "6a31a0000000000000000003", // Viettel
            "6a31a0000000000000000004", // VNG
            "6a31a0000000000000000005", // MoMo
            "6a31a0000000000000000006", // VNPT
            "6a31a0000000000000000007", // AWS
            "6a31a0000000000000000008", // Microsoft
            "6a31a0000000000000000009"  // RetailPlus
    );


    // ============================================================
    // RUN
    // ============================================================

    @Override
    @Transactional
    public void run(String... args) {

        log.info(
                "Running AssistantDemoDataSeeder with verified FPT-centric company data..."
        );

        Project project = ensureProjectExists();

        // MongoDB
        seedCompanyProfiles();

        // Neo4j
        seedNeo4jGraph();

        // SQL Server
        seedScoreSnapshots(project);

        log.info("");
        log.info(
                "============================================================"
        );
        log.info(
                "VERIFIED FPT-CENTRIC DEMO DATA SEEDED"
        );
        log.info(
                "============================================================"
        );

        log.info(
                "Owner organization: {}",
                ownerOrganizationService.getOwnerCompanyId()
        );

        log.info(
                "FPT: {}",
                FPT_ID
        );

        log.info(
                "PARTNER - NVIDIA: {}",
                NVIDIA_ID
        );

        log.info(
                "PARTNER - SAP: {}",
                SAP_ID
        );

        log.info(
                "SUPPLIER - Dell Technologies: {}",
                DELL_ID
        );

        log.info(
                "CUSTOMER - Vietnam Airlines: {}",
                VIETNAM_AIRLINES_ID
        );

        log.info(
                "CUSTOMER - PVOIL: {}",
                PVOIL_ID
        );

        log.info(
                "POTENTIAL PARTNER - Mitsubishi Motors: {}",
                MITSUBISHI_MOTORS_ID
        );

        log.info(
                "COMPETITOR - TCS: {}",
                TCS_ID
        );

        log.info(
                "COMPETITOR - Infosys: {}",
                INFOSYS_ID
        );

        log.info(
                "============================================================"
        );

        log.info("");
    }


    // ============================================================
    // PROJECT
    // ============================================================

    private Project ensureProjectExists() {

        Account manager =
                accountRepository
                        .findByEmail("manager@apms.com")
                        .orElseThrow();

        Project project =
                projectRepository
                        .findById(1L)
                        .orElse(null);

        if (project == null) {

            project =
                    Project.builder()

                            .projectName(
                                    "AI Assistant Demo Project"
                            )

                            .projectType(
                                    ProjectType.RESEARCH_NEW_COMPANY
                            )

                            .targetCompanyName(
                                    "FPT Business Ecosystem"
                            )

                            .description(
                                    "Approved FPT-centric company data for AI assistant testing."
                            )

                            .status(
                                    ProjectStatus.ACTIVE
                            )

                            .createdByAccount(
                                    manager
                            )

                            .build();

            project =
                    projectRepository.save(project);
        }

        ensureMembership(
                project,
                "manager@apms.com",
                MemberRole.MANAGER
        );

        ensureMembership(
                project,
                "staff@apms.com",
                MemberRole.STAFF
        );

        return project;
    }


    private void ensureMembership(
            Project project,
            String email,
            MemberRole role
    ) {

        accountRepository
                .findByEmail(email)
                .ifPresent(account -> {

                    boolean exists =
                            projectMemberRepository
                                    .existsByProject_IdAndAccount_Id(
                                            project.getId(),
                                            account.getId()
                                    );

                    if (!exists) {

                        projectMemberRepository.save(

                                ProjectMember.builder()

                                        .project(
                                                project
                                        )

                                        .account(
                                                account
                                        )

                                        .memberRole(
                                                role
                                        )

                                        .build()
                        );
                    }
                });
    }


    // ============================================================
    // MONGODB
    // COMPANY PROFILES
    // ============================================================

    private void seedCompanyProfiles() {

        cleanupLegacyMongoProfiles();

        /*
         * =========================================================
         * IMPORTANT ABOUT taxCode
         * =========================================================
         *
         * CompanyProfile currently has only one String field:
         *
         *      taxCode
         *
         * Therefore:
         *
         * Vietnam companies:
         *      Vietnamese enterprise/tax code
         *
         * United States companies:
         *      EIN
         *
         * SAP Germany:
         *      VAT ID
         *
         * Mitsubishi Motors Japan:
         *      Japanese Corporate Number
         *
         * TCS / Infosys India:
         *      PAN
         *
         * NO TAX IDENTIFIER IS GENERATED BY THIS SEEDER.
         */


        // ========================================================
        // FPT CORPORATION
        // OWNER COMPANY
        // ========================================================
        //
        // Enterprise registration / tax code:
        // 0101248141
        //
        // Employees:
        // 54,110 as of 31 December 2025
        //
        // ========================================================

        CompanyProfile fpt =
                verifiedProfile(

                        FPT_ID,

                        "Công ty Cổ phần FPT",

                        "FPT Corporation",

                        "0101248141",

                        54110,

                        "54,110 employees (31 Dec 2025)",

                        "https://fpt.com",

                        List.of(
                                "ir@fpt.com"
                        ),

                        List.of(
                                "+84 24 7300 7300"
                        ),

                        "Tòa nhà FPT, số 10 phố Phạm Văn Bạch, "
                                + "phường Cầu Giấy, Thành phố Hà Nội, Việt Nam.",

                        List.of(
                                "Information Technology",
                                "Software Services",
                                "Artificial Intelligence",
                                "Cloud Services",
                                "Digital Transformation"
                        ),

                        "Technology corporation providing IT services, "
                                + "software engineering, digital transformation, "
                                + "AI, cloud, telecommunications and education services.",

                        List.of(

                                product(
                                        "Software Engineering Services",
                                        "IT Services",
                                        "Software development, modernization "
                                                + "and global technology services."
                                ),

                                product(
                                        "Digital Transformation",
                                        "Consulting",
                                        "Enterprise digital transformation consulting "
                                                + "and implementation services."
                                ),

                                product(
                                        "AI Services",
                                        "Artificial Intelligence",
                                        "AI infrastructure, platforms "
                                                + "and enterprise AI services."
                                ),

                                product(
                                        "Cloud Services",
                                        "Cloud",
                                        "Cloud migration, managed cloud "
                                                + "and enterprise infrastructure services."
                                )
                        ),

                        List.of(
                                "Vietnam",
                                "Japan",
                                "United States",
                                "Europe",
                                "APAC"
                        ),

                        List.of(
                                "Enterprise",
                                "Banking",
                                "Manufacturing",
                                "Automotive",
                                "Aviation",
                                "Public Sector"
                        ),

                        List.of(
                                "Large global delivery capability.",
                                "Strong technology ecosystem across AI, cloud "
                                        + "and digital transformation.",
                                "Established Vietnamese technology brand."
                        ),

                        List.of(
                                "Large operating structure can increase coordination complexity.",
                                "International technology demand is exposed "
                                        + "to global economic cycles."
                        ),

                        List.of(
                                "Continued expansion in AI, cloud, "
                                        + "automotive software and enterprise transformation."
                        ),

                        List.of(
                                "Strong competition from large global IT services companies."
                        )
                );


        // ========================================================
        // NVIDIA CORPORATION
        // PARTNER
        // ========================================================
        //
        // U.S. EIN:
        // 94-3177549
        //
        // Employees:
        // Approximately 42,000 FY2026
        //
        // ========================================================

        CompanyProfile nvidia =
                verifiedProfile(

                        NVIDIA_ID,

                        "NVIDIA Corporation",

                        "NVIDIA",

                        "94-3177549",

                        42000,

                        "Approximately 42,000 employees (FY2026)",

                        "https://www.nvidia.com",

                        List.of(
                                "info@nvidia.com"
                        ),

                        List.of(
                                "+1 408 486 2000"
                        ),

                        "2788 San Tomas Expressway, "
                                + "Santa Clara, CA 95051, USA",

                        List.of(
                                "Artificial Intelligence",
                                "Semiconductors",
                                "Accelerated Computing",
                                "AI Infrastructure"
                        ),

                        "Technology company focused on accelerated computing, "
                                + "GPUs, AI software and AI infrastructure.",

                        List.of(

                                product(
                                        "NVIDIA GPUs",
                                        "Accelerated Computing",
                                        "GPU platforms for AI, graphics "
                                                + "and high-performance computing."
                                ),

                                product(
                                        "NVIDIA AI Enterprise",
                                        "Artificial Intelligence",
                                        "Enterprise software for production AI workloads."
                                ),

                                product(
                                        "DGX / HGX Platforms",
                                        "AI Infrastructure",
                                        "High-performance computing platforms "
                                                + "for AI workloads."
                                )
                        ),

                        List.of(
                                "Global",
                                "United States",
                                "APAC",
                                "Europe"
                        ),

                        List.of(
                                "Cloud Providers",
                                "Technology Companies",
                                "Enterprise",
                                "Research Organizations"
                        ),

                        List.of(
                                "Leadership in accelerated computing and AI infrastructure.",
                                "Broad AI hardware and software ecosystem."
                        ),

                        List.of(
                                "High exposure to semiconductor supply capacity "
                                        + "and rapid product cycles."
                        ),

                        List.of(
                                "Strategic AI collaboration can expand "
                                        + "enterprise AI adoption and AI infrastructure services."
                        ),

                        List.of(
                                "Intense competition and rapid technological "
                                        + "change in AI accelerators."
                        )
                );


        // ========================================================
        // SAP SE
        // PARTNER
        // ========================================================
        //
        // German VAT ID:
        // DE143454214
        //
        // Employees:
        // 111,397 as of 31 December 2025
        //
        // ========================================================

        CompanyProfile sap =
                verifiedProfile(

                        SAP_ID,

                        "SAP SE",

                        "SAP",

                        "DE143454214",

                        111397,

                        "111,397 employees (31 Dec 2025)",

                        "https://www.sap.com",

                        List.of(
                                "info@sap.com"
                        ),

                        List.of(
                                "+49 6227 7-47474"
                        ),

                        "Dietmar-Hopp-Allee 16, "
                                + "69190 Walldorf, Germany",

                        List.of(
                                "Enterprise Software",
                                "ERP",
                                "Cloud",
                                "Business AI"
                        ),

                        "Enterprise software company providing ERP, "
                                + "cloud business applications, "
                                + "data platforms and business AI solutions.",

                        List.of(

                                product(
                                        "SAP S/4HANA",
                                        "ERP",
                                        "Enterprise resource planning platform."
                                ),

                                product(
                                        "SAP Business AI",
                                        "Artificial Intelligence",
                                        "AI capabilities embedded "
                                                + "in enterprise business processes."
                                ),

                                product(
                                        "SAP Business Technology Platform",
                                        "Cloud Platform",
                                        "Data, integration, analytics "
                                                + "and application platform."
                                )
                        ),

                        List.of(
                                "Global",
                                "Europe",
                                "APAC",
                                "Vietnam"
                        ),

                        List.of(
                                "Enterprise",
                                "Manufacturing",
                                "Retail",
                                "Financial Services",
                                "Public Sector"
                        ),

                        List.of(
                                "Large enterprise application ecosystem.",
                                "Strong ERP and business-process expertise."
                        ),

                        List.of(
                                "Large enterprise implementations can require "
                                        + "significant transformation effort."
                        ),

                        List.of(
                                "Joint ERP, cloud and Business AI "
                                        + "transformation opportunities."
                        ),

                        List.of(
                                "Competition from global cloud "
                                        + "and enterprise software platforms."
                        )
                );


        // ========================================================
        // DELL TECHNOLOGIES
        // SUPPLIER
        // ========================================================
        //
        // U.S. EIN:
        // 80-0890963
        //
        // Employees:
        // Approximately 97,000 as of 30 January 2026
        //
        // ========================================================

        CompanyProfile dell =
                verifiedProfile(

                        DELL_ID,

                        "Dell Technologies Inc.",

                        "Dell Technologies",

                        "80-0890963",

                        97000,

                        "Approximately 97,000 employees (30 Jan 2026)",

                        "https://www.dell.com",

                        List.of(
                                "investor_relations@dell.com"
                        ),

                        List.of(
                                "+1 512 728 7800"
                        ),

                        "One Dell Way, "
                                + "Round Rock, TX 78682, USA",

                        List.of(
                                "Enterprise Infrastructure",
                                "Servers",
                                "Storage",
                                "Data Center",
                                "AI Infrastructure"
                        ),

                        "Technology infrastructure company providing servers, "
                                + "storage, client devices, data-center platforms "
                                + "and AI infrastructure.",

                        List.of(

                                product(
                                        "PowerEdge",
                                        "Servers",
                                        "Enterprise server platforms "
                                                + "for data-center and AI workloads."
                                ),

                                product(
                                        "Dell Storage",
                                        "Enterprise Storage",
                                        "Enterprise storage "
                                                + "and data-management infrastructure."
                                ),

                                product(
                                        "AI Infrastructure",
                                        "AI Infrastructure",
                                        "Infrastructure platforms supporting "
                                                + "enterprise AI workloads."
                                )
                        ),

                        List.of(
                                "Global",
                                "United States",
                                "APAC",
                                "Vietnam"
                        ),

                        List.of(
                                "Enterprise",
                                "Government",
                                "Cloud and Data Center Operators",
                                "Technology Companies"
                        ),

                        List.of(
                                "Broad enterprise infrastructure portfolio.",
                                "Strong server and data-center ecosystem."
                        ),

                        List.of(
                                "Hardware business is exposed "
                                        + "to component and supply-chain cycles."
                        ),

                        List.of(
                                "Infrastructure can support FPT-led data-center, "
                                        + "AI and enterprise solution stacks."
                        ),

                        List.of(
                                "Competitive infrastructure market "
                                        + "and rapid hardware refresh cycles."
                        )
                );


        // ========================================================
        // VIETNAM AIRLINES
        // CUSTOMER
        // ========================================================
        //
        // Enterprise registration no.:
        // 0100107518
        //
        // Official published workforce:
        // Approximately 6,000 people
        //
        // ========================================================

        CompanyProfile vietnamAirlines =
                verifiedProfile(

                        VIETNAM_AIRLINES_ID,

                        "Tổng công ty Hàng không Việt Nam - CTCP",

                        "Vietnam Airlines",

                        "0100107518",

                        6000,

                        "Approximately 6,000 employees "
                                + "(official statement, 15 May 2025)",

                        "https://www.vietnamairlines.com",

                        List.of(
                                "nhadautu@vietnamairlines.com"
                        ),

                        List.of(
                                "+84 24 3827 2289"
                        ),

                        "Số 200 Nguyễn Sơn, "
                                + "Phường Bồ Đề, Hà Nội, Việt Nam.",

                        List.of(
                                "Aviation",
                                "Air Transportation",
                                "Travel Services"
                        ),

                        "Airline group providing passenger air transportation, "
                                + "cargo services and related aviation services.",

                        List.of(

                                product(
                                        "Passenger Air Transportation",
                                        "Aviation",
                                        "Domestic and international passenger air services."
                                ),

                                product(
                                        "Air Cargo",
                                        "Logistics",
                                        "Air freight and cargo transportation services."
                                ),

                                product(
                                        "Digital Passenger Services",
                                        "Digital Services",
                                        "Digital channels supporting booking "
                                                + "and passenger experience."
                                )
                        ),

                        List.of(
                                "Vietnam",
                                "Asia",
                                "Europe",
                                "International"
                        ),

                        List.of(
                                "Passengers",
                                "Corporate Travelers",
                                "Cargo Customers",
                                "Travel Partners"
                        ),

                        List.of(
                                "Established national aviation brand.",
                                "Domestic and international route network."
                        ),

                        List.of(
                                "Operations are sensitive to fuel costs "
                                        + "and aviation demand cycles."
                        ),

                        List.of(
                                "Digital aviation, data, AI "
                                        + "and customer-experience modernization opportunities."
                        ),

                        List.of(
                                "High operational complexity "
                                        + "and strong regional airline competition."
                        )
                );


        // ========================================================
        // PVOIL
        // CUSTOMER
        // ========================================================
        //
        // Tax code:
        // 0305795054
        //
        // Official company overview:
        // More than 7,400 employees
        //
        // employeeCount stores 7400 because the current model
        // only accepts an integer.
        //
        // employeeTier preserves the "More than" qualifier.
        //
        // ========================================================

        CompanyProfile pvoil =
                verifiedProfile(

                        PVOIL_ID,

                        "Tổng Công ty Dầu Việt Nam - CTCP",

                        "PVOIL",

                        "0305795054",

                        7400,

                        "More than 7,400 employees "
                                + "(official current company overview)",

                        "https://www.pvoil.com.vn",

                        List.of(
                                "contact@pvoil.com.vn"
                        ),

                        List.of(
                                "+84 28 3910 6990"
                        ),

                        "Tầng 14-18, Tòa nhà PetroVietnam Tower, "
                                + "Số 1-5 Lê Duẩn, Phường Sài Gòn, "
                                + "Thành phố Hồ Chí Minh, Việt Nam.",

                        List.of(
                                "Energy",
                                "Petroleum",
                                "Fuel Distribution",
                                "Retail"
                        ),

                        "Petroleum company operating import, export, "
                                + "distribution, retail and related energy services.",

                        List.of(

                                product(
                                        "Petroleum Distribution",
                                        "Energy",
                                        "Wholesale and distribution "
                                                + "of petroleum products."
                                ),

                                product(
                                        "Fuel Retail Network",
                                        "Retail",
                                        "Retail fuel distribution "
                                                + "through service-station networks."
                                ),

                                product(
                                        "Energy Operations",
                                        "Energy Services",
                                        "Operational services supporting "
                                                + "petroleum supply and distribution."
                                )
                        ),

                        List.of(
                                "Vietnam"
                        ),

                        List.of(
                                "Consumers",
                                "Transport Companies",
                                "Industrial Customers",
                                "Enterprise"
                        ),

                        List.of(
                                "Large petroleum distribution network in Vietnam.",
                                "Strong fuel retail "
                                        + "and enterprise distribution presence."
                        ),

                        List.of(
                                "Business performance is exposed "
                                        + "to energy-price and regulatory movements."
                        ),

                        List.of(
                                "Digital transformation, data "
                                        + "and enterprise operations modernization opportunities."
                        ),

                        List.of(
                                "Energy transition and changing mobility patterns "
                                        + "may reshape petroleum demand."
                        )
                );


        // ========================================================
        // MITSUBISHI MOTORS CORPORATION
        // POTENTIAL PARTNER
        // ========================================================
        //
        // Japanese Corporate Number:
        // 7010401029044
        //
        // Employees:
        // 27,695 consolidated employees as of 18 June 2026
        //
        // No current public general / IR email is published
        // on the official global IR inquiry page.
        //
        // Therefore emails = List.of()
        //
        // DO NOT generate or guess an email.
        //
        // ========================================================

        CompanyProfile mitsubishiMotors =
                verifiedProfile(

                        MITSUBISHI_MOTORS_ID,

                        "MITSUBISHI MOTORS CORPORATION",

                        "Mitsubishi Motors",

                        "7010401029044",

                        27695,

                        "27,695 consolidated employees (18 Jun 2026)",

                        "https://www.mitsubishi-motors.com",

                        List.of(),

                        List.of(
                                "+81 3 3456 1111"
                        ),

                        "1-21, Shibaura 3-chome, "
                                + "Minato-ku, Tokyo 108-8410, Japan",

                        List.of(
                                "Automotive",
                                "Mobility",
                                "Automotive Software",
                                "Manufacturing"
                        ),

                        "Automotive manufacturer engaged in development, "
                                + "production and sales of vehicles and vehicle parts, "
                                + "together with related financial businesses.",

                        List.of(

                                product(
                                        "Passenger Vehicles",
                                        "Automotive",
                                        "Passenger vehicle portfolio for global markets."
                                ),

                                product(
                                        "PHEV / Electrified Vehicles",
                                        "Automotive Technology",
                                        "Electrified vehicle technologies and products."
                                ),

                                product(
                                        "Connected Vehicle Technology",
                                        "Automotive Technology",
                                        "Digital and connected capabilities for vehicles."
                                )
                        ),

                        List.of(
                                "Japan",
                                "Asia",
                                "Global"
                        ),

                        List.of(
                                "Consumers",
                                "Automotive Dealers",
                                "Fleet Customers",
                                "Mobility Ecosystem"
                        ),

                        List.of(
                                "Established global automotive manufacturing capability.",
                                "Strong brand presence in Asian automotive markets."
                        ),

                        List.of(
                                "Automotive transformation requires significant "
                                        + "software and electrification investment."
                        ),

                        List.of(
                                "Potential for broader automotive software "
                                        + "and digital collaboration with FPT."
                        ),

                        List.of(
                                "Strong competition in electrified, connected "
                                        + "and software-defined vehicle markets."
                        )
                );


        // ========================================================
        // TATA CONSULTANCY SERVICES
        // COMPETITOR
        // ========================================================
        //
        // India PAN:
        // AAACR4849R
        //
        // Employees:
        // 593,798 as of 30 June 2026
        //
        // ========================================================

        CompanyProfile tcs =
                verifiedProfile(

                        TCS_ID,

                        "Tata Consultancy Services Limited",

                        "Tata Consultancy Services",

                        "AAACR4849R",

                        593798,

                        "593,798 employees (30 Jun 2026)",

                        "https://www.tcs.com",

                        List.of(
                                "Investor.Relations@tcs.com"
                        ),

                        List.of(
                                "+91 22 6778 9595"
                        ),

                        "9th Floor, Nirmal Building, "
                                + "Nariman Point, Mumbai 400 021, India",

                        List.of(
                                "Information Technology",
                                "IT Services",
                                "Consulting",
                                "Digital Transformation"
                        ),

                        "Global IT services and consulting company providing "
                                + "software engineering, cloud, enterprise transformation, "
                                + "data and digital services.",

                        List.of(

                                product(
                                        "IT Services",
                                        "IT Services",
                                        "Application development "
                                                + "and global technology services."
                                ),

                                product(
                                        "Cloud Transformation",
                                        "Cloud",
                                        "Enterprise cloud transformation "
                                                + "and modernization services."
                                ),

                                product(
                                        "Consulting",
                                        "Consulting",
                                        "Business and technology consulting for enterprises."
                                )
                        ),

                        List.of(
                                "Global",
                                "India",
                                "North America",
                                "Europe",
                                "APAC"
                        ),

                        List.of(
                                "Large Enterprise",
                                "Banking",
                                "Manufacturing",
                                "Retail",
                                "Telecommunications"
                        ),

                        List.of(
                                "Very large global delivery organization.",
                                "Broad enterprise client base across industries."
                        ),

                        List.of(
                                "Large organizational scale "
                                        + "can increase operating complexity."
                        ),

                        List.of(
                                "Expansion of AI-led services "
                                        + "creates new market opportunities."
                        ),

                        List.of(
                                "Competes with FPT in global IT services, "
                                        + "outsourcing and digital transformation markets."
                        )
                );


        // ========================================================
        // INFOSYS
        // COMPETITOR
        // ========================================================
        //
        // India PAN:
        // AAACI4798L
        //
        // Employees:
        // 328,062 as of 30 June 2026
        //
        // ========================================================

        CompanyProfile infosys =
                verifiedProfile(

                        INFOSYS_ID,

                        "Infosys Limited",

                        "Infosys",

                        "AAACI4798L",

                        328062,

                        "328,062 employees (30 Jun 2026)",

                        "https://www.infosys.com",

                        List.of(
                                "investors@infosys.com"
                        ),

                        List.of(
                                "+91 80 2852 0261"
                        ),

                        "Electronics City, Hosur Road, "
                                + "Bengaluru 560 100, India",

                        List.of(
                                "Information Technology",
                                "IT Services",
                                "Consulting",
                                "Cloud",
                                "Artificial Intelligence"
                        ),

                        "Global digital services and consulting company "
                                + "providing software, cloud, data, AI, engineering "
                                + "and enterprise transformation services.",

                        List.of(

                                product(
                                        "Digital Services",
                                        "IT Services",
                                        "Digital engineering "
                                                + "and enterprise technology services."
                                ),

                                product(
                                        "Cloud Services",
                                        "Cloud",
                                        "Cloud migration, modernization "
                                                + "and managed services."
                                ),

                                product(
                                        "AI and Data Services",
                                        "Artificial Intelligence",
                                        "AI, analytics and data transformation services."
                                )
                        ),

                        List.of(
                                "Global",
                                "India",
                                "North America",
                                "Europe",
                                "APAC"
                        ),

                        List.of(
                                "Large Enterprise",
                                "Financial Services",
                                "Manufacturing",
                                "Retail",
                                "Telecommunications"
                        ),

                        List.of(
                                "Strong global IT services and consulting presence.",
                                "Large enterprise client portfolio."
                        ),

                        List.of(
                                "Exposure to enterprise technology spending cycles."
                        ),

                        List.of(
                                "Enterprise AI and cloud transformation "
                                        + "continue to expand addressable markets."
                        ),

                        List.of(
                                "Competes with FPT for global technology services, "
                                        + "software engineering "
                                        + "and digital-transformation engagements."
                        )
                );


        // ========================================================
        // SAVE ALL COMPANY PROFILES
        // ========================================================

        companyProfileRepository.saveAll(

                List.of(
                        fpt,
                        nvidia,
                        sap,
                        dell,
                        vietnamAirlines,
                        pvoil,
                        mitsubishiMotors,
                        tcs,
                        infosys
                )
        );


        // ========================================================
        // CREATE INITIAL VERSION FOR FPT
        // ========================================================

        if (
                versionRepository
                        .findByCompanyProfileIdAndVersion(
                                fpt.getId(),
                                fpt.getVersion()
                        )
                        .isEmpty()
        ) {

            CompanyProfileVersion version =

                    CompanyProfileVersion.builder()

                            .companyProfileId(
                                    fpt.getId()
                            )

                            .companyId(
                                    fpt.getCompanyId()
                            )

                            .version(
                                    fpt.getVersion()
                            )

                            .snapshot(
                                    objectMapper.convertValue(
                                            fpt,
                                            new TypeReference<Map<String, Object>>() {
                                            }
                                    )
                            )

                            .changeSummary(
                                    "Initial verified FPT-centric seed"
                            )

                            .createdBy(
                                    -1L
                            )

                            .build();

            versionRepository.save(version);
        }
    }


    // ============================================================
    // VERIFIED COMPANY PROFILE BUILDER
    //
    // IMPORTANT:
    //
    // No dummy tax code
    // No dummy email
    // No dummy phone
    // No dummy address
    // No hard-coded generic employee count
    //
    // Every value is explicitly passed from a verified profile.
    // ============================================================

    private CompanyProfile verifiedProfile(

            String id,

            String legalName,

            String tradeName,

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

                .id(
                        id
                )

                .companyId(
                        id
                )

                .identity(

                        CompanyProfile.Identity.builder()

                                .legalName(
                                        legalName
                                )

                                .tradeName(
                                        tradeName
                                )

                                .taxCode(
                                        taxIdentifier
                                )

                                .build()
                )

                .companySize(

                        CompanyProfile.CompanySize.builder()

                                .employeeCount(
                                        employeeCount
                                )

                                .employeeTier(
                                        employeeTier
                                )

                                .build()
                )

                .business(

                        CompanyProfile.Business.builder()

                                .industries(
                                        industries
                                )

                                .businessModel(
                                        businessModel
                                )

                                .products(
                                        products
                                )

                                .markets(
                                        markets
                                )

                                .targetCustomers(
                                        targetCustomers
                                )

                                .build()
                )

                .contact(

                        CompanyProfile.Contact.builder()

                                .website(
                                        website
                                )

                                .emails(
                                        emails
                                )

                                .phones(
                                        phones
                                )

                                .addresses(

                                        List.of(

                                                CompanyProfile.Address.builder()

                                                        .fullAddress(
                                                                address
                                                        )

                                                        .build()
                                        )
                                )

                                .build()
                )

                .insights(

                        CompanyProfile.Insights.builder()

                                .strengths(
                                        strengths
                                )

                                .weaknesses(
                                        weaknesses
                                )

                                .opportunities(
                                        opportunities
                                )

                                .threats(
                                        threats
                                )

                                .build()
                )

                .reviewStatus(
                        "APPROVED"
                )

                .sourceRefs(

                        CompanyProfile.SourceRefs.builder()

                                .projectIds(
                                        Set.of(PROJECT_ID)
                                )

                                .build()
                )

                .metadata(

                        CompanyProfile.Metadata.builder()

                                .createdBy(
                                        "system-seed"
                                )

                                .build()
                )

                .build();
    }


    // ============================================================
    // PRODUCT BUILDER
    // ============================================================

    private CompanyProfile.Product product(

            String name,

            String category,

            String description

    ) {

        return CompanyProfile.Product.builder()

                .name(
                        name
                )

                .category(
                        category
                )

                .description(
                        description
                )

                .build();
    }


    // ============================================================
    // REMOVE LEGACY MONGODB PROFILES
    // ============================================================

    private void cleanupLegacyMongoProfiles() {

        for (
                String legacyCompanyId :
                LEGACY_COMPANY_IDS
        ) {

            companyProfileRepository

                    .findById(
                            legacyCompanyId
                    )

                    .ifPresent(
                            companyProfileRepository::delete
                    );
        }
    }


    // ============================================================
    // NEO4J
    // ============================================================

    private void seedNeo4jGraph() {

        // ========================================================
        // STEP 1
        // DELETE OLD SEED RELATIONSHIPS
        // ========================================================

        clearSeededRelationships();


        // ========================================================
        // STEP 2
        // DELETE OLD DEMO COMPANY NODES
        // ========================================================

        cleanupLegacyNeo4jCompanies();


        // ========================================================
        // STEP 3
        // CREATE / UPDATE COMPANY NODES
        // ========================================================

        mergeCompanyNode(
                FPT_ID,
                "FPT Corporation",
                "Information Technology"
        );

        mergeCompanyNode(
                NVIDIA_ID,
                "NVIDIA Corporation",
                "Artificial Intelligence & Semiconductors"
        );

        mergeCompanyNode(
                SAP_ID,
                "SAP SE",
                "Enterprise Software"
        );

        mergeCompanyNode(
                DELL_ID,
                "Dell Technologies",
                "Enterprise Infrastructure"
        );

        mergeCompanyNode(
                VIETNAM_AIRLINES_ID,
                "Vietnam Airlines",
                "Aviation"
        );

        mergeCompanyNode(
                PVOIL_ID,
                "PVOIL",
                "Energy & Petroleum"
        );

        mergeCompanyNode(
                MITSUBISHI_MOTORS_ID,
                "Mitsubishi Motors Corporation",
                "Automotive"
        );

        mergeCompanyNode(
                TCS_ID,
                "Tata Consultancy Services",
                "Information Technology Services"
        );

        mergeCompanyNode(
                INFOSYS_ID,
                "Infosys Limited",
                "Information Technology Services"
        );


        // ========================================================
        // FPT-CENTRIC RELATIONSHIPS
        //
        // ONLY:
        //
        // FPT -> EXTERNAL COMPANY
        //
        // NO RELATIONSHIPS BETWEEN EXTERNAL COMPANIES.
        // ========================================================


        // -------------------------
        // PARTNERS
        // -------------------------

        createRelationship(
                FPT_ID,
                NVIDIA_ID,
                "PARTNER_WITH"
        );

        createRelationship(
                FPT_ID,
                SAP_ID,
                "PARTNER_WITH"
        );


        // -------------------------
        // SUPPLIER
        // -------------------------

        createRelationship(
                FPT_ID,
                DELL_ID,
                "SUPPLIER_OF"
        );


        // -------------------------
        // CUSTOMERS
        // -------------------------

        createRelationship(
                FPT_ID,
                VIETNAM_AIRLINES_ID,
                "CUSTOMER_OF"
        );

        createRelationship(
                FPT_ID,
                PVOIL_ID,
                "CUSTOMER_OF"
        );


        // -------------------------
        // POTENTIAL PARTNER
        // -------------------------

        createRelationship(
                FPT_ID,
                MITSUBISHI_MOTORS_ID,
                "POTENTIAL_PARTNER_OF"
        );


        // -------------------------
        // COMPETITORS
        // -------------------------

        createRelationship(
                FPT_ID,
                TCS_ID,
                "COMPETITOR_OF"
        );

        createRelationship(
                FPT_ID,
                INFOSYS_ID,
                "COMPETITOR_OF"
        );


        // ========================================================
        // REMOVE LEGACY APMS DEMO ORGANIZATION
        // ========================================================

        String deleteLegacyOrganization =
                """
                MATCH (
                    c:Company {
                        companyId: '6a31a0000000000000000000'
                    }
                )
                DETACH DELETE c
                """;

        neo4jClient
                .query(
                        deleteLegacyOrganization
                )
                .run();
    }


    // ============================================================
    // CLEAR PREVIOUS SEEDED RELATIONSHIPS
    // ============================================================

    private void clearSeededRelationships() {

        String cypher =
                """
                MATCH (:Company)-[r]->(:Company)

                WHERE
                    r.projectId = $projectId
                    AND r.confirmedBy = 'system-seed'

                DELETE r
                """;

        neo4jClient
                .query(
                        cypher
                )

                .bind(
                        PROJECT_ID
                )

                .to(
                        "projectId"
                )

                .run();
    }


    // ============================================================
    // CLEAN UP OLD NEO4J COMPANY NODES
    // ============================================================

    private void cleanupLegacyNeo4jCompanies() {

        String cypher =
                """
                MATCH (c:Company)

                WHERE
                    c.companyId IN $legacyCompanyIds

                DETACH DELETE c
                """;

        neo4jClient
                .query(
                        cypher
                )

                .bind(
                        LEGACY_COMPANY_IDS
                )

                .to(
                        "legacyCompanyIds"
                )

                .run();
    }


    // ============================================================
    // MERGE COMPANY NODE
    // ============================================================

    private void mergeCompanyNode(

            String companyId,

            String name,

            String industry

    ) {

        String cypher =
                """
                MERGE (
                    c:Company {
                        companyId: $companyId
                    }
                )

                ON CREATE SET

                    c.name = $name,

                    c.industry = $industry,

                    c.createdAt = datetime()

                ON MATCH SET

                    c.name = $name,

                    c.industry = $industry,

                    c.updatedAt = datetime()
                """;

        neo4jClient
                .query(
                        cypher
                )

                .bindAll(
                        Map.of(
                                "companyId",
                                companyId,

                                "name",
                                name,

                                "industry",
                                industry
                        )
                )

                .run();
    }


    // ============================================================
    // CREATE RELATIONSHIP
    // ============================================================

    private void createRelationship(

            String sourceId,

            String targetId,

            String relType

    ) {

        String cypher =
                String.format(

                        """
                        MATCH (
                            c1:Company {
                                companyId: $sourceId
                            }
                        )

                        MATCH (
                            c2:Company {
                                companyId: $targetId
                            }
                        )

                        MERGE (
                            c1
                        )-[r:%s]->(
                            c2
                        )

                        SET
                            r.confidenceScore = 1.0,

                            r.confirmedBy = 'system-seed',

                            r.confirmedAt = datetime(),

                            r.projectId = $projectId
                        """,

                        relType
                );

        neo4jClient
                .query(
                        cypher
                )

                .bindAll(
                        Map.of(
                                "sourceId",
                                sourceId,

                                "targetId",
                                targetId,

                                "projectId",
                                PROJECT_ID
                        )
                )

                .run();
    }


    // ============================================================
    // SQL SERVER
    // SCORE SNAPSHOTS
    // ============================================================

    private void seedScoreSnapshots(
            Project project
    ) {

        // ========================================================
        // FPT IS OWNER
        // Do not seed a new FPT score.
        // ========================================================


        // PARTNER - NVIDIA
        ensureScore(
                project,
                NVIDIA_ID,
                95,
                15,
                30,
                92
        );


        // PARTNER - SAP
        ensureScore(
                project,
                SAP_ID,
                92,
                18,
                28,
                88
        );


        // SUPPLIER - DELL
        ensureScore(
                project,
                DELL_ID,
                84,
                20,
                32,
                76
        );


        // CUSTOMER - VIETNAM AIRLINES
        ensureScore(
                project,
                VIETNAM_AIRLINES_ID,
                86,
                10,
                38,
                85
        );


        // CUSTOMER - PVOIL
        ensureScore(
                project,
                PVOIL_ID,
                82,
                12,
                42,
                78
        );


        // POTENTIAL PARTNER - MITSUBISHI MOTORS
        ensureScore(
                project,
                MITSUBISHI_MOTORS_ID,
                90,
                28,
                36,
                72
        );


        // COMPETITOR - TCS
        ensureScore(
                project,
                TCS_ID,
                58,
                95,
                36,
                20
        );


        // COMPETITOR - INFOSYS
        ensureScore(
                project,
                INFOSYS_ID,
                60,
                92,
                34,
                22
        );
    }


    // ============================================================
    // ENSURE SCORE
    // ============================================================

    private void ensureScore(

            Project project,

            String companyId,

            int fit,

            int comp,

            int risk,

            int rel

    ) {

        if (
                !scoreSnapshotRepository
                        .findByCompanyIdAndEvaluatedRoleIsNullOrderByCreatedAtDesc(
                                companyId
                        )
                        .isEmpty()
        ) {

            return;
        }

        scoreSnapshotRepository.save(

                ScoreSnapshot.builder()

                        .companyId(
                                companyId
                        )

                        .candidateId(
                                "seed-" + companyId
                        )

                        .project(
                                project
                        )

                        .partnerFitScore(
                                fit
                        )

                        .competitionLevel(
                                comp
                        )

                        .riskLevel(
                                risk
                        )

                        .relationshipStrength(
                                rel
                        )

                        .totalScore(
                                fit
                                        + comp
                                        + risk
                                        + rel
                        )

                        .ruleVersion(
                                "demo-v4-verified-fpt-centric"
                        )

                        .generatedByAccount(
                                null
                        )

                        .build()
        );
    }
}