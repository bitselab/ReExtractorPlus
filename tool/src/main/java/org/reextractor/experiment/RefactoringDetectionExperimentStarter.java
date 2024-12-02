package org.reextractor.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.reextractor.ReExtractor;
import org.reextractor.RefactoringMiner;
import org.reextractor.dto.RefactoringDetectionResults;
import org.reextractor.refactoring.Refactoring;
import org.remapper.service.GitService;
import org.remapper.util.GitServiceImpl;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RefactoringDetectionExperimentStarter {

    //TODO: please specify your local project path
    final String datasetPath = "E:/Dataset/";

    public static void main(String[] args) {
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
            new RefactoringDetectionExperimentStarter().start(projectName);
        }
    }

    private void start(String projectName) {
        ClassLoader classLoader = getClass().getClassLoader();
        try {
            InputStream stream = classLoader.getResourceAsStream("benchmark/refactoring detection/" + projectName + ".txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String commitId;
            while ((commitId = reader.readLine()) != null) {
                refactoringDiscover(projectName, commitId);
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void refactoringDiscover(String projectName, String commitId) throws Exception {
        GitService gitService = new GitServiceImpl();
        String projectPath = datasetPath + projectName;
        gitService.resetHard(projectPath);
        gitService.checkoutCurrent(projectPath, commitId);
        RefactoringMiner refactoringMiner = new RefactoringMiner();
        ReExtractor reExtractor = new ReExtractor();
        long time1 = System.nanoTime();
        List<org.refactoringminer.api.Refactoring> refactorings1 = refactoringMiner.detectAtCommit(projectPath, commitId);
        long time2 = System.nanoTime();
        List<Refactoring> refactorings2 = reExtractor.detectAtCommit(projectPath, commitId);
        long time3 = System.nanoTime();
        System.out.println("" + (time3- time2) + "\t" + (time2 - time1));
        Map<org.refactoringminer.api.Refactoring, Refactoring> intersection = new LinkedHashMap<>();
        for (org.refactoringminer.api.Refactoring refactoring1 : refactorings1) {
            for (Refactoring refactoring2 : refactorings2) {
                if (refactoring1.toString().equals(refactoring2.toString())) {
                    intersection.put(refactoring1, refactoring2);
                }
            }
        }
        refactorings1.removeAll(intersection.keySet());
        refactorings2.removeAll(intersection.values());
        String filePath = "./data/refactoring detection/" + projectName + ".json";
        File file = new File(filePath);
        File directory = file.getParentFile();
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        String remote_repo = GitServiceImpl.getRemoteUrl(projectPath);
        String remote_url = remote_repo.replace(".git", "/commit/") + commitId;
        if (file.exists()) {
            FileReader reader1 = new FileReader(filePath);
            RefactoringDetectionResults results1 = gson.fromJson(reader1, RefactoringDetectionResults.class);
            results1.populateJSON(remote_repo, commitId, remote_url, intersection, refactorings1, refactorings2);
            String jsonString = gson.toJson(results1, RefactoringDetectionResults.class);
            BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
            out.write(jsonString);
            out.close();
        } else {
            if (!directory.exists())
                directory.mkdirs();
            file.createNewFile();
            RefactoringDetectionResults results1 = new RefactoringDetectionResults();
            results1.populateJSON(remote_repo, commitId, remote_url, intersection, refactorings1, refactorings2);
            String jsonString = gson.toJson(results1, RefactoringDetectionResults.class);
            BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
            out.write(jsonString);
            out.close();
        }
    }
}
