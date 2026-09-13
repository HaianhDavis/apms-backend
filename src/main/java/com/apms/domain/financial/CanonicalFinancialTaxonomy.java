package com.apms.domain.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class CanonicalFinancialTaxonomy {

    private CanonicalFinancialTaxonomy() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricDefinition {
        private String code;
        private String label;
        private String statementType;
        private String subCategory;
        private String defaultUnit;
        private int displayOrder;
        private boolean common;
        @Builder.Default
        private List<String> aliases = List.of();
    }

    public static final List<MetricDefinition> DEFINITIONS = List.of(
            // ==========================================
            // 1. BALANCE SHEET (BẢNG CÂN ĐỐI KẾ TOÁN) - 29 metrics
            // ==========================================

            // 1.1 Current Assets (Tài sản ngắn hạn) - 7 metrics
            MetricDefinition.builder()
                    .code("CURRENT_ASSETS")
                    .label("Tài sản ngắn hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(1)
                    .common(true)
                    .aliases(List.of("Tài sản ngắn hạn", "Tổng tài sản ngắn hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("CASH_AND_CASH_EQUIVALENTS")
                    .label("Tiền và tương đương tiền")
                    .statementType("BALANCE_SHEET")
                    .subCategory("CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(2)
                    .common(true)
                    .aliases(List.of("Tiền và tương đương tiền", "Tiền và các khoản tương đương tiền", "Tiền mặt và tiền gửi"))
                    .build(),
            MetricDefinition.builder()
                    .code("SHORT_TERM_FINANCIAL_INVESTMENTS")
                    .label("Đầu tư tài chính ngắn hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(3)
                    .common(false)
                    .aliases(List.of("Đầu tư tài chính ngắn hạn", "Các khoản đầu tư tài chính ngắn hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("SHORT_TERM_RECEIVABLES")
                    .label("Các khoản phải thu ngắn hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(4)
                    .common(true)
                    .aliases(List.of("Các khoản phải thu ngắn hạn", "Phải thu ngắn hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("TRADE_RECEIVABLES")
                    .label("Phải thu ngắn hạn của khách hàng")
                    .statementType("BALANCE_SHEET")
                    .subCategory("CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(5)
                    .common(false)
                    .aliases(List.of("Phải thu ngắn hạn của khách hàng", "Phải thu của khách hàng", "Phải thu khách hàng ngắn hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("INVENTORIES")
                    .label("Hàng tồn kho")
                    .statementType("BALANCE_SHEET")
                    .subCategory("CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(6)
                    .common(true)
                    .aliases(List.of("Hàng tồn kho", "Hàng tồn kho ròng"))
                    .build(),
            MetricDefinition.builder()
                    .code("OTHER_CURRENT_ASSETS")
                    .label("Tài sản ngắn hạn khác")
                    .statementType("BALANCE_SHEET")
                    .subCategory("CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(7)
                    .common(false)
                    .aliases(List.of("Tài sản ngắn hạn khác", "Các tài sản ngắn hạn khác"))
                    .build(),

            // 1.2 Non-Current Assets (Tài sản dài hạn) - 8 metrics
            MetricDefinition.builder()
                    .code("NON_CURRENT_ASSETS")
                    .label("Tài sản dài hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(8)
                    .common(true)
                    .aliases(List.of("Tài sản dài hạn", "Tổng tài sản dài hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("LONG_TERM_RECEIVABLES")
                    .label("Các khoản phải thu dài hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(9)
                    .common(false)
                    .aliases(List.of("Các khoản phải thu dài hạn", "Phải thu dài hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("FIXED_ASSETS")
                    .label("Tài sản cố định")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(10)
                    .common(false)
                    .aliases(List.of("Tài sản cố định", "Tổng tài sản cố định"))
                    .build(),
            MetricDefinition.builder()
                    .code("TANGIBLE_FIXED_ASSETS")
                    .label("Tài sản cố định hữu hình")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(11)
                    .common(false)
                    .aliases(List.of("Tài sản cố định hữu hình", "TSCĐ hữu hình"))
                    .build(),
            MetricDefinition.builder()
                    .code("INTANGIBLE_FIXED_ASSETS")
                    .label("Tài sản cố định vô hình")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(12)
                    .common(false)
                    .aliases(List.of("Tài sản cố định vô hình", "TSCĐ vô hình"))
                    .build(),
            MetricDefinition.builder()
                    .code("INVESTMENT_PROPERTIES")
                    .label("Bất động sản đầu tư")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(13)
                    .common(false)
                    .aliases(List.of("Bất động sản đầu tư", "BĐS đầu tư"))
                    .build(),
            MetricDefinition.builder()
                    .code("LONG_TERM_FINANCIAL_INVESTMENTS")
                    .label("Đầu tư tài chính dài hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(14)
                    .common(false)
                    .aliases(List.of("Đầu tư tài chính dài hạn", "Các khoản đầu tư tài chính dài hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("OTHER_NON_CURRENT_ASSETS")
                    .label("Tài sản dài hạn khác")
                    .statementType("BALANCE_SHEET")
                    .subCategory("NON_CURRENT_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(15)
                    .common(false)
                    .aliases(List.of("Tài sản dài hạn khác", "Các tài sản dài hạn khác"))
                    .build(),

            // 1.3 Total Assets (Tổng tài sản) - 1 metric
            MetricDefinition.builder()
                    .code("TOTAL_ASSETS")
                    .label("Tổng tài sản")
                    .statementType("BALANCE_SHEET")
                    .subCategory("TOTAL_ASSETS")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(16)
                    .common(true)
                    .aliases(List.of("Tổng tài sản", "Tổng cộng tài sản"))
                    .build(),

            // 1.4 Liabilities (Nợ phải trả) - 8 metrics
            MetricDefinition.builder()
                    .code("CURRENT_LIABILITIES")
                    .label("Nợ ngắn hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(17)
                    .common(true)
                    .aliases(List.of("Nợ ngắn hạn", "Tổng nợ ngắn hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("TRADE_PAYABLES")
                    .label("Phải trả người bán ngắn hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(18)
                    .common(false)
                    .aliases(List.of("Phải trả người bán ngắn hạn", "Phải trả người bán", "Phải trả cho người bán"))
                    .build(),
            MetricDefinition.builder()
                    .code("SHORT_TERM_BORROWINGS")
                    .label("Vay và nợ thuê tài chính ngắn hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(19)
                    .common(false)
                    .aliases(List.of("Vay và nợ thuê tài chính ngắn hạn", "Vay ngắn hạn", "Vay và nợ ngắn hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("OTHER_CURRENT_LIABILITIES")
                    .label("Nợ ngắn hạn khác")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(20)
                    .common(false)
                    .aliases(List.of("Nợ ngắn hạn khác", "Phải trả ngắn hạn khác"))
                    .build(),
            MetricDefinition.builder()
                    .code("NON_CURRENT_LIABILITIES")
                    .label("Nợ dài hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(21)
                    .common(true)
                    .aliases(List.of("Nợ dài hạn", "Tổng nợ dài hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("LONG_TERM_BORROWINGS")
                    .label("Vay và nợ thuê tài chính dài hạn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(22)
                    .common(false)
                    .aliases(List.of("Vay và nợ thuê tài chính dài hạn", "Vay dài hạn", "Vay và nợ dài hạn"))
                    .build(),
            MetricDefinition.builder()
                    .code("OTHER_NON_CURRENT_LIABILITIES")
                    .label("Nợ dài hạn khác")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(23)
                    .common(false)
                    .aliases(List.of("Nợ dài hạn khác", "Phải trả dài hạn khác"))
                    .build(),
            MetricDefinition.builder()
                    .code("TOTAL_LIABILITIES")
                    .label("Tổng nợ phải trả")
                    .statementType("BALANCE_SHEET")
                    .subCategory("LIABILITIES")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(24)
                    .common(true)
                    .aliases(List.of("Tổng nợ phải trả", "Nợ phải trả"))
                    .build(),

            // 1.5 Equity (Vốn chủ sở hữu) - 5 metrics
            MetricDefinition.builder()
                    .code("OWNER_EQUITY")
                    .label("Vốn chủ sở hữu")
                    .statementType("BALANCE_SHEET")
                    .subCategory("EQUITY")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(25)
                    .common(true)
                    .aliases(List.of("Vốn chủ sở hữu", "Nguồn vốn chủ sở hữu"))
                    .build(),
            MetricDefinition.builder()
                    .code("CHARTER_CAPITAL")
                    .label("Vốn điều lệ")
                    .statementType("BALANCE_SHEET")
                    .subCategory("EQUITY")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(26)
                    .common(false)
                    .aliases(List.of("Vốn điều lệ", "Vốn góp của chủ sở hữu", "Vốn đầu tư của chủ sở hữu"))
                    .build(),
            MetricDefinition.builder()
                    .code("RETAINED_EARNINGS")
                    .label("Lợi nhuận sau thuế chưa phân phối")
                    .statementType("BALANCE_SHEET")
                    .subCategory("EQUITY")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(27)
                    .common(false)
                    .aliases(List.of("Lợi nhuận sau thuế chưa phân phối", "Lợi nhuận chưa phân phối"))
                    .build(),
            MetricDefinition.builder()
                    .code("TOTAL_EQUITY")
                    .label("Tổng vốn chủ sở hữu")
                    .statementType("BALANCE_SHEET")
                    .subCategory("EQUITY")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(28)
                    .common(false)
                    .aliases(List.of("Tổng vốn chủ sở hữu", "Vốn và các quỹ"))
                    .build(),
            MetricDefinition.builder()
                    .code("TOTAL_LIABILITIES_AND_EQUITY")
                    .label("Tổng cộng nguồn vốn")
                    .statementType("BALANCE_SHEET")
                    .subCategory("EQUITY")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(29)
                    .common(false)
                    .aliases(List.of("Tổng cộng nguồn vốn", "Tổng nguồn vốn"))
                    .build(),

            // ==========================================
            // 2. INCOME STATEMENT (BÁO CÁO KẾT QUẢ KINH DOANH) - 18 metrics
            // ==========================================
            MetricDefinition.builder()
                    .code("GROSS_REVENUE")
                    .label("Doanh thu bán hàng và cung cấp dịch vụ")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(30)
                    .common(false)
                    .aliases(List.of("Doanh thu bán hàng và cung cấp dịch vụ", "Doanh thu gộp", "Tổng doanh thu"))
                    .build(),
            MetricDefinition.builder()
                    .code("REVENUE_DEDUCTIONS")
                    .label("Các khoản giảm trừ doanh thu")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(31)
                    .common(false)
                    .aliases(List.of("Các khoản giảm trừ doanh thu", "Giảm trừ doanh thu"))
                    .build(),
            MetricDefinition.builder()
                    .code("NET_REVENUE")
                    .label("Doanh thu thuần")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(32)
                    .common(true)
                    .aliases(List.of("Doanh thu thuần", "Doanh thu thuần về bán hàng và cung cấp dịch vụ"))
                    .build(),
            MetricDefinition.builder()
                    .code("COST_OF_GOODS_SOLD")
                    .label("Giá vốn hàng bán")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(33)
                    .common(true)
                    .aliases(List.of("Giá vốn hàng bán", "Giá vốn"))
                    .build(),
            MetricDefinition.builder()
                    .code("GROSS_PROFIT")
                    .label("Lợi nhuận gộp")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(34)
                    .common(true)
                    .aliases(List.of("Lợi nhuận gộp", "Lợi nhuận gộp về bán hàng và cung cấp dịch vụ"))
                    .build(),
            MetricDefinition.builder()
                    .code("FINANCIAL_INCOME")
                    .label("Doanh thu hoạt động tài chính")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(35)
                    .common(false)
                    .aliases(List.of("Doanh thu hoạt động tài chính", "Doanh thu tài chính"))
                    .build(),
            MetricDefinition.builder()
                    .code("FINANCIAL_EXPENSES")
                    .label("Chi phí tài chính")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(36)
                    .common(true)
                    .aliases(List.of("Chi phí tài chính", "Tổng chi phí tài chính"))
                    .build(),
            MetricDefinition.builder()
                    .code("INTEREST_EXPENSE")
                    .label("Chi phí lãi vay")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(37)
                    .common(false)
                    .aliases(List.of("Chi phí lãi vay", "Chi phí lãi"))
                    .build(),
            MetricDefinition.builder()
                    .code("SELLING_EXPENSE")
                    .label("Chi phí bán hàng")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(38)
                    .common(true)
                    .aliases(List.of("Chi phí bán hàng"))
                    .build(),
            MetricDefinition.builder()
                    .code("GENERAL_AND_ADMINISTRATIVE_EXPENSE")
                    .label("Chi phí quản lý doanh nghiệp")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(39)
                    .common(true)
                    .aliases(List.of("Chi phí quản lý doanh nghiệp", "Chi phí quản lý"))
                    .build(),
            MetricDefinition.builder()
                    .code("OPERATING_PROFIT")
                    .label("Lợi nhuận thuần từ hoạt động kinh doanh")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(40)
                    .common(false)
                    .aliases(List.of("Lợi nhuận thuần từ hoạt động kinh doanh", "Lợi nhuận từ HĐKD", "Lợi nhuận thuần"))
                    .build(),
            MetricDefinition.builder()
                    .code("OTHER_INCOME")
                    .label("Thu nhập khác")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(41)
                    .common(false)
                    .aliases(List.of("Thu nhập khác"))
                    .build(),
            MetricDefinition.builder()
                    .code("OTHER_EXPENSE")
                    .label("Chi phí khác")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(42)
                    .common(false)
                    .aliases(List.of("Chi phí khác"))
                    .build(),
            MetricDefinition.builder()
                    .code("OTHER_PROFIT")
                    .label("Lợi nhuận khác")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(43)
                    .common(false)
                    .aliases(List.of("Lợi nhuận khác"))
                    .build(),
            MetricDefinition.builder()
                    .code("PROFIT_BEFORE_TAX")
                    .label("Lợi nhuận trước thuế")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(44)
                    .common(true)
                    .aliases(List.of("Lợi nhuận trước thuế", "Tổng lợi nhuận kế toán trước thuế"))
                    .build(),
            MetricDefinition.builder()
                    .code("CURRENT_INCOME_TAX_EXPENSE")
                    .label("Chi phí thuế TNDN hiện hành")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(45)
                    .common(false)
                    .aliases(List.of("Chi phí thuế TNDN hiện hành", "Thuế TNDN hiện hành"))
                    .build(),
            MetricDefinition.builder()
                    .code("DEFERRED_INCOME_TAX_EXPENSE")
                    .label("Chi phí thuế TNDN hoãn lại")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(46)
                    .common(false)
                    .aliases(List.of("Chi phí thuế TNDN hoãn lại", "Thuế TNDN hoãn lại"))
                    .build(),
            MetricDefinition.builder()
                    .code("PROFIT_AFTER_TAX")
                    .label("Lợi nhuận sau thuế")
                    .statementType("INCOME_STATEMENT")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(47)
                    .common(true)
                    .aliases(List.of("Lợi nhuận sau thuế", "Lợi nhuận sau thuế TNDN"))
                    .build(),

            // ==========================================
            // 3. BANKING & CREDIT INSTITUTIONS (NGÂN HÀNG & TỔ CHỨC TÍN DỤNG) - 8 metrics
            // ==========================================
            MetricDefinition.builder()
                    .code("CUSTOMER_LOANS")
                    .label("Cho vay khách hàng")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(48)
                    .common(false)
                    .aliases(List.of("Cho vay khách hàng", "Dư nợ cho vay khách hàng"))
                    .build(),
            MetricDefinition.builder()
                    .code("CUSTOMER_DEPOSITS")
                    .label("Tiền gửi của khách hàng")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(49)
                    .common(false)
                    .aliases(List.of("Tiền gửi của khách hàng", "Tiền gửi khách hàng"))
                    .build(),
            MetricDefinition.builder()
                    .code("NET_INTEREST_INCOME")
                    .label("Thu nhập lãi thuần")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(50)
                    .common(false)
                    .aliases(List.of("Thu nhập lãi thuần", "Lãi thuần"))
                    .build(),
            MetricDefinition.builder()
                    .code("NON_INTEREST_INCOME")
                    .label("Thu nhập ngoài lãi")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(51)
                    .common(false)
                    .aliases(List.of("Thu nhập ngoài lãi", "Tổng thu nhập ngoài lãi"))
                    .build(),
            MetricDefinition.builder()
                    .code("NET_FEE_COMMISSION_INCOME")
                    .label("Lãi thuần từ hoạt động dịch vụ")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(52)
                    .common(false)
                    .aliases(List.of("Lãi thuần từ hoạt động dịch vụ", "Thu nhập thuần từ hoạt động dịch vụ"))
                    .build(),
            MetricDefinition.builder()
                    .code("TOTAL_OPERATING_INCOME")
                    .label("Tổng thu nhập hoạt động")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(53)
                    .common(false)
                    .aliases(List.of("Tổng thu nhập hoạt động", "TOI"))
                    .build(),
            MetricDefinition.builder()
                    .code("OPERATING_EXPENSES")
                    .label("Chi phí hoạt động")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(54)
                    .common(false)
                    .aliases(List.of("Chi phí hoạt động", "Tổng chi phí hoạt động", "OPEX"))
                    .build(),
            MetricDefinition.builder()
                    .code("CREDIT_RISK_PROVISION")
                    .label("Chi phí dự phòng rủi ro tín dụng")
                    .statementType("BANKING")
                    .defaultUnit("MILLION_VND")
                    .displayOrder(55)
                    .common(false)
                    .aliases(List.of("Chi phí dự phòng rủi ro tín dụng", "Chi phí dự phòng rủi ro", "Dự phòng rủi ro tín dụng"))
                    .build(),

            // ==========================================
            // 4. FINANCIAL & SAFETY RATIOS (CÁC CHỈ SỐ TÀI CHÍNH & AN TOÀN) - 6 metrics
            // ==========================================
            MetricDefinition.builder()
                    .code("NIM")
                    .label("Biên lãi thuần (NIM)")
                    .statementType("RATIOS")
                    .defaultUnit("PERCENT")
                    .displayOrder(56)
                    .common(false)
                    .aliases(List.of("Biên lãi thuần (NIM)", "Tỷ lệ thu nhập lãi thuần (NIM)", "NIM"))
                    .build(),
            MetricDefinition.builder()
                    .code("CIR")
                    .label("Tỷ lệ chi phí / thu nhập (CIR)")
                    .statementType("RATIOS")
                    .defaultUnit("PERCENT")
                    .displayOrder(57)
                    .common(false)
                    .aliases(List.of("Tỷ lệ chi phí / thu nhập (CIR)", "Tỷ lệ chi phí trên thu nhập (CIR)", "CIR"))
                    .build(),
            MetricDefinition.builder()
                    .code("NPL")
                    .label("Tỷ lệ nợ xấu (NPL)")
                    .statementType("RATIOS")
                    .defaultUnit("PERCENT")
                    .displayOrder(58)
                    .common(false)
                    .aliases(List.of("Tỷ lệ nợ xấu (NPL)", "Tỷ lệ nợ xấu", "NPL"))
                    .build(),
            MetricDefinition.builder()
                    .code("CAR")
                    .label("Tỷ lệ an toàn vốn (CAR)")
                    .statementType("RATIOS")
                    .defaultUnit("PERCENT")
                    .displayOrder(59)
                    .common(false)
                    .aliases(List.of("Tỷ lệ an toàn vốn (CAR)", "Hệ số an toàn vốn", "CAR"))
                    .build(),
            MetricDefinition.builder()
                    .code("ROAA")
                    .label("ROAA")
                    .statementType("RATIOS")
                    .defaultUnit("PERCENT")
                    .displayOrder(60)
                    .common(false)
                    .aliases(List.of("ROAA", "Tỷ suất sinh lời trên tổng tài sản bình quân (ROAA)"))
                    .build(),
            MetricDefinition.builder()
                    .code("ROAE")
                    .label("ROAE")
                    .statementType("RATIOS")
                    .defaultUnit("PERCENT")
                    .displayOrder(61)
                    .common(false)
                    .aliases(List.of("ROAE", "Tỷ suất sinh lời trên vốn chủ sở hữu bình quân (ROAE)"))
                    .build()
    );

    public static List<MetricDefinition> getCommonDefinitions() {
        return DEFINITIONS.stream().filter(MetricDefinition::isCommon).toList();
    }

    public static Optional<MetricDefinition> findByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return DEFINITIONS.stream()
                .filter(d -> d.getCode().equalsIgnoreCase(code.trim()))
                .findFirst();
    }

    public static Optional<MetricDefinition> findByCodeOrAlias(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String norm = normalize(text);
        return DEFINITIONS.stream()
                .filter(d -> {
                    if (d.getCode().equalsIgnoreCase(text.trim())) return true;
                    if (normalize(d.getLabel()).equals(norm)) return true;
                    if (d.getAliases() != null) {
                        for (String a : d.getAliases()) {
                            if (normalize(a).equals(norm)) return true;
                        }
                    }
                    return false;
                })
                .findFirst();
    }

    public static String normalize(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String noAccents = pattern.matcher(normalized).replaceAll("");
        return noAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
