package org.reextractorplus.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.reextractorplus.ReExtractor;
import org.reextractorplus.RefactoringMiner;
import org.reextractorplus.dto.RefactoringDetectionResults;
import org.reextractorplus.refactoring.Refactoring;
import org.remapper.service.GitService;
import org.remapper.util.GitServiceImpl;

import java.io.*;
import java.util.List;

public class RefactoringDetectionExperimentStarter {

    //TODO: please specify your local project path
    final String datasetPath = "/home/anno/dataset/";

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
                "spring-framework"
        };
        for (String projectName : projects) {
            new RefactoringDetectionExperimentStarter().start(projectName);
        }
    }

    private void start(String projectName) {
        ClassLoader classLoader = getClass().getClassLoader();
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        GitService gitService = new GitServiceImpl();
        try {
            InputStream stream = classLoader.getResourceAsStream("benchmark/refactoring detection/" + projectName + ".txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String commitId;
            while ((commitId = reader.readLine()) != null) {
                String projectPath = datasetPath + projectName;
                gitService.checkoutCurrent(projectPath, commitId);
                RefactoringMiner refactoringMiner = new RefactoringMiner();
                ReExtractor reExtractor = new ReExtractor();
                List<Refactoring> refactorings1 = reExtractor.detectAtCommit(projectPath, commitId);
                List<org.refactoringminer.api.Refactoring> refactorings2 = refactoringMiner.detectAtCommit(projectPath, commitId);
                String filePath = "./data/refactoring detection/" + projectName + ".json";
                File file = new File(filePath);
                File directory = file.getParentFile();
                String remote_repo = GitServiceImpl.getRemoteUrl(projectPath);
                String remote_url = remote_repo.replace(".git", "/commit/") + commitId;
                if (remote_repo.equals("https://git.eclipse.org/r/jgit/jgit.git"))
                    remote_url = "https://git.eclipse.org/c/jgit/jgit.git/commit/?id=" + commitId;
                if (file.exists()) {
                    FileReader reader1 = new FileReader(filePath);
                    RefactoringDetectionResults results1 = gson.fromJson(reader1, RefactoringDetectionResults.class);
                    results1.populateJSON(remote_repo, commitId, remote_url, refactorings1, refactorings2);
                    String jsonString = gson.toJson(results1, RefactoringDetectionResults.class);
                    BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
                    out.write(jsonString);
                    out.close();
                } else {
                    if (!directory.exists())
                        directory.mkdirs();
                    file.createNewFile();
                    RefactoringDetectionResults results1 = new RefactoringDetectionResults();
                    results1.populateJSON(remote_repo, commitId, remote_url, refactorings1, refactorings2);
                    String jsonString = gson.toJson(results1, RefactoringDetectionResults.class);
                    BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
                    out.write(jsonString);
                    out.close();
                }
            }

            reader.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
