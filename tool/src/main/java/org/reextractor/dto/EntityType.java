package org.reextractor.dto;

public enum EntityType {

    COMPILATION_UNIT("Compilation Unit"),
    PACKAGE("Package"),
    CLASS("Class"),
    INTERFACE("Interface"),
    ENUM("Enum"),
    RECORD("Record"),
    ANNOTATION_TYPE("Annotation Type"),
    INITIALIZER("Initializer"),
    FIELD("Field"),
    METHOD("Method"),
    ANNOTATION_MEMBER("Annotation Member"),
    ENUM_CONSTANT("Enum Constant"),
    STATEMENT("Statement");

    private final String name;

    EntityType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
