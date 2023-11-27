package org.reextractorplus.dto;

public enum Visibility {
    PUBLIC, PRIVATE, PROTECTED, PACKAGE;

    public String toString() {
        return this.name().toLowerCase();
    }
}
