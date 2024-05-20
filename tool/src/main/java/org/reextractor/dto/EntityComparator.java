package org.reextractor.dto;

import org.eclipse.jdt.core.dom.ASTNode;

import java.util.Objects;

public class EntityComparator {

    private String container;
    private EntityType type;
    private String name;
    private ASTNode declaration;
    private LocationInfo location;

    public String getContainer() {
        return container;
    }

    public void setContainer(String container) {
        this.container = container;
    }

    public EntityType getType() {
        return type;
    }

    public void setType(EntityType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ASTNode getDeclaration() {
        return declaration;
    }

    public void setDeclaration(ASTNode declaration) {
        this.declaration = declaration;
    }

    public LocationInfo getLocationInfo() {
        return location;
    }

    public void setLocationInfo(LocationInfo location) {
        this.location = location;
    }

    public void setType(String type) {
        switch (type) {
            case "Compilation Unit":
                this.type = EntityType.COMPILATION_UNIT;
                break;
            case "Class":
                this.type = EntityType.CLASS;
                break;
            case "Interface":
                this.type = EntityType.INTERFACE;
                break;
            case "Enum":
                this.type = EntityType.ENUM;
                break;
            case "Record":
                this.type = EntityType.RECORD;
                break;
            case "Annotation Type":
                this.type = EntityType.ANNOTATION_TYPE;
                break;
            case "Initializer":
                this.type = EntityType.INITIALIZER;
                break;
            case "Field":
                this.type = EntityType.FIELD;
                break;
            case "Method":
                this.type = EntityType.METHOD;
                break;
            case "Annotation Member":
                this.type = EntityType.ANNOTATION_MEMBER;
                break;
            case "Enum Constant":
                this.type = EntityType.ENUM_CONSTANT;
                break;
            case "Statement":
                this.type = EntityType.STATEMENT;
                break;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EntityComparator that = (EntityComparator) o;
        return location.getFilePath().equals(that.location.getFilePath()) && location.getStartLine() == that.location.getStartLine() &&
                location.getEndLine() == that.location.getEndLine();
    }

    @Override
    public int hashCode() {
        return Objects.hash(location.getFilePath(), location.getStartLine(), location.getEndLine());
    }

    @Override
    public String toString() {
        return "EntityComparator{" +
                "container='" + container + '\'' +
                ", label=" + type +
                ", name='" + name + '\'' +
                ", startLine='" + location.getStartLine() + '\'' +
                ", endLine='" + location.getEndLine() + '\'' +
                '}';
    }
}
