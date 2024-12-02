package org.reextractor.dto;

import com.google.common.collect.Sets;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EntityMatchingResults {

    private List<Result> results;

    public EntityMatchingResults() {
        this.results = new ArrayList<>();
    }

    public void populateJSON(String repository, String sha1, String url,
                             Sets.SetView<Pair<EntityComparator, EntityComparator>> intersection,
                             Sets.SetView<Pair<EntityComparator, EntityComparator>> differenceOfApproach,
                             Sets.SetView<Pair<EntityComparator, EntityComparator>> differenceOfBaseline) {
        Result result = new Result(repository, sha1, url);
        for (Pair<EntityComparator, EntityComparator> pair : intersection) {
            EntityComparator left = pair.getLeft();
            EntityComparator right = pair.getRight();
            if (left.getType() == EntityType.STATEMENT && right.getType() == EntityType.STATEMENT) {
                if (left.getName().equals(right.getName()))
                    continue;
            }
            if (left.getType() == EntityType.METHOD && right.getType() == EntityType.METHOD) {
                MethodDeclaration declaration1 = (MethodDeclaration) left.getDeclaration();
                MethodDeclaration declaration2 = (MethodDeclaration) right.getDeclaration();
                if (declaration1.toString().equals(declaration2.toString()))
                    continue;
            }
            result.addCommonMatching(pair);
        }
        for (Pair<EntityComparator, EntityComparator> pair : differenceOfApproach) {
            result.addOurApproach(pair);
        }
        for (Pair<EntityComparator, EntityComparator> pair : differenceOfBaseline) {
            result.addBaseline(pair);
        }
        results.add(result);
    }

    public List<Result> getResults() {
        return results;
    }

    public class Result {
        private String repository;
        private String sha1;
        private String url;
        private List<Entity> commonMatching;
        private List<Entity> ourApproach;
        private List<Entity> baseline;

        public Result(String repository, String sha1, String url) {
            this.repository = repository;
            this.sha1 = sha1;
            this.url = url;
            commonMatching = new ArrayList<>();
            ourApproach = new ArrayList<>();
            baseline = new ArrayList<>();
        }

        public void addCommonMatching(Pair<EntityComparator, EntityComparator> pair) {
            Location left = new Location(pair.getLeft());
            Location right = new Location(pair.getRight());
            Entity entity = new Entity(left, right);
            commonMatching.add(entity);
        }

        public void addOurApproach(Pair<EntityComparator, EntityComparator> pair) {
            Location left = new Location(pair.getLeft());
            Location right = new Location(pair.getRight());
            Entity entity = new Entity(left, right);
            ourApproach.add(entity);
        }

        public void addBaseline(Pair<EntityComparator, EntityComparator> pair) {
            Location left = new Location(pair.getLeft());
            Location right = new Location(pair.getRight());
            Entity entity = new Entity(left, right);
            baseline.add(entity);
        }

        public List<Entity> getCommonMatching() {
            return commonMatching;
        }

        public List<Entity> getOurApproach() {
            return ourApproach;
        }

        public List<Entity> getBaseline() {
            return baseline;
        }
    }

    public class Entity {
        private final Location leftSideLocation;
        private final Location rightSideLocation;
        private boolean validation;

        public Entity(Location leftSideLocation, Location rightSideLocation) {
            this.leftSideLocation = leftSideLocation;
            this.rightSideLocation = rightSideLocation;
        }

        public Location getLeftSideLocation() {
            return leftSideLocation;
        }

        public Location getRightSideLocation() {
            return rightSideLocation;
        }

        public boolean isValidation() {
            return validation;
        }

        @Override
        public int hashCode() {
            // 基于左侧位置、右侧位置以及 validation 状态
            return Objects.hash(leftSideLocation, rightSideLocation, validation);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Entity other = (Entity) obj;
            return validation == other.validation &&
                    Objects.equals(leftSideLocation, other.leftSideLocation) &&
                    Objects.equals(rightSideLocation, other.rightSideLocation);
        }
    }

    public class Location {
        private final String container;
        private final String type;
        private final String name;
        private final String filePath;
        private final int startLine;
        private final int endLine;
        private final int startColumn;
        private final int endColumn;

        public Location(EntityComparator entity) {
            this.container = entity.getContainer();
            this.type = entity.getType().getName();
            this.name = entity.getName();
            this.filePath = entity.getLocationInfo().getFilePath();
            this.startLine = entity.getLocationInfo().getStartLine();
            this.endLine = entity.getLocationInfo().getEndLine();
            this.startColumn = entity.getLocationInfo().getStartColumn();
            this.endColumn = entity.getLocationInfo().getEndColumn();
        }

        public String getType() {
            return type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(container, type, name, filePath, startLine, endLine, startColumn, endColumn);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Location other = (Location) obj;
            return startLine == other.startLine &&
                    endLine == other.endLine &&
                    startColumn == other.startColumn &&
                    endColumn == other.endColumn &&
                    Objects.equals(container, other.container) &&
                    Objects.equals(type, other.type) &&
                    Objects.equals(name, other.name) &&
                    Objects.equals(filePath, other.filePath);
        }
    }
}
