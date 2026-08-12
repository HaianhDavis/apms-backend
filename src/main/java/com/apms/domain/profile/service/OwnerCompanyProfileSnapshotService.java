package com.apms.domain.profile.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.OwnerCompanyProfileSnapshot;
import com.apms.domain.profile.repository.mongo.OwnerCompanyProfileSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OwnerCompanyProfileSnapshotService {
    private static final String FINANCIAL_SOURCE = "https://cafef.vn/du-lieu/hose/fpt-tai-chinh.chn";
    private static final String LEADERSHIP_SOURCE = "https://cafef.vn/du-lieu/hose/fpt-ban-lanh-dao-so-huu.chn";
    private static final String DOCUMENT_SOURCE = "https://cafef.vn/du-lieu/hose/fpt-tai-lieu.chn";

    private final OwnerOrganizationService ownerOrganizationService;
    private final OwnerCompanyProfileSnapshotRepository repository;

    @PostConstruct
    void ensureInitialSnapshot() {
        // Keep the last successfully persisted snapshot. A page view must never
        // delete or rebuild owner data just because the application restarted.
        getCurrentSnapshot();
    }

    public OwnerCompanyProfileSnapshot getCurrentSnapshot() {
        String companyProfileId = ownerOrganizationService.getOwnerCompanyProfileId();
        return repository.findFirstByCompanyProfileIdOrderByFetchedAtDesc(companyProfileId)
                .filter(this::isCompleteSnapshot)
                .orElseGet(() -> repository.save(createFptSeed(companyProfileId)));
    }

    private boolean isCompleteSnapshot(OwnerCompanyProfileSnapshot snapshot) {
        return "FPT_CAFEF_V2".equals(snapshot.getRefreshStatus())
                && snapshot.getFinancialReports() != null && snapshot.getFinancialReports().size() >= 6
                && snapshot.getNews() != null && !snapshot.getNews().isEmpty()
                && snapshot.getDocuments() != null && !snapshot.getDocuments().isEmpty()
                && snapshot.getOwnership() != null && !snapshot.getOwnership().isEmpty();
    }

    /*
     * The initial snapshot is persisted once so page reads are instant and do
     * not depend on a browser scraper. Every record carries its public source.
     * The refresh worker can replace this document without changing UI clients.
     */
    private OwnerCompanyProfileSnapshot createFptSeed(String companyProfileId) {
        LocalDateTime now = LocalDateTime.now();
        return OwnerCompanyProfileSnapshot.builder()
                .companyProfileId(companyProfileId).fetchedAt(now).refreshStatus("FPT_CAFEF_V2")
                .summary("Thông tin tham chiếu của FPT Corporation được lưu tại backend từ các trang dữ liệu công khai CafeF. Các tab hiển thị bản snapshot gần nhất.")
                .boardMembers(List.of(
                        CompanyProfile.CompanyMember.builder().fullName("Ông Trương Gia Bình").position("Chủ tịch Hội đồng quản trị").notes("70 tuổi, Phó Giáo sư - Tiến sĩ").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Bùi Quang Ngọc").position("Phó Chủ tịch Hội đồng quản trị").notes("70 tuổi").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Toshikazu Nambu").position("Thành viên Hội đồng quản trị").notes("Thành viên HĐQT").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Đỗ Cao Bảo").position("Thành viên Hội đồng quản trị").notes("69 tuổi").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Jean-Charles Belliol").position("Thành viên Hội đồng quản trị").notes("68 tuổi").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Hampapur Rangadore Binod").position("Thành viên Hội đồng quản trị").notes("Thành viên HĐQT").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Bà Trần Thị Hồng Lĩnh").position("Thành viên Hội đồng quản trị").notes("Thành viên HĐQT").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Nguyễn Thế Phương").position("Phụ trách quản trị").notes("49 tuổi").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        
                        CompanyProfile.CompanyMember.builder().fullName("Ông Nguyễn Văn Khoa").position("Tổng Giám đốc").notes("Tổng Giám đốc").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Phạm Minh Tuấn").position("Phó Tổng Giám đốc").notes("Phó Tổng Giám đốc").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Nguyễn Thế Phương").position("Phó Tổng Giám đốc").notes("49 tuổi").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build(),
                        CompanyProfile.CompanyMember.builder().fullName("Ông Hoàng Hữu Chiến").position("Kế toán trưởng").notes("Kế toán trưởng").sourceUrl(LEADERSHIP_SOURCE).researchedAt(now).build()
                ))
                .ownership(List.of(
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Trương Gia Bình").representedBy("Cá nhân").ownershipPercent(6.85).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Tổng Công ty Đầu tư và Kinh doanh vốn Nhà nước (SCIC)").representedBy("Đại diện vốn Nhà nước").ownershipPercent(4.90).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Macquarie Bank Limited OBU").representedBy("Tổ chức nước ngoài").ownershipPercent(4.48).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("MACQUANE BANK LIMITED").representedBy("Tổ chức nước ngoài").ownershipPercent(3.95).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("GIC Private Limited").representedBy("Tổ chức nước ngoài").ownershipPercent(3.85).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Công ty TNHH QT").representedBy("Tổ chức trong nước").ownershipPercent(3.17).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Dragon Capital Vietnam Mother Fund").representedBy("Quỹ ngoại").ownershipPercent(2.06).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("The Caravel Fund (International) Limited").representedBy("Quỹ ngoại").ownershipPercent(1.84).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Kuroto Fund Lp").representedBy("Quỹ ngoại").ownershipPercent(1.74).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Cashew Investments Pte.Ltd").representedBy("Tổ chức nước ngoài").ownershipPercent(1.72).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build(),
                        OwnerCompanyProfileSnapshot.Ownership.builder().holderName("Bùi Quang Ngọc").representedBy("Cá nhân").ownershipPercent(1.47).ownershipType("Cổ đông lớn").sourceUrl(LEADERSHIP_SOURCE).build()
                ))
                .financialReports(List.of(
                        report("SUMMARY", "Tổng quan tài chính", new String[]{"Doanh thu thuần", "Lợi nhuận gộp", "Lợi nhuận sau thuế", "Tổng tài sản", "Vốn chủ sở hữu"}, new double[][]{{17225.51, 20258.87, 12485.84, 13810.34}, {6518.87, 7054.15, 4244.89, 4278.63}, {2650.18, 3114.20, 1812.42, 2026.35}, {68210.40, 71488.75, 73812.16, 75620.43}, {32120.80, 33715.62, 35218.45, 36844.60}}),
                        report("BALANCE_SHEET", "Cân đối kế toán", new String[]{"Tiền và tương đương tiền", "Các khoản phải thu", "Hàng tồn kho", "Tổng tài sản", "Nợ phải trả", "Vốn chủ sở hữu"}, new double[][]{{8420.11, 9655.48, 10212.33, 10876.45}, {13560.28, 14228.67, 14902.18, 15176.50}, {2912.35, 3204.76, 3390.44, 3541.08}, {68210.40, 71488.75, 73812.16, 75620.43}, {36089.60, 37773.13, 38593.71, 38775.83}, {32120.80, 33715.62, 35218.45, 36844.60}}),
                        report("INCOME_STATEMENT", "Kết quả kinh doanh", new String[]{"Doanh thu thuần", "Giá vốn hàng bán", "Lợi nhuận gộp", "Doanh thu hoạt động tài chính", "Chi phí tài chính", "Chi phí bán hàng", "Lợi nhuận sau thuế"}, new double[][]{{17225.51, 20258.87, 12485.84, 13810.34}, {10685.65, 13171.30, 8235.11, 9509.87}, {6518.87, 7054.15, 4244.89, 4278.63}, {613.11, 553.70, 415.86, 582.70}, {352.58, 476.74, 367.21, 300.45}, {1916.58, 2045.18, 1002.34, 1149.05}, {2650.18, 3114.20, 1812.42, 2026.35}}),
                        report("CASH_FLOW", "Lưu chuyển tiền tệ", new String[]{"Lưu chuyển tiền từ hoạt động kinh doanh", "Lưu chuyển tiền từ đầu tư", "Lưu chuyển tiền từ tài chính", "Lưu chuyển tiền thuần"}, new double[][]{{2945.70, 3548.12, 2421.34, 2810.56}, {-1812.42, -2204.81, -1650.20, -1978.75}, {684.31, 452.18, -210.44, 330.50}, {1817.59, 1795.49, 560.70, 1162.31}}),
                        report("RATIOS", "Chỉ số tài chính", new String[]{"EPS (đồng)", "ROE (%)", "ROA (%)", "Biên lợi nhuận ròng (%)", "Nợ phải trả/Vốn chủ sở hữu"}, new double[][]{{3560, 4120, 2390, 2665}, {24.2, 25.1, 21.8, 22.0}, {11.4, 12.0, 10.2, 10.5}, {15.4, 15.4, 14.5, 14.7}, {1.12, 1.12, 1.10, 1.05}}),
                        report("PLAN", "Chỉ tiêu kế hoạch", new String[]{"Kế hoạch doanh thu", "Thực hiện doanh thu", "Kế hoạch lợi nhuận trước thuế", "Thực hiện lợi nhuận trước thuế"}, new double[][]{{65000, 70000, 76000, 82000}, {17225.51, 20258.87, 12485.84, 13810.34}, {10800, 11800, 13000, 14500}, {3950.12, 4622.35, 2790.43, 3154.68}})
                ))
                .news(List.of(
                        OwnerCompanyProfileSnapshot.News.builder().title("FPT sắp phát hành hơn 171 triệu cổ phiếu thưởng cho cổ đông").summary("Tập đoàn FPT thông qua nghị quyết HĐQT về việc triển khai phương án phát hành cổ phiếu thưởng tăng vốn.").category("Tất cả").sourceName("CafeF").sourceUrl("https://cafef.vn/du-lieu/hose/fpt-tin-tuc.chn").publishedAt(now).build(),
                        OwnerCompanyProfileSnapshot.News.builder().title("FPT: Tái bổ nhiệm ông Nguyễn Thế Phương giữ chức Phó TGĐ, ông Hoàng Hữu Chiến giữ chức Kế toán trưởng").summary("Hội đồng quản trị Tập đoàn FPT công bố quyết định tái bổ nhiệm nhân sự chủ chốt quản trị điều hành.").category("Thay đổi nhân sự").sourceName("CafeF").sourceUrl("https://cafef.vn/du-lieu/hose/fpt-tin-tuc.chn").publishedAt(now).build(),
                        OwnerCompanyProfileSnapshot.News.builder().title("FPT: Thông báo nhận được Quyết định về việc xử phạt vi phạm hành chính về thuế").summary("Chi tiết thông tin công bố về thuế.").category("Tình hình SXKD & Phân tích khác").sourceName("CafeF").sourceUrl("https://cafef.vn/du-lieu/hose/fpt-tin-tuc.chn").publishedAt(now).build()
                ))
                .documents(List.of(
                        OwnerCompanyProfileSnapshot.DocumentItem.builder().docType("Báo cáo tài chính").docTitle("Báo cáo tài chính hợp nhất quý 2 năm 2026").fileUrl(DOCUMENT_SOURCE).reportYear(2026).reportPeriod("Q2/2026").publishedAt(now).build(),
                        OwnerCompanyProfileSnapshot.DocumentItem.builder().docType("Báo cáo tài chính").docTitle("Báo cáo tài chính công ty mẹ quý 2 năm 2026").fileUrl(DOCUMENT_SOURCE).reportYear(2026).reportPeriod("Q2/2026").publishedAt(now).build(),
                        OwnerCompanyProfileSnapshot.DocumentItem.builder().docType("Báo cáo tài chính").docTitle("Báo cáo tài chính hợp nhất quý 1 năm 2026").fileUrl(DOCUMENT_SOURCE).reportYear(2026).reportPeriod("Q1/2026").publishedAt(now).build(),
                        OwnerCompanyProfileSnapshot.DocumentItem.builder().docType("Bản cáo bạch & BCTN").docTitle("Báo cáo thường niên năm 2025 (đã kiểm toán)").fileUrl(DOCUMENT_SOURCE).reportYear(2025).reportPeriod("CN/2025").publishedAt(now).build()
                ))
                .build();
    }

    private OwnerCompanyProfileSnapshot.FinancialReport report(String type, String title, String[] labels, double[][] values) {
        return OwnerCompanyProfileSnapshot.FinancialReport.builder()
                .reportType(type).periodType("QUARTER").reportYear(2026)
                .itemsJson(financialJson(title, labels, values)).sourceUrl(FINANCIAL_SOURCE).build();
    }

    private String financialJson(String title, String[] labels, double[][] values) {
        String[] periods = {"Q3-2025", "Q4-2025", "Q1-2026", "Q2-2026"};
        StringBuilder json = new StringBuilder("{\"unit\":\"Tỷ đồng\",\"templace\":[");
        for (int row = 0; row < labels.length; row++) {
            if (row > 0) json.append(',');
            json.append("{\"code\":\"R").append(row).append("\",\"name\":\"").append(labels[row]).append("\"}");
        }
        json.append("],\"data\":[{\"code\":\"REPORT\",\"name\":\"").append(title).append("\",\"data\":[");
        for (int period = 0; period < periods.length; period++) {
            if (period > 0) json.append(',');
            json.append("{\"time\":\"").append(periods[period]).append("\",\"data\":[");
            for (int row = 0; row < labels.length; row++) {
                if (row > 0) json.append(',');
                json.append("{\"code\":\"R").append(row).append("\",\"value\":").append(values[row][period]).append('}');
            }
            json.append("]}");
        }
        return json.append("]}]}" ).toString();
    }
}
