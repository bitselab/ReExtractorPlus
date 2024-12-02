package org.reextractor.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RefactoringDetectionResults {

    private List<Result> results;

    public RefactoringDetectionResults() {
        results = new ArrayList<>();
    }

    public void populateJSON(String repository, String sha1, String url,
                             Map<org.refactoringminer.api.Refactoring, org.reextractor.refactoring.Refactoring> intersection,
                             List<org.refactoringminer.api.Refactoring> refactoring1,
                             List<org.reextractor.refactoring.Refactoring> refactoring2) {
        Result result = new Result(repository, sha1, url);
        for (org.refactoringminer.api.Refactoring refactoring : intersection.keySet()) {
            Refactoring refactor2 = new Refactoring(intersection.get(refactoring));
            result.addCommon(refactor2);
        }
        for (org.refactoringminer.api.Refactoring refactoring : refactoring1) {
            Refactoring refactor = new Refactoring(refactoring);
            result.addBaseline(refactor);
        }
        for (org.reextractor.refactoring.Refactoring refactoring : refactoring2) {
            Refactoring refactor = new Refactoring(refactoring);
            result.addOurApproach(refactor);
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
        private List<Refactoring> commonRefactoring;
        private List<Refactoring> ourApproach;
        private List<Refactoring> baseline;

        public Result(String repository, String sha1, String url) {
            this.repository = repository;
            this.sha1 = sha1;
            this.url = url;
            commonRefactoring = new ArrayList<>();
            ourApproach = new ArrayList<>();
            baseline = new ArrayList<>();
        }

        public void addCommon(Refactoring refactoring) {
            commonRefactoring.add(refactoring);
        }

        public void addOurApproach(Refactoring refactoring) {
            ourApproach.add(refactoring);
        }

        public void addBaseline(Refactoring refactoring) {
            baseline.add(refactoring);
        }

        public List<Refactoring> getCommonRefactoring() {
            return commonRefactoring;
        }

        public List<Refactoring> getOurApproach() {
            return ourApproach;
        }

        public List<Refactoring> getBaseline() {
            return baseline;
        }

        public String getRepository() {
            return repository;
        }

        public String getSha1() {
            return sha1;
        }

        public String getUrl() {
            return url;
        }
    }

    public class Refactoring {
        private String type;
        private String description;
        private boolean validation = true;

        public Refactoring(org.refactoringminer.api.Refactoring refactoring) {
            this.type = refactoring.getRefactoringType().toString();
            this.description = refactoring.toString();
        }

        public Refactoring(org.reextractor.refactoring.Refactoring refactoring) {
            this.type = refactoring.getRefactoringType().toString();
            this.description = refactoring.toString();
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }

        public boolean isValidation() {
            return validation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Refactoring that = (Refactoring) o;
            return Objects.equals(type, that.type) && Objects.equals(description, that.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, description);
        }
    }
}
