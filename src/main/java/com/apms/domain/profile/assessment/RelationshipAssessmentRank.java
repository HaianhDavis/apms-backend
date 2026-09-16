package com.apms.domain.profile.assessment;

import lombok.Getter;

@Getter
public enum RelationshipAssessmentRank {
    A("Strategic / Very Close Relationship"),
    B("Strong Relationship"),
    C("Developing Relationship"),
    D("Limited Relationship");

    private final String description;

    RelationshipAssessmentRank(String description) {
        this.description = description;
    }

    public static RelationshipAssessmentRank fromScore(int score) {
        if (score >= 90) {
            return A;
        } else if (score >= 60) {
            return B;
        } else if (score >= 30) {
            return C;
        } else {
            return D;
        }
    }

    public static RelationshipAssessmentRank fromExactScore(double score) {
        if (score >= 90.0) {
            return A;
        } else if (score >= 60.0) {
            return B;
        } else if (score >= 30.0) {
            return C;
        } else {
            return D;
        }
    }
}
