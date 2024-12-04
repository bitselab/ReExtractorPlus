package org.reextractor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.reextractor.dto.RefactoringDetectionResults;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class Count {

    private static final String jsonPath = "E:\\contributions\\TSE2024\\ReExtractorPlus\\data\\refactoring detection\\ref-Dataset\\";

    public static void main(String[] args) {
        List<RefactoringDetectionResults.Refactoring> commonTPs = new ArrayList<>();
        List<RefactoringDetectionResults.Refactoring> commonFPs = new ArrayList<>();
        List<RefactoringDetectionResults.Refactoring> ourTPs = new ArrayList<>();
        List<RefactoringDetectionResults.Refactoring> ourFPs = new ArrayList<>();
        List<RefactoringDetectionResults.Refactoring> baselineTPs = new ArrayList<>();
        List<RefactoringDetectionResults.Refactoring> baselineFPs = new ArrayList<>();
        String[] projects = new String[]{
                "checkstyle",
                "commons-io",
                "commons-lang",
                "elasticsearch",
                "flink",
                "hadoop",
                "hibernate-orm",
                "hibernate-search",
                "intellij-community",
                "javaparser",
                "jetty.project",
                "jgit",
                "junit4",
                "junit5",
                "lucene-solr",
                "mockito",
                "okhttp",
                "pmd",
                "spring-boot",
                "spring-framework",
        };
        for (String projectName : projects) {
            new Count().start(projectName, commonTPs, commonFPs, ourTPs, ourFPs, baselineTPs, baselineFPs);
        }
        int commonTP = 0;
        int commonFP = 0;
        int ourTP = 0;
        int ourFP = 0;
        int ourFN = 0;
        int baselineTP = 0;
        int baselineFP = 0;
        int baselineFN = 0;

        for (RefactoringDetectionResults.Refactoring refactoring : commonTPs) {
                commonTP++;
        }
        for (RefactoringDetectionResults.Refactoring refactoring : commonFPs) {
                commonFP++;
        }
        for (RefactoringDetectionResults.Refactoring refactoring : ourTPs) {
                ourTP++;
        }
        for (RefactoringDetectionResults.Refactoring refactoring : ourFPs) {
                ourFP++;
        }
        for (RefactoringDetectionResults.Refactoring refactoring : baselineTPs) {
                baselineTP++;
        }
        for (RefactoringDetectionResults.Refactoring refactoring : baselineFPs) {
                baselineFP++;
        }
        ourFN = baselineTP;
        baselineFN = ourTP;
        ourTP += commonTP;
        baselineTP += commonTP;
        ourFP += commonFP;
        baselineFP += commonFP;
        System.out.println("ourTP:\t\t" + ourTP);
        System.out.println("ourFP:\t\t" + ourFP);
        System.out.println("ourFN:\t\t" + ourFN);
        System.out.println("baselineTP:\t" + baselineTP);
        System.out.println("baselineFP:\t" + baselineFP);
        System.out.println("baselineFN:\t" + baselineFN);
    }

    private void start(String projectName, List<RefactoringDetectionResults.Refactoring> commonTP, List<RefactoringDetectionResults.Refactoring> commonFP, List<RefactoringDetectionResults.Refactoring> ourTP, List<RefactoringDetectionResults.Refactoring> ourFP, List<RefactoringDetectionResults.Refactoring> baselineTP, List<RefactoringDetectionResults.Refactoring> baselineFP) {
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        try {
            String filePath = jsonPath + projectName + ".json";
            FileReader reader = new FileReader(filePath);
            RefactoringDetectionResults results = gson.fromJson(reader, RefactoringDetectionResults.class);
            List<RefactoringDetectionResults.Result> res = results.getResults();
            for (RefactoringDetectionResults.Result result : res) {
                List<RefactoringDetectionResults.Refactoring> commonRefactoring = result.getCommonRefactoring();
                for (RefactoringDetectionResults.Refactoring refactoring : commonRefactoring) {
                    if (refactoring.isValidation()) {
                        commonTP.add(refactoring);
                    } else {
                        commonFP.add(refactoring);
                    }
                }
                List<RefactoringDetectionResults.Refactoring> ourApproach = result.getOurApproach();
                for (RefactoringDetectionResults.Refactoring refactoring : ourApproach) {
                    if (refactoring.isValidation()) {
                        ourTP.add(refactoring);
                    } else {
                        ourFP.add(refactoring);
                    }
                }
                List<RefactoringDetectionResults.Refactoring> baseline = result.getBaseline();
                for (RefactoringDetectionResults.Refactoring refactoring : baseline) {
                    if (refactoring.isValidation()) {
                        baselineTP.add(refactoring);
                    } else {
                        baselineFP.add(refactoring);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
