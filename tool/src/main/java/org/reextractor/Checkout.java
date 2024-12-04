package org.reextractor;

import org.eclipse.jgit.lib.Repository;
import org.remapper.service.GitService;
import org.remapper.util.GitServiceImpl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class Checkout {

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
            new Checkout().start(projectName);
        }
    }

    private void start(String projectName) {
        String projectPath = datasetPath + projectName;
        ClassLoader classLoader = getClass().getClassLoader();
        try {
            InputStream stream = classLoader.getResourceAsStream("benchmark/refactoring detection/" + projectName + ".txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String commitId;
            while ((commitId = reader.readLine()) != null) {
                GitService service = new GitServiceImpl();
                try (Repository repository = service.openRepository(projectPath)) {
                    service.resetHard(projectPath);
                    service.checkoutBranch(repository);
                    long start = System.nanoTime();
                    service.resetHard(projectPath);
                    service.checkoutCurrent(projectPath, commitId);
                    long end = System.nanoTime();
                    System.out.println(end - start);
                } catch (Exception ignore){
                    ignore.printStackTrace();
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
