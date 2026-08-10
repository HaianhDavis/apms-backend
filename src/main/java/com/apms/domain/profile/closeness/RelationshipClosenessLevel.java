package com.apms.domain.profile.closeness;

import com.apms.common.exception.BusinessValidationException;

public enum RelationshipClosenessLevel {
    CONTACT_ONLY(1),
    WEAK(2),
    ESTABLISHED(3),
    CLOSE(4),
    STRATEGIC(5);

    private final int stars;

    RelationshipClosenessLevel(int stars) {
        this.stars = stars;
    }

    public int getStars() {
        return stars;
    }

    public static RelationshipClosenessLevel fromStars(int stars) {
        for (RelationshipClosenessLevel level : values()) {
            if (level.getStars() == stars) {
                return level;
            }
        }
        throw new BusinessValidationException("Invalid RelationshipClosenessLevel stars: " + stars + ". Must be between 1 and 5.");
    }
}
