package com.apms.domain.crawler.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class TextSimilarityUtils {

    public static double calculateJaccardSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }

        String clean1 = s1.toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        String clean2 = s2.toLowerCase().replaceAll("[^a-z0-9\\s]", "");

        Set<String> set1 = new HashSet<>(Arrays.asList(clean1.split("\\s+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(clean2.split("\\s+")));
        
        set1.remove("");
        set2.remove("");

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }
}
