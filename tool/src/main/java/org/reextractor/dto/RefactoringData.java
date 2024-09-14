package org.reextractor.dto;

import java.util.List;

public class RefactoringData {

    private int id;
    private String repository;
    private String sha1;
    private String url;
    private String author;
    private String time;
    private List<Refactoring> refactorings;
    private int refDiffExecutionTime;

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

    public String getAuthor() {
        return author;
    }

    public String getTime() {
        return time;
    }

    public List<Refactoring> getRefactorings() {
        return refactorings;
    }

    public int getRefDiffExecutionTime() {
        return refDiffExecutionTime;
    }

    public static class Refactoring {
        private String type;
        private String description;
        private String comment;
        private String validation;
        private String detectionTools;
        private String validators;

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }

        public String getComment() {
            return comment;
        }

        public String getValidation() {
            return validation;
        }

        public String getDetectionTools() {
            return detectionTools;
        }

        public String getValidators() {
            return validators;
        }
    }
}
