package org.reextractorplus.dto;

import java.util.ArrayList;
import java.util.List;

public class RefactoringDetectionResults {

    private List<Result> results;

    public RefactoringDetectionResults() {
        results = new ArrayList<>();
    }

    public void populateJSON(String repository, String sha1, String url,
                             List<org.reextractorplus.refactoring.Refactoring> refactoring1,
                             List<org.refactoringminer.api.Refactoring> refactoring2) {
        Result result = new Result(repository, sha1, url);
        for (org.reextractorplus.refactoring.Refactoring refactoring : refactoring1) {
            Refactoring refactor = new Refactoring(refactoring);
            result.addOurApproach(refactor);
        }
        for (org.refactoringminer.api.Refactoring refactoring : refactoring2) {
            Refactoring refactor = new Refactoring(refactoring);
            result.addBaseline(refactor);
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
        private List<Refactoring> ourApproach;
        private List<Refactoring> baseline;

        public Result(String repository, String sha1, String url) {
            this.repository = repository;
            this.sha1 = sha1;
            this.url = url;
            ourApproach = new ArrayList<>();
            baseline = new ArrayList<>();
        }

        public void addOurApproach(Refactoring refactoring) {
            ourApproach.add(refactoring);
        }

        public void addBaseline(Refactoring refactoring) {
            baseline.add(refactoring);
        }

        public List<Refactoring> getOurApproach() {
            return ourApproach;
        }

        public List<Refactoring> getBaseline() {
            return baseline;
        }

        public String getSha1() {
            return sha1;
        }
    }

    public class Refactoring {
        private String type;
        private String description;

        public Refactoring(org.refactoringminer.api.Refactoring refactoring) {
            this.type = refactoring.getRefactoringType().toString();
            this.description = refactoring.toString();
        }

        public Refactoring(org.reextractorplus.refactoring.Refactoring refactoring) {
            this.type = refactoring.getRefactoringType().toString();
            this.description = refactoring.toString();
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }
    }
}
