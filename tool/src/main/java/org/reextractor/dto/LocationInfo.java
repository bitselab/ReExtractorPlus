package org.reextractor.dto;

import java.util.Objects;

public class LocationInfo {

    private String filePath;
    private int startLine;
    private int startColumn;
    private int endLine;
    private int endColumn;


    public LocationInfo() {}

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public void setStartColumn(int startColumn) {
        this.startColumn = startColumn;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public void setEndColumn(int endColumn) {
        this.endColumn = endColumn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LocationInfo that = (LocationInfo) o;
        return startLine == that.startLine && endLine == that.endLine && filePath.equals(that.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath, startLine, endLine);
    }

    public String toString() {
        return "line range:" + startLine + "-" + endLine;
    }

}
