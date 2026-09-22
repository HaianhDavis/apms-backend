package com.apms.domain.profile.service;

import com.apms.domain.profile.CompanyProfile;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Helper for semantic normalization and comparison of Company Profile fields and collections
 * to ensure version history diffs only record genuine business changes.
 */
public final class CompanyProfileDiffHelper {

    private CompanyProfileDiffHelper() {}

    /**
     * Normalizes optional scalar string:
     * - null -> null
     * - blank string -> null
     * - otherwise trimmed string
     */
    public static String normalizeOptionalString(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Checks if an address contains meaningful location fields (not just type: "HEADQUARTERS").
     */
    public static boolean isMeaningfulAddress(Object addressObj) {
        if (addressObj == null) return false;
        if (addressObj instanceof CompanyProfile.Address addr) {
            return StringUtils.hasText(addr.getFullAddress())
                    || StringUtils.hasText(addr.getCity())
                    || StringUtils.hasText(addr.getCountry());
        }
        if (addressObj instanceof Map<?, ?> map) {
            Object fullAddress = map.get("fullAddress");
            Object city = map.get("city");
            Object country = map.get("country");
            return (fullAddress != null && StringUtils.hasText(fullAddress.toString()))
                    || (city != null && StringUtils.hasText(city.toString()))
                    || (country != null && StringUtils.hasText(country.toString()));
        }
        return false;
    }

    /**
     * Normalizes address list by removing placeholder objects with no meaningful location fields.
     */
    public static List<Object> normalizeAddressList(Object val) {
        if (val == null) return Collections.emptyList();
        if (val instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (isMeaningfulAddress(item)) {
                    result.add(item);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    /**
     * Checks if a product is meaningful (has a non-blank name).
     */
    public static boolean isMeaningfulProduct(Object productObj) {
        if (productObj == null) return false;
        if (productObj instanceof CompanyProfile.Product p) {
            return StringUtils.hasText(p.getName());
        }
        if (productObj instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name != null && StringUtils.hasText(name.toString());
        }
        return false;
    }

    /**
     * Normalizes product list by removing products with empty/blank names.
     */
    public static List<Object> normalizeProductList(Object val) {
        if (val == null) return Collections.emptyList();
        if (val instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (isMeaningfulProduct(item)) {
                    result.add(item);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    /**
     * Checks if a member is meaningful (has a non-blank fullName).
     */
    public static boolean isMeaningfulMember(Object memberObj) {
        if (memberObj == null) return false;
        if (memberObj instanceof CompanyProfile.CompanyMember m) {
            return StringUtils.hasText(m.getFullName());
        }
        if (memberObj instanceof Map<?, ?> map) {
            Object fullName = map.get("fullName");
            return fullName != null && StringUtils.hasText(fullName.toString());
        }
        return false;
    }

    /**
     * Normalizes member list by removing empty entries.
     */
    public static List<Object> normalizeMemberList(Object val) {
        if (val == null) return Collections.emptyList();
        if (val instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (isMeaningfulMember(item)) {
                    result.add(item);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    /**
     * Normalizes a list of strings by removing null and blank entries.
     */
    public static List<String> normalizeStringList(Object val) {
        if (val == null) return Collections.emptyList();
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    String s = item.toString().trim();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    /**
     * Checks whether before and after values for a given field path are semantically equal.
     */
    public static boolean areValuesSemanticallyEqual(String path, Object beforeVal, Object afterVal) {
        if (path == null) {
            return Objects.equals(beforeVal, afterVal);
        }

        switch (path) {
            case "contact.addresses": {
                List<Object> bList = normalizeAddressList(beforeVal);
                List<Object> aList = normalizeAddressList(afterVal);
                return areAddressListsEqual(bList, aList);
            }
            case "business.products": {
                List<Object> bList = normalizeProductList(beforeVal);
                List<Object> aList = normalizeProductList(afterVal);
                return areProductListsEqual(bList, aList);
            }
            case "companyMembers": {
                List<Object> bList = normalizeMemberList(beforeVal);
                List<Object> aList = normalizeMemberList(afterVal);
                return areMemberListsEqual(bList, aList);
            }
            case "contact.emails":
            case "contact.phones":
            case "business.industries":
            case "business.markets":
            case "business.targetCustomers":
            case "tags": {
                List<String> bList = normalizeStringList(beforeVal);
                List<String> aList = normalizeStringList(afterVal);
                return Objects.equals(bList, aList);
            }
            case "companySize.employeeCount": {
                Integer bNum = parseInteger(beforeVal);
                Integer aNum = parseInteger(afterVal);
                return Objects.equals(bNum, aNum);
            }
            default: {
                // Scalar string comparisons:
                // identity.legalName, identity.tradeName, identity.taxCode, identity.registrationNumber,
                // contact.website, companySize.employeeTier, companySize.revenueTier, business.businessModel
                String bStr = normalizeOptionalString(beforeVal);
                String aStr = normalizeOptionalString(afterVal);
                return Objects.equals(bStr, aStr);
            }
        }
    }

    /**
     * Normalizes a value for recording in beforeValues / afterValues in Version History.
     * When a field is empty (blank string, empty collection, placeholder address), it returns null
     * so that the UI correctly detects "Removed" (for after) or "Not provided" (for before).
     */
    public static Object normalizeForHistory(String path, Object val) {
        if (val == null) return null;
        if (path == null) return val;

        switch (path) {
            case "contact.addresses": {
                List<Object> list = normalizeAddressList(val);
                return list.isEmpty() ? null : list;
            }
            case "business.products": {
                List<Object> list = normalizeProductList(val);
                return list.isEmpty() ? null : list;
            }
            case "companyMembers": {
                List<Object> list = normalizeMemberList(val);
                return list.isEmpty() ? null : list;
            }
            case "contact.emails":
            case "contact.phones":
            case "business.industries":
            case "business.markets":
            case "business.targetCustomers":
            case "tags": {
                List<String> list = normalizeStringList(val);
                return list.isEmpty() ? null : list;
            }
            case "companySize.employeeCount":
                return parseInteger(val);
            default:
                return normalizeOptionalString(val);
        }
    }

    private static Integer parseInteger(Object val) {
        if (val == null) return null;
        if (val instanceof Number num) return num.intValue();
        try {
            String s = val.toString().trim();
            return s.isEmpty() ? null : Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean areAddressListsEqual(List<Object> list1, List<Object> list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!areAddressesEqual(list1.get(i), list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean areAddressesEqual(Object a1, Object a2) {
        if (a1 == a2) return true;
        if (a1 == null || a2 == null) return false;
        String type1 = getField(a1, "type");
        String type2 = getField(a2, "type");
        String fullAddr1 = getField(a1, "fullAddress");
        String fullAddr2 = getField(a2, "fullAddress");
        String city1 = getField(a1, "city");
        String city2 = getField(a2, "city");
        String country1 = getField(a1, "country");
        String country2 = getField(a2, "country");

        return Objects.equals(normalizeOptionalString(type1), normalizeOptionalString(type2))
                && Objects.equals(normalizeOptionalString(fullAddr1), normalizeOptionalString(fullAddr2))
                && Objects.equals(normalizeOptionalString(city1), normalizeOptionalString(city2))
                && Objects.equals(normalizeOptionalString(country1), normalizeOptionalString(country2));
    }

    private static boolean areProductListsEqual(List<Object> list1, List<Object> list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!areProductsEqual(list1.get(i), list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean areProductsEqual(Object p1, Object p2) {
        if (p1 == p2) return true;
        if (p1 == null || p2 == null) return false;
        String name1 = getField(p1, "name");
        String name2 = getField(p2, "name");
        String cat1 = getField(p1, "category");
        String cat2 = getField(p2, "category");
        String desc1 = getField(p1, "description");
        String desc2 = getField(p2, "description");

        return Objects.equals(normalizeOptionalString(name1), normalizeOptionalString(name2))
                && Objects.equals(normalizeOptionalString(cat1), normalizeOptionalString(cat2))
                && Objects.equals(normalizeOptionalString(desc1), normalizeOptionalString(desc2));
    }

    private static boolean areMemberListsEqual(List<Object> list1, List<Object> list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!areMembersEqual(list1.get(i), list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean areMembersEqual(Object m1, Object m2) {
        if (m1 == m2) return true;
        if (m1 == null || m2 == null) return false;
        String name1 = getField(m1, "fullName");
        String name2 = getField(m2, "fullName");
        String pos1 = getField(m1, "position");
        String pos2 = getField(m2, "position");
        String img1 = getField(m1, "imageUrl");
        String img2 = getField(m2, "imageUrl");
        String src1 = getField(m1, "sourceUrl");
        String src2 = getField(m2, "sourceUrl");
        String notes1 = getField(m1, "notes");
        String notes2 = getField(m2, "notes");

        return Objects.equals(normalizeOptionalString(name1), normalizeOptionalString(name2))
                && Objects.equals(normalizeOptionalString(pos1), normalizeOptionalString(pos2))
                && Objects.equals(normalizeOptionalString(img1), normalizeOptionalString(img2))
                && Objects.equals(normalizeOptionalString(src1), normalizeOptionalString(src2))
                && Objects.equals(normalizeOptionalString(notes1), normalizeOptionalString(notes2));
    }

    private static String getField(Object obj, String fieldName) {
        if (obj == null) return null;
        if (obj instanceof Map<?, ?> map) {
            Object val = map.get(fieldName);
            return val != null ? val.toString() : null;
        }
        try {
            String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            java.lang.reflect.Method method = obj.getClass().getMethod(getterName);
            Object val = method.invoke(obj);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
