package org.reextractor;

import org.eclipse.jgit.lib.Repository;
import org.json.*;
import org.refactoringminer.api.GitService;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.refactoringminer.util.GitServiceImpl;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.List;

public class Replicate {
    //TODO: Json file path
    private static final String jsonPath = "E:\\ReExtractorPlus\\data\\refactoring detection\\ref-Dataset";
    //TODO: Dataset path
    private static final String datasetPath = "E:\\Dataset";
    public static void main(String[] args) throws Exception {
        File root = new File(jsonPath);
        GitHistoryRefactoringMinerImpl miner = new GitHistoryRefactoringMinerImpl();
        int oracle = 0;
        int tps = 0;
        int fns = 0;
        if(root.isDirectory()) {
            File[] files = root.listFiles();
            for(File file : files) {
                if(file.getName().endsWith(".json")) {
                    String jsonString = Files.readString(file.toPath());
                    JSONObject obj = new JSONObject(jsonString);
                    JSONArray array = obj.getJSONArray("results");
                    for (int i = 0; i < array.length(); i++)
                    {
                        final List<String> Oracles = new LinkedList();
                        final List<String> descriptions = new LinkedList<>();
                        final List<String> TPs = new LinkedList<>();
                        JSONObject jsonObject = array.getJSONObject(i);
                        String commitId = jsonObject.getString("sha1");

                        JSONArray refactoringArray = jsonObject.getJSONArray("ourApproach");
                        for (int j = 0; j < refactoringArray.length(); j++)
                        {
                            JSONObject refactoringObject = refactoringArray.getJSONObject(j);
                            boolean confirmation = refactoringObject.getBoolean("validation");
                            if(confirmation) {
                                String description = refactoringObject.getString("description");
                                descriptions.add(description);
                                Oracles.add(description);
                            }
                        }
                        JSONArray refactoringArray2 = jsonObject.getJSONArray("baseline");
                        for (int j = 0; j < refactoringArray2.length(); j++)
                        {
                            JSONObject refactoringObject = refactoringArray2.getJSONObject(j);
                            boolean confirmation = refactoringObject.getBoolean("validation");
                            if(confirmation) {
                                String description = refactoringObject.getString("description");
                                descriptions.add(description);
                                Oracles.add(description);
                            }
                        }
                        JSONArray refactoringArray3 = jsonObject.getJSONArray("commonRefactoring");
                        for (int j = 0; j < refactoringArray3.length(); j++)
                        {
                            JSONObject refactoringObject = refactoringArray3.getJSONObject(j);
                            boolean confirmation = refactoringObject.getBoolean("validation");
                            if(confirmation) {
                                String description = refactoringObject.getString("description");
                                descriptions.add(description);
                                Oracles.add(description);
                            }
                        }
                        GitService service = new GitServiceImpl();
                        try (Repository repo = service.openRepository(datasetPath + "\\" + file.getName().replace(".json", ""))) {
                            miner.detectAtCommit(repo, commitId, new RefactoringHandler() {
                                @Override
                                public void handle(String commitId, List<Refactoring> refactorings) {
                                    for (Refactoring ref : refactorings) {
                                        if (Oracles.contains(ref.toString())) {
                                            TPs.add(ref.toString());
                                        }
                                        descriptions.remove(ref.toString());
                                    }
                                }
                            });
                            if (descriptions.size() > 0) {
                                System.out.println("False Negatives at " + commitId);
                                for (String s : descriptions) {
                                    System.out.println(s);
                                }
                            }
                        } catch (Exception ignored){}
                        oracle += Oracles.size();
                        tps += TPs.size();
                        fns += (Oracles.size() - TPs.size());
                    }
                }
            }
        }
        System.out.println("Expected:" + oracle + "\t" + "Found:" + tps + "\t" + "Missed:" + fns);
    }
}
