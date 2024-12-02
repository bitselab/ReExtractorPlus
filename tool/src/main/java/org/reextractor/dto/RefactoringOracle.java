package org.reextractor.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RefactoringOracle {

    private List<Result> results;

    public RefactoringOracle() {
        results = new ArrayList<>();
    }

    public void populateJSON(int id, String repository, String sha1, String url,
                             Map<org.refactoringminer.api.Refactoring, org.reextractor.refactoring.Refactoring> intersection,
                             List<org.refactoringminer.api.Refactoring> refactoring1,
                             List<org.reextractor.refactoring.Refactoring> refactoring2,
                             List<RefactoringData.Refactoring> refactorings) {
        Result result = new Result(id, repository, sha1, url);
        for (org.refactoringminer.api.Refactoring refactoring : intersection.keySet()) {
            Refactoring refactor2 = new Refactoring(intersection.get(refactoring), true);
            result.addCommon(refactor2);
        }
        for (org.refactoringminer.api.Refactoring refactoring : refactoring1) {
            boolean notExist = true;
            for (RefactoringData.Refactoring ref : refactorings) {
                if (refactoring.toString().replace("\t", " ").equals(ref.getDescription())) {
                    Refactoring refactor = new Refactoring(refactoring, true);
                    result.addBaseline(refactor);
                    notExist = false;
                    break;
                }
            }
            if (notExist) {
                Refactoring refactor = new Refactoring(refactoring, false);
                result.addBaseline(refactor);
            }
        }
        for (org.reextractor.refactoring.Refactoring refactoring : refactoring2) {
            Refactoring refactor = new Refactoring(refactoring, false);
            result.addOurApproach(refactor);
        }
        results.add(result);
    }

    public List<Result> getResults() {
        return results;
    }

    public class Result {

        private int id;
        private String repository;
        private String sha1;
        private String url;
        private List<Refactoring> commonRefactoring;
        private List<Refactoring> ourApproach;
        private List<Refactoring> baseline;

        public Result(int id, String repository, String sha1, String url) {
            this.id = id;
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

        public int getId() {
            return id;
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
        private boolean validation;

        public Refactoring(org.refactoringminer.api.Refactoring refactoring, boolean validation) {
            this.type = refactoring.getRefactoringType().toString();
            this.description = refactoring.toString();
            this.validation = validation;
        }

        public Refactoring(org.reextractor.refactoring.Refactoring refactoring, boolean validation) {
            this.type = refactoring.getRefactoringType().toString();
            this.description = refactoring.toString();
            this.validation = validation;
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

        public void setValidation(boolean validation) {
            this.validation = validation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Refactoring that = (Refactoring) o;
            return Objects.equals(description, that.description);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(description);
        }
    }
}
