package com.apms.config;

import com.apms.common.enums.MemberRole;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectMember;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
@Order(2) // Run after DataSeeder creates accounts
public class AssistantDemoDataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final org.springframework.data.neo4j.core.Neo4jClient neo4jClient;
    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyProfileVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    private static final String PROJECT_ID = "1";
    
    private static final String FPT_ID = "6a31a0000000000000000001";
    private static final String CMC_ID = "6a31a0000000000000000002";
    private static final String VIETTEL_ID = "6a31a0000000000000000003";
    private static final String VNG_ID = "6a31a0000000000000000004";
    private static final String MOMO_ID = "6a31a0000000000000000005";
    private static final String VNPT_ID = "6a31a0000000000000000006";
    private static final String AWS_ID = "6a31a0000000000000000007";
    private static final String MICROSOFT_ID = "6a31a0000000000000000008";
    private static final String RETAILPLUS_ID = "6a31a0000000000000000009";

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Running AssistantDemoDataSeeder to seed approved data...");

        Project project = ensureProjectExists();
        
        seedCompanyProfiles();
        seedNeo4jGraph();
        seedScoreSnapshots(project);

        log.info("");
        log.info("AI Assistant demo data seeded:");
        log.info("Demo Org: {}", ownerOrganizationService.getOwnerCompanyId());
        log.info("FPT: {}", FPT_ID);
        log.info("CMC: {}", CMC_ID);
        log.info("Viettel: {}", VIETTEL_ID);
        log.info("VNG: {}", VNG_ID);
        log.info("MoMo: {}", MOMO_ID);
        log.info("VNPT: {}", VNPT_ID);
        log.info("AWS Vietnam: {}", AWS_ID);
        log.info("Microsoft Vietnam: {}", MICROSOFT_ID);
        log.info("RetailPlus Vietnam: {}", RETAILPLUS_ID);
        log.info("");
    }

    private Project ensureProjectExists() {
        Account manager = accountRepository.findByEmail("manager@apms.com").orElseThrow();

        Project project = projectRepository.findById(1L).orElse(null);
        if (project == null) {
            project = Project.builder()
                    .projectName("AI Assistant Demo Project")
                    .projectType(ProjectType.RESEARCH_NEW_COMPANY)
                    .targetCompanyName("Vietnam Tech Industry")
                    .description("Project with approved data for AI assistant testing.")
                    .status(ProjectStatus.ACTIVE)
                    .createdByAccount(manager) // Manager creates the project, owner does NOT belong to it
                    .build();
            project = projectRepository.save(project);
        }

        // Ensure manager and staff have access, but not owner
        ensureMembership(project, "manager@apms.com", MemberRole.MANAGER);
        ensureMembership(project, "staff@apms.com", MemberRole.STAFF);

        return project;
    }

    private void ensureMembership(Project project, String email, MemberRole role) {
        accountRepository.findByEmail(email).ifPresent(account -> {
            boolean exists = projectMemberRepository.existsByProject_IdAndAccount_Id(project.getId(), account.getId());
            if (!exists) {
                projectMemberRepository.save(ProjectMember.builder()
                        .project(project)
                        .account(account)
                        .memberRole(role)
                        .build());
            }
        });
    }

    private void seedCompanyProfiles() {
        CompanyProfile fpt = CompanyProfile.builder()
                .id(FPT_ID)
                .companyId(FPT_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Công ty Cổ phần FPT")
                        .tradeName("FPT Corporation")
                        .taxCode("0101248141")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Information Technology", "Software Outsourcing", "AI", "Cloud Services"))
                        .businessModel("Global technology corporation providing software outsourcing, digital transformation, cloud, and AI solutions.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Software Outsourcing").category("IT Services").description("Custom software development and outsourcing services.").build(),
                                CompanyProfile.Product.builder().name("AI Solutions").category("Artificial Intelligence").description("AI platforms, machine learning, and automation solutions.").build(),
                                CompanyProfile.Product.builder().name("Cloud Services").category("Cloud").description("Cloud migration, managed cloud, and infrastructure services.").build()
                        ))
                        .markets(List.of("Vietnam", "Japan", "United States", "Europe", "APAC"))
                        .targetCustomers(List.of("Banking", "Manufacturing", "Healthcare", "Public Sector"))
                        .build())
                .companySize(CompanyProfile.CompanySize.builder()
                        .employeeTier(">40,000")
                        .employeeCount(40000)
                        .revenueTier("Large Enterprise")
                        .build())
                .contact(CompanyProfile.Contact.builder()
                        .website("https://fpt.com")
                        .emails(List.of("contact@fpt.com"))
                        .phones(List.of())
                        .addresses(List.of(CompanyProfile.Address.builder().fullAddress("Hanoi, Vietnam").build()))
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong technology capability in software outsourcing, AI, and cloud.", "Large global delivery network.", "Strong brand reputation in Vietnam."))
                        .weaknesses(List.of("Large organization may slow down decision-making.", "High dependency on major international markets."))
                        .opportunities(List.of("Potential collaboration in AI transformation projects.", "Expansion into enterprise automation and cloud modernization."))
                        .threats(List.of("Strong competition from regional and global IT service providers."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile cmc = CompanyProfile.builder()
                .id(CMC_ID)
                .companyId(CMC_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Công ty Cổ phần Tập đoàn Công nghệ CMC")
                        .tradeName("CMC Corporation")
                        .taxCode("0100244112")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Information Technology", "Cybersecurity", "Cloud Services", "Telecommunications"))
                        .businessModel("Vietnamese technology group providing IT services, cybersecurity, cloud infrastructure, and digital transformation solutions.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("CMC Cloud").category("Cloud").description("Cloud infrastructure and enterprise cloud services.").build(),
                                CompanyProfile.Product.builder().name("Cybersecurity Services").category("Security").description("Security monitoring, protection, and consulting services.").build(),
                                CompanyProfile.Product.builder().name("Digital Transformation Consulting").category("IT Services").description("Enterprise digital transformation services.").build()
                        ))
                        .markets(List.of("Vietnam", "APAC"))
                        .targetCustomers(List.of("Enterprise", "Government", "Banking", "Telecommunications"))
                        .build())
                .companySize(CompanyProfile.CompanySize.builder()
                        .employeeTier("5,000-10,000")
                        .employeeCount(5000)
                        .revenueTier("Large Enterprise")
                        .build())
                .contact(CompanyProfile.Contact.builder()
                        .website("https://cmc.com.vn")
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong domestic technology ecosystem.", "Good capability in cloud and cybersecurity.", "Suitable for enterprise digital transformation projects."))
                        .weaknesses(List.of("Smaller global presence compared to FPT.", "Market positioning may overlap with other IT service providers."))
                        .opportunities(List.of("Potential cooperation in cloud and security services.", "Growing demand for local digital infrastructure."))
                        .threats(List.of("Direct competition in IT services and cloud.", "Pressure from larger international technology companies."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile viettel = CompanyProfile.builder()
                .id(VIETTEL_ID)
                .companyId(VIETTEL_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Tập đoàn Công nghiệp - Viễn thông Quân đội")
                        .tradeName("Viettel Group")
                        .taxCode("0100109106")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Telecommunications", "Digital Infrastructure", "Cybersecurity", "Cloud", "Technology"))
                        .businessModel("Large telecommunications and technology group providing digital infrastructure, telecom, cybersecurity, cloud, and enterprise technology services.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Telecommunications Services").category("Telecom").description("Mobile, broadband, and telecom infrastructure services.").build(),
                                CompanyProfile.Product.builder().name("Viettel Cloud").category("Cloud").description("Cloud infrastructure and data center services.").build(),
                                CompanyProfile.Product.builder().name("Cybersecurity Solutions").category("Security").description("Cybersecurity monitoring and protection services.").build()
                        ))
                        .markets(List.of("Vietnam", "Southeast Asia", "Africa", "Latin America"))
                        .targetCustomers(List.of("Government", "Enterprise", "Telecommunications", "Public Sector"))
                        .build())
                .companySize(CompanyProfile.CompanySize.builder()
                        .employeeTier(">50,000")
                        .employeeCount(50000)
                        .revenueTier("Very Large Enterprise")
                        .build())
                .contact(CompanyProfile.Contact.builder()
                        .website("https://viettel.com.vn")
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Very strong infrastructure and telecommunications capability.", "Large-scale national and international operations.", "Strong cybersecurity and cloud infrastructure potential."))
                        .weaknesses(List.of("Enterprise partnership process may be complex.", "Large organization may require longer approval cycles."))
                        .opportunities(List.of("Potential collaboration in cloud infrastructure and cybersecurity.", "Strong fit for public sector and national-scale digital projects."))
                        .threats(List.of("May compete in digital infrastructure and cloud markets.", "High dependency on regulatory and public sector environment."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile vng = CompanyProfile.builder()
                .id(VNG_ID)
                .companyId(VNG_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Công ty Cổ phần VNG")
                        .tradeName("VNG Corporation")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Technology", "Digital Content", "Cloud Services", "AI"))
                        .businessModel("Vietnamese technology company providing digital platforms, cloud services, online services, and AI-related solutions.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Zalo Platform").category("Communication").build(),
                                CompanyProfile.Product.builder().name("VNG Cloud").category("Cloud").build(),
                                CompanyProfile.Product.builder().name("Digital Platform Services").category("Technology").build()
                        ))
                        .markets(List.of("Vietnam", "Southeast Asia"))
                        .targetCustomers(List.of("Consumers", "Enterprise", "Digital Businesses"))
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong digital platform ecosystem in Vietnam.", "Strong user base and local technology brand.", "Capability in cloud and digital products."))
                        .weaknesses(List.of("Business scope may overlap with other domestic technology companies.", "International enterprise presence is still more limited than larger global vendors."))
                        .opportunities(List.of("Potential collaboration in AI-enabled digital services.", "Potential expansion into enterprise cloud and platform services."))
                        .threats(List.of("Direct competition in digital platforms, cloud, and AI-enabled services."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile momo = CompanyProfile.builder()
                .id(MOMO_ID)
                .companyId(MOMO_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Công ty Cổ phần Dịch vụ Di động Trực tuyến")
                        .tradeName("MoMo")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Fintech", "Digital Payments", "Financial Services"))
                        .businessModel("Digital payment and financial services platform serving consumers, merchants, and enterprise partners.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Digital Wallet").category("Fintech").build(),
                                CompanyProfile.Product.builder().name("Payment Gateway").category("Payments").build(),
                                CompanyProfile.Product.builder().name("Merchant Services").category("Financial Services").build()
                        ))
                        .markets(List.of("Vietnam"))
                        .targetCustomers(List.of("Consumers", "Merchants", "Financial Institutions"))
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong digital payment ecosystem.", "Large consumer and merchant network.", "Good fit for fintech partnership opportunities."))
                        .weaknesses(List.of("Heavily dependent on financial regulation and consumer trust.", "Partnership integration may require compliance review."))
                        .opportunities(List.of("Potential partnership in payment integration and financial service distribution.", "Opportunity for co-developing digital customer engagement channels."))
                        .threats(List.of("Competition from banks, e-wallets, and super-app platforms."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile vnpt = CompanyProfile.builder()
                .id(VNPT_ID)
                .companyId(VNPT_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Tập đoàn Bưu chính Viễn thông Việt Nam")
                        .tradeName("VNPT")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Telecommunications", "Cloud", "Digital Infrastructure", "Public Sector Technology"))
                        .businessModel("Telecommunications and digital infrastructure provider offering connectivity, cloud, and digital transformation services.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Telecommunications Services").category("Telecom").build(),
                                CompanyProfile.Product.builder().name("VNPT Cloud").category("Cloud").build(),
                                CompanyProfile.Product.builder().name("Digital Government Solutions").category("Public Sector Technology").build()
                        ))
                        .markets(List.of("Vietnam"))
                        .targetCustomers(List.of("Government", "Enterprise", "Public Sector", "Telecommunications"))
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong telecom infrastructure.", "Strong public sector presence.", "Suitable for national-scale digital projects."))
                        .weaknesses(List.of("Enterprise processes may be slower due to large organization structure.", "Some technology services overlap with other telecom and cloud providers."))
                        .opportunities(List.of("Potential cooperation in digital infrastructure and public sector transformation.", "Opportunity for cloud and connectivity partnerships."))
                        .threats(List.of("Competition in telecom, cloud, and public sector digital solutions."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile aws = CompanyProfile.builder()
                .id(AWS_ID)
                .companyId(AWS_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Amazon Web Services Vietnam")
                        .tradeName("AWS Vietnam")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Cloud", "Infrastructure", "AI Services", "Enterprise Technology"))
                        .businessModel("Cloud infrastructure and platform provider supporting enterprise workloads, AI services, storage, compute, and managed cloud services.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Cloud Infrastructure").category("Cloud").build(),
                                CompanyProfile.Product.builder().name("AI and Machine Learning Services").category("AI").build(),
                                CompanyProfile.Product.builder().name("Data Storage and Analytics").category("Data Platform").build()
                        ))
                        .markets(List.of("Vietnam", "Global"))
                        .targetCustomers(List.of("Enterprise", "Startup", "Government", "Technology Companies"))
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong cloud infrastructure and AI service ecosystem.", "Scalable platform for enterprise workloads.", "Broad technical service portfolio."))
                        .weaknesses(List.of("Cost control can be challenging without cloud governance.", "Dependency on external cloud provider may increase vendor lock-in."))
                        .opportunities(List.of("Supplier relationship for cloud infrastructure and AI deployment.", "Potential support for scalable APMS hosting and analytics workloads."))
                        .threats(List.of("Cloud cost escalation and vendor dependency."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile ms = CompanyProfile.builder()
                .id(MICROSOFT_ID)
                .companyId(MICROSOFT_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Microsoft Vietnam")
                        .tradeName("Microsoft Vietnam")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Cloud", "Enterprise Software", "AI", "Productivity Tools"))
                        .businessModel("Enterprise software and cloud technology provider offering productivity tools, cloud infrastructure, AI services, and business applications.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Azure Cloud").category("Cloud").build(),
                                CompanyProfile.Product.builder().name("Microsoft 365").category("Productivity").build(),
                                CompanyProfile.Product.builder().name("AI Copilot Services").category("AI").build()
                        ))
                        .markets(List.of("Vietnam", "Global"))
                        .targetCustomers(List.of("Enterprise", "SME", "Government", "Education"))
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong enterprise software ecosystem.", "Strong AI and cloud integration capability.", "Good fit for enterprise digital transformation."))
                        .weaknesses(List.of("Subscription and licensing costs may be high for smaller teams.", "Some integrations may require technical expertise."))
                        .opportunities(List.of("Strategic partnership for AI, productivity, and cloud modernization.", "Potential support for enterprise collaboration workflows."))
                        .threats(List.of("Vendor dependency and licensing complexity."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        CompanyProfile retailplus = CompanyProfile.builder()
                .id(RETAILPLUS_ID)
                .companyId(RETAILPLUS_ID)
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Công ty Cổ phần RetailPlus Việt Nam")
                        .tradeName("RetailPlus Vietnam")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("Retail", "E-commerce", "Consumer Services"))
                        .businessModel("Retail and e-commerce company using technology platforms to manage online sales, customer engagement, and digital operations.")
                        .products(List.of(
                                CompanyProfile.Product.builder().name("Online Retail Platform").category("E-commerce").build(),
                                CompanyProfile.Product.builder().name("Customer Loyalty Program").category("Retail Technology").build(),
                                CompanyProfile.Product.builder().name("Digital Sales Channels").category("Consumer Services").build()
                        ))
                        .markets(List.of("Vietnam"))
                        .targetCustomers(List.of("Consumers", "Retail Customers", "Online Shoppers"))
                        .build())
                .insights(CompanyProfile.Insights.builder()
                        .strengths(List.of("Strong customer-facing retail operations.", "Useful case for applying AI-driven customer analytics.", "Potential customer for business intelligence and automation services."))
                        .weaknesses(List.of("Technology capability may depend on external vendors.", "Retail margin pressure may limit technology budget."))
                        .opportunities(List.of("Potential customer for APMS-related analytics, automation, and partner intelligence solutions.", "Opportunity to support digital retail operations."))
                        .threats(List.of("Competitive pressure from larger e-commerce platforms."))
                        .build())
                .reviewStatus("APPROVED")
                .sourceRefs(CompanyProfile.SourceRefs.builder().projectIds(Set.of(PROJECT_ID)).build())
                .metadata(CompanyProfile.Metadata.builder().createdBy("system-seed").build())
                .build();

        companyProfileRepository.saveAll(List.of(fpt, cmc, viettel, vng, momo, vnpt, aws, ms, retailplus));

        if (versionRepository.findByCompanyProfileIdAndVersion(fpt.getId(), fpt.getVersion()).isEmpty()) {
            CompanyProfileVersion version = CompanyProfileVersion.builder()
                    .companyProfileId(fpt.getId())
                    .companyId(fpt.getCompanyId())
                    .version(fpt.getVersion())
                    .snapshot(objectMapper.convertValue(fpt, new TypeReference<Map<String, Object>>() {}))
                    .changeSummary("Initial seed")
                    .createdBy(-1L)
                    .build();
            versionRepository.save(version);
        }
    }

    private void seedNeo4jGraph() {
        mergeCompanyNode(FPT_ID, "FPT Corporation", "Information Technology");
        mergeCompanyNode(CMC_ID, "CMC Corporation", "Information Technology");
        mergeCompanyNode(VIETTEL_ID, "Viettel Group", "Telecommunications");
        mergeCompanyNode(VNG_ID, "VNG Corporation", "Technology");
        mergeCompanyNode(MOMO_ID, "MoMo", "Fintech");
        mergeCompanyNode(VNPT_ID, "VNPT", "Telecommunications");
        mergeCompanyNode(AWS_ID, "AWS Vietnam", "Cloud");
        mergeCompanyNode(MICROSOFT_ID, "Microsoft Vietnam", "Enterprise Software");
        mergeCompanyNode(RETAILPLUS_ID, "RetailPlus Vietnam", "Retail");

        // Relationships connected to owner organization (FPT)
        createRelationship(FPT_ID, MICROSOFT_ID, "PARTNER_WITH");
        createRelationship(FPT_ID, CMC_ID, "COMPETITOR_OF");
        createRelationship(FPT_ID, VNG_ID, "COMPETITOR_OF");
        createRelationship(FPT_ID, VNPT_ID, "COMPETITOR_OF");
        createRelationship(FPT_ID, VIETTEL_ID, "POTENTIAL_PARTNER_OF");
        createRelationship(FPT_ID, MOMO_ID, "POTENTIAL_PARTNER_OF");
        createRelationship(FPT_ID, AWS_ID, "SUPPLIER_OF");
        createRelationship(FPT_ID, RETAILPLUS_ID, "CUSTOMER_OF");

        // Inter-company relationships
        createRelationship(CMC_ID, VNPT_ID, "COMPETITOR_OF");
        createRelationship(VIETTEL_ID, VNPT_ID, "COMPETITOR_OF");
        createRelationship(AWS_ID, MICROSOFT_ID, "PARTNER_WITH");
        createRelationship(MOMO_ID, RETAILPLUS_ID, "POTENTIAL_PARTNER_OF");

        // Clean up legacy APMS Demo Organization node
        String deleteCypher = "MATCH (c:Company {companyId: '6a31a0000000000000000000'}) DETACH DELETE c";
        neo4jClient.query(deleteCypher).run();
    }

    private void mergeCompanyNode(String companyId, String name, String industry) {
        String cypher = """
            MERGE (c:Company {companyId: $companyId})
            ON CREATE SET c.name = $name, c.industry = $industry, c.createdAt = datetime()
            ON MATCH SET c.name = $name, c.industry = $industry, c.updatedAt = datetime()
            """;
        neo4jClient.query(cypher)
                .bindAll(Map.of("companyId", companyId, "name", name, "industry", industry))
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
                .bindAll(Map.of("sourceId", sourceId, "targetId", targetId, "projectId", PROJECT_ID))
                .run();
    }

    private void seedScoreSnapshots(Project project) {
        // Do not seed new FPT score snapshots; preserve any historical ones in the DB
        ensureScore(project, CMC_ID, 72, 78, 45, 40);
        ensureScore(project, VIETTEL_ID, 80, 50, 40, 62);
        ensureScore(project, VNG_ID, 68, 82, 48, 38);
        ensureScore(project, MOMO_ID, 84, 35, 42, 66);
        ensureScore(project, VNPT_ID, 75, 70, 44, 55);
        ensureScore(project, AWS_ID, 86, 20, 50, 70);
        ensureScore(project, MICROSOFT_ID, 90, 18, 38, 76);
        ensureScore(project, RETAILPLUS_ID, 65, 25, 55, 58);
    }
    
    private void ensureScore(Project project, String companyId, int fit, int comp, int risk, int rel) {
        if (!scoreSnapshotRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).isEmpty()) {
            return;
        }
        scoreSnapshotRepository.save(ScoreSnapshot.builder()
                .companyId(companyId)
                .candidateId("seed-" + companyId)
                .project(project)
                .partnerFitScore(fit)
                .competitionLevel(comp)
                .riskLevel(risk)
                .relationshipStrength(rel)
                .totalScore(fit + comp + risk + rel)
                .ruleVersion("demo-v2")
                .generatedByAccount(null)
                .build());
    }
}
