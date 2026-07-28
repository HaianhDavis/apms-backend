package com.apms.config;

import com.apms.common.enums.MemberRole;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.domain.graph.CompanyNode;
import com.apms.domain.graph.repository.neo4j.CompanyNodeRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectMember;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Seeds realistic business data (CompanyProfiles, Projects, Graph nodes) for demo/dev.
 * Runs AFTER DataSeeder (Order 2) so user accounts already exist.
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class BusinessDataSeeder implements CommandLineRunner {

    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyNodeRepository companyNodeRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;

    @Override
    public void run(String... args) {
        try {
            boolean hasProfiles = companyProfileRepository.count() > 0;
            List<CompanyProfile> profiles;
            if (!hasProfiles) {
                log.info("BusinessDataSeeder: Seeding business data to MongoDB...");
                profiles = buildProfiles();
                companyProfileRepository.saveAll(profiles);
                log.info("Saved {} company profiles to MongoDB.", profiles.size());
            } else {
                profiles = companyProfileRepository.findAll();
            }

            if (companyNodeRepository.count() == 0) {
                List<CompanyNode> nodes = buildNodes(profiles);
                for (CompanyNode node : nodes) {
                    companyNodeRepository.save(node);
                }
                log.info("Saved {} company nodes to Neo4j.", nodes.size());
            }

            if (projectRepository.count() == 0) {
                Account managerAccount = accountRepository.findByEmail("manager@apms.com").orElse(null);
                Account staffAccount = accountRepository.findByEmail("staff@apms.com").orElse(null);
                if (managerAccount != null && staffAccount != null) {
                    List<Project> projects = buildProjects(managerAccount, staffAccount, profiles);
                    projectRepository.saveAll(projects);
                    log.info("Saved {} projects to SQL.", projects.size());
                } else {
                    log.warn("Manager or Staff account not found, skipping project seeding.");
                }
            }
        } catch (Exception e) {
            log.warn("BusinessDataSeeder failed. Skipping seeding.", e);
        }

        log.info("BusinessDataSeeder: Done.");
    }

    // ─────────────────────────────────────────────────────────────
    // Company Profiles (MongoDB)
    // ─────────────────────────────────────────────────────────────

    private List<CompanyProfile> buildProfiles() {
        return Arrays.asList(
            profile("FPT Corporation", "FPT Corp", "0101248141",
                "Công nghệ thông tin", "B2B",
                List.of("Dịch vụ CNTT", "Outsourcing phần mềm", "Giải pháp AI"),
                List.of("Việt Nam", "Nhật Bản", "Mỹ"),
                "LARGE", 50000, "OVER_1T", "https://fpt.com",
                "Hà Nội, Việt Nam", "HN", "VERIFIED",
                List.of("partner", "strategic")),

            profile("VNPT Group", "VNPT", "0106026467",
                "Viễn thông", "B2B",
                List.of("Dịch vụ viễn thông", "Internet", "Cloud Computing"),
                List.of("Việt Nam"),
                "LARGE", 30000, "OVER_1T", "https://vnpt.com.vn",
                "Hà Nội, Việt Nam", "HN", "VERIFIED",
                List.of("partner", "strategic")),

            profile("Viettel Digital", "Viettel Digital", "0106873858",
                "Công nghệ số", "B2B",
                List.of("Chuyển đổi số", "Giải pháp doanh nghiệp", "FinTech"),
                List.of("Việt Nam", "Đông Nam Á"),
                "LARGE", 8000, "100B_TO_1T", "https://vietteldigital.vn",
                "Hà Nội, Việt Nam", "HN", "VERIFIED",
                List.of("partner")),

            profile("CMC Technology", "CMC Tech", "0101689028",
                "Công nghệ thông tin", "B2B",
                List.of("Tích hợp hệ thống", "Outsourcing", "An toàn thông tin"),
                List.of("Việt Nam", "Nhật Bản"),
                "MEDIUM", 5000, "10B_TO_100B", "https://cmctechnology.vn",
                "TP.HCM, Việt Nam", "HCM", "PENDING_REVIEW",
                List.of("partner")),

            profile("MoMo E-Wallet", "MoMo", "0313055803",
                "FinTech", "B2C",
                List.of("Ví điện tử", "Thanh toán di động", "Cho vay tiêu dùng"),
                List.of("Việt Nam"),
                "MEDIUM", 2000, "10B_TO_100B", "https://momo.vn",
                "TP.HCM, Việt Nam", "HCM", "VERIFIED",
                List.of("partner", "fintech")),

            profile("VinGroup", "Vingroup", "0103018409",
                "Tập đoàn đa ngành", "B2B",
                List.of("Bất động sản", "Ô tô điện", "Bán lẻ", "Công nghệ"),
                List.of("Việt Nam", "Toàn cầu"),
                "LARGE", 100000, "OVER_1T", "https://vingroup.net",
                "Hà Nội, Việt Nam", "HN", "VERIFIED",
                List.of("partner", "strategic")),

            profile("Sacombank", "Sacombank", "0301103908",
                "Ngân hàng", "B2B",
                List.of("Ngân hàng bán lẻ", "Ngân hàng doanh nghiệp", "Dịch vụ tài chính"),
                List.of("Việt Nam", "Lào", "Campuchia"),
                "LARGE", 18000, "100B_TO_1T", "https://sacombank.com.vn",
                "TP.HCM, Việt Nam", "HCM", "PENDING_REVIEW",
                List.of("partner", "banking")),

            profile("TH True Milk", "TH Group", "2600286698",
                "Thực phẩm & Đồ uống", "B2C",
                List.of("Sản xuất sữa", "Thực phẩm sạch", "Nông nghiệp công nghệ cao"),
                List.of("Việt Nam", "Nga"),
                "LARGE", 7000, "10B_TO_100B", "https://thmilk.vn",
                "Nghệ An, Việt Nam", "HN", "VERIFIED",
                List.of("partner")),

            profile("Deloitte Vietnam", "Deloitte VN", "0100109106",
                "Tư vấn & Kiểm toán", "B2B",
                List.of("Kiểm toán", "Tư vấn thuế", "Tư vấn chiến lược"),
                List.of("Việt Nam", "Toàn cầu"),
                "MEDIUM", 800, "1B_TO_10B", "https://deloitte.com/vn",
                "TP.HCM, Việt Nam", "HCM", "VERIFIED",
                List.of("competitor")),

            profile("PwC Vietnam", "PwC VN", "0300782250",
                "Tư vấn & Kiểm toán", "B2B",
                List.of("Kiểm toán", "Tư vấn thuế", "Deal advisory"),
                List.of("Việt Nam", "Toàn cầu"),
                "MEDIUM", 700, "1B_TO_10B", "https://pwc.com/vn",
                "Hà Nội, Việt Nam", "HN", "VERIFIED",
                List.of("competitor")),

            profile("KPMG Vietnam", "KPMG VN", "0101368965",
                "Tư vấn", "B2B",
                List.of("Kiểm toán", "Tư vấn rủi ro", "Phân tích thị trường"),
                List.of("Việt Nam", "Toàn cầu"),
                "MEDIUM", 500, "100M_TO_1B", "https://kpmg.com/vn",
                "Hà Nội, Việt Nam", "HN", "VERIFIED",
                List.of("competitor")),

            profile("VCCorp", "VCCorp", "0101683413",
                "Truyền thông số", "B2B",
                List.of("Quảng cáo kỹ thuật số", "Thương mại điện tử", "Nội dung số"),
                List.of("Việt Nam"),
                "MEDIUM", 3000, "1B_TO_10B", "https://vccorp.vn",
                "Hà Nội, Việt Nam", "HN", "IN_PROGRESS",
                List.of("partner")),

            profile("Tima JSC", "Tima", "0105776429",
                "FinTech", "B2C",
                List.of("Cho vay ngang hàng", "Tài chính tiêu dùng"),
                List.of("Việt Nam"),
                "SMALL", 500, "100M_TO_1B", "https://tima.vn",
                "Hà Nội, Việt Nam", "HN", "PENDING_REVIEW",
                List.of("partner", "fintech")),

            profile("TechVision Ltd", "TechVision", "0316812500",
                "Phần mềm", "B2B",
                List.of("Phần mềm doanh nghiệp", "Tự động hóa quy trình"),
                List.of("Việt Nam", "Singapore"),
                "SMALL", 150, "UNDER_100M", "https://techvision.vn",
                "TP.HCM, Việt Nam", "HCM", "IN_PROGRESS",
                List.of("partner"))
        );
    }

    private CompanyProfile profile(
        String legalName, String tradeName, String taxCode,
        String industry, String bizModel,
        List<String> products, List<String> markets,
        String employeeTier, int employeeCount, String revenueTier,
        String website, String address, String city, String reviewStatus,
        List<String> tags
    ) {
        String companyId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        List<CompanyProfile.Product> productList = new ArrayList<>();
        for (String p : products) {
            productList.add(CompanyProfile.Product.builder().name(p).category(industry).build());
        }

        return CompanyProfile.builder()
            .companyId(companyId)
            .identity(CompanyProfile.Identity.builder()
                .legalName(legalName)
                .tradeName(tradeName)
                .taxCode(taxCode)
                .registrationNumber("VN-" + taxCode)
                .build())
            .business(CompanyProfile.Business.builder()
                .industries(List.of(industry))
                .businessModel(bizModel)
                .products(productList)
                .markets(markets)
                .targetCustomers(List.of("Doanh nghiệp", "Chính phủ"))
                .build())
            .companySize(CompanyProfile.CompanySize.builder()
                .employeeTier(employeeTier)
                .employeeCount(employeeCount)
                .revenueTier(revenueTier)
                .build())
            .contact(CompanyProfile.Contact.builder()
                .website(website)
                .emails(List.of("contact@" + tradeName.toLowerCase().replace(" ", "") + ".vn"))
                .phones(List.of("+84-24-" + (int)(Math.random() * 90000000 + 10000000)))
                .addresses(List.of(CompanyProfile.Address.builder()
                    .type("HQ")
                    .fullAddress(address)
                    .city(city)
                    .country("Việt Nam")
                    .build()))
                .build())
            .insights(CompanyProfile.Insights.builder()
                .strengths(List.of("Thương hiệu mạnh", "Hệ sinh thái đa dạng"))
                .weaknesses(List.of("Chi phí vận hành cao"))
                .opportunities(List.of("Chuyển đổi số đang tăng tốc"))
                .threats(List.of("Cạnh tranh từ doanh nghiệp nước ngoài"))
                .build())
            .reviewStatus(reviewStatus)
            .tags(new ArrayList<>(tags))
            .metadata(CompanyProfile.Metadata.builder()
                .createdBy("system-seeder")
                .createdAt(now.minusDays((long)(Math.random() * 90 + 10)))
                .lastModifiedBy("system-seeder")
                .updatedAt(now)
                .build())
            .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Company Nodes (Neo4j)
    // ─────────────────────────────────────────────────────────────

    private List<CompanyNode> buildNodes(List<CompanyProfile> profiles) {
        List<CompanyNode> nodes = new ArrayList<>();
        for (CompanyProfile p : profiles) {
            CompanyNode node = new CompanyNode();
            node.setCompanyId(p.getCompanyId());
            node.setName(p.getIdentity().getTradeName() != null
                ? p.getIdentity().getTradeName()
                : p.getIdentity().getLegalName());
            node.setIndustry(p.getBusiness().getIndustries() != null && !p.getBusiness().getIndustries().isEmpty()
                ? p.getBusiness().getIndustries().get(0)
                : "Unknown");
            node.setCreatedAt(LocalDateTime.now());
            node.setUpdatedAt(LocalDateTime.now());
            nodes.add(node);
        }
        return nodes;
    }

    // ─────────────────────────────────────────────────────────────
    // Projects (SQL Server)
    // ─────────────────────────────────────────────────────────────

    private List<Project> buildProjects(Account manager, Account staff, List<CompanyProfile> profiles) {
        List<Project> projects = new ArrayList<>();

        String[][] projectDefs = {
            {"Nghiên cứu hệ sinh thái Fintech Q3/2026", "RESEARCH_MULTIPLE_COMPANIES", "ACTIVE", "Khảo sát toàn bộ công ty FinTech tiềm năng trong khu vực TP.HCM"},
            {"Đánh giá FPT Corporation 2026", "UPDATE_EXISTING_COMPANY", "ACTIVE", "Cập nhật và xác minh lại hồ sơ chiến lược của FPT"},
            {"Phân tích đối thủ ngành Tư vấn", "RESEARCH_MULTIPLE_COMPANIES", "COMPLETED", "Bản đồ hóa các công ty tư vấn Big4 tại Việt Nam"},
            {"Nghiên cứu VinGroup mở rộng VinAI", "RESEARCH_NEW_COMPANY", "ACTIVE", "Thu thập dữ liệu chuyên sâu về mảng AI và công nghệ của Vingroup"},
            {"Cập nhật hồ sơ VNPT Group", "UPDATE_EXISTING_COMPANY", "DRAFT", "Làm mới thông tin dịch vụ Cloud và viễn thông của VNPT"},
            {"Khảo sát thị trường Banking Tech", "RESEARCH_MULTIPLE_COMPANIES", "COMPLETED", "Nghiên cứu xu hướng ngân hàng số và đối tác tiềm năng"},
        };

        for (String[] def : projectDefs) {
            String name = def[0];
            ProjectType type = ProjectType.valueOf(def[1]);
            ProjectStatus status = ProjectStatus.valueOf(def[2]);
            String description = def[3];

            // Find matching profile if UPDATE_EXISTING
            String targetProfileId = null;
            String targetName = name;
            if (type == ProjectType.UPDATE_EXISTING_COMPANY && profiles.size() > 0) {
                for (CompanyProfile p : profiles) {
                    String pName = p.getIdentity().getLegalName();
                    if (name.toLowerCase().contains(pName.split(" ")[0].toLowerCase())) {
                        targetProfileId = p.getCompanyId();
                        targetName = pName;
                        break;
                    }
                }
            }

            ProjectMember managerMember = ProjectMember.builder()
                .account(manager)
                .memberRole(MemberRole.MANAGER)
                .build();

            ProjectMember staffMember = ProjectMember.builder()
                .account(staff)
                .memberRole(MemberRole.STAFF)
                .build();

            Project project = Project.builder()
                .projectName(name)
                .projectType(type)
                .targetCompanyProfileId(targetProfileId)
                .targetCompanyName(targetName)
                .description(description)
                .status(status)
                .createdByAccount(manager)
                .members(new ArrayList<>())
                .build();

            managerMember.setProject(project);
            staffMember.setProject(project);
            project.getMembers().add(managerMember);
            project.getMembers().add(staffMember);

            projects.add(project);
        }

        return projects;
    }
}
