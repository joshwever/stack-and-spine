package com.stackandspine.model;

public enum MediaType {
    BOOK("Book", "indigo"),
    EBOOK("E-Book", "violet"),
    BLU_RAY("Blu-Ray", "sky"),
    DVD("DVD", "amber");

    private final String displayName;
    private final String badgeColor;

    MediaType(String displayName, String badgeColor) {
        this.displayName = displayName;
        this.badgeColor = badgeColor;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBadgeColor() {
        return badgeColor;
    }
}
