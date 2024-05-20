package org.reextractor.dto;

import org.eclipse.jdt.core.dom.ASTNode;
import org.remapper.dto.LocationInfo;

import java.util.Objects;

public class EntityInfo {

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

    public void setLocationInfo(LocationInfo locationInfo) {
        this.location = locationInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EntityInfo that = (EntityInfo) o;
        return container.equals(that.container) && type == that.type && name.equals(that.name) && location.equals(that.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(container, type, name, location);
    }

    @Override
    public String toString() {
        return "EntityInfo{" +
                "container='" + container + '\'' +
                ", type=" + type +
                ", name='" + name + '\'' +
                ", startLine=" + location.getStartLine() +
                ", endLine=" + location.getEndLine() +
                '}';
    }
}
