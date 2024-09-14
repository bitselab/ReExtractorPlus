package org.reextractor.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.jgit.lib.Repository;
import org.reextractor.dto.RefactoringDiscoveryJSON;
import org.reextractor.handler.RefactoringHandler;
import org.reextractor.refactoring.Refactoring;
import org.reextractor.service.RefactoringExtractorService;
import org.reextractor.service.RefactoringExtractorServiceImpl;
import org.remapper.dto.MatchPair;
import org.remapper.service.GitService;
import org.remapper.util.GitServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // 允许跨域请求
public class StringController {

    @PostMapping("/diff")
    public ResponseEntity<Object> processStrings(@RequestBody Map<String, String> requestBody) {

        String folder = requestBody.get("repository");
        String commitId = requestBody.get("commitid");

        GitService gitService = new GitServiceImpl();
        List<String> content = new ArrayList<>();

        try (Repository repo = gitService.openRepository(folder)) {
            String gitURL = GitServiceImpl.getRemoteUrl(folder);
            RefactoringExtractorService extractor = new RefactoringExtractorServiceImpl();
            extractor.detectAtCommit(repo, commitId, new RefactoringHandler() {
                @Override
                public void handle(String commitId, MatchPair matchPair, List<Refactoring> refactorings) {
                    Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
                    String url = gitURL.replace(".git", "/commit/") + commitId;
                    RefactoringDiscoveryJSON results = new RefactoringDiscoveryJSON();
                    results.populateJSON(gitURL, commitId, url, matchPair, refactorings);
                    content.add(gson.toJson(results, RefactoringDiscoveryJSON.class));
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ResponseEntity.ok(content.get(0));
    }
}
