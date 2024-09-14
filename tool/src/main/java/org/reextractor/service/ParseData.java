package org.reextractor.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.reextractor.ReExtractor;
import org.reextractor.RefactoringMiner;
import org.reextractor.dto.RefactoringData;
import org.reextractor.dto.RefactoringOracle;
import org.reextractor.refactoring.Refactoring;
import org.remapper.service.GitService;
import org.remapper.util.GitServiceImpl;

import java.io.*;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseData {

    final String datasetPath = "/home/anno/dataset/";

    public static void main(String[] args) {
       new ParseData().start();
    }

    private void start() {
        Gson gson = new Gson();
        ClassLoader classLoader = getClass().getClassLoader();
        try {
            InputStream stream = classLoader.getResourceAsStream("benchmark/refactoring detection/data.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            Type commitListType = new TypeToken<List<RefactoringData>>() {
            }.getType();
            List<RefactoringData> list = gson.fromJson(reader, commitListType);
            for (RefactoringData data : list) {
                int id = data.getId();
                String repository = extractRepositoryPath(data.getRepository()).replace("/", "@");
                String commitId = data.getSha1();
                List<RefactoringData.Refactoring> refactorings = data.getRefactorings();
                try {
                    refactoringDiscover(id, repository, commitId, refactorings);
                } catch (Exception ignore) {
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String extractRepositoryPath(String url) {
        String regex = "https://github\\.com/([^/]+/[^/]+)\\.git";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return null;
        }
    }

    private void refactoringDiscover(int id, String projectName, String commitId, List<RefactoringData.Refactoring> refactorings) throws Exception {
        System.out.println(id + " --> " + projectName + " --> " + commitId);
        GitService gitService = new GitServiceImpl();
        String projectPath = datasetPath + projectName;
        gitService.checkoutCurrent(projectPath, commitId);
        gitService.resetHard(projectPath);
        RefactoringMiner refactoringMiner = new RefactoringMiner();
        ReExtractor reExtractor = new ReExtractor();
        List<org.refactoringminer.api.Refactoring> refactorings1 = refactoringMiner.detectAtCommit(projectPath, commitId);
        List<Refactoring> refactorings2 = reExtractor.detectAtCommit(projectPath, commitId);
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
        String filePath = "./data/refactoring detection/data.json";
        File file = new File(filePath);
        File directory = file.getParentFile();
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        String remote_repo = GitServiceImpl.getRemoteUrl(projectPath);
        String remote_url = remote_repo.replace(".git", "/commit/") + commitId;
        if (remote_repo.equals("https://git.eclipse.org/r/jgit/jgit.git"))
            remote_url = "https://git.eclipse.org/c/jgit/jgit.git/commit/?id=" + commitId;
        if (file.exists()) {
            FileReader reader1 = new FileReader(filePath);
            RefactoringOracle results1 = gson.fromJson(reader1, RefactoringOracle.class);
            results1.populateJSON(id, remote_repo, commitId, remote_url, intersection, refactorings1, refactorings2, refactorings);
            String jsonString = gson.toJson(results1, RefactoringOracle.class);
            BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
            out.write(jsonString);
            out.close();
        } else {
            if (!directory.exists())
                directory.mkdirs();
            file.createNewFile();
            RefactoringOracle results1 = new RefactoringOracle();
            results1.populateJSON(id, remote_repo, commitId, remote_url, intersection, refactorings1, refactorings2, refactorings);
            String jsonString = gson.toJson(results1, RefactoringOracle.class);
            BufferedWriter out = new BufferedWriter(new FileWriter(filePath));
            out.write(jsonString);
            out.close();
        }
    }
}
