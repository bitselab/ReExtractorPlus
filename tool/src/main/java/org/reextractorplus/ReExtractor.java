package org.reextractorplus;

import org.eclipse.jgit.lib.Repository;
import org.reextractorplus.handler.RefactoringHandler;
import org.reextractorplus.refactoring.Refactoring;
import org.reextractorplus.service.RefactoringExtractorService;
import org.reextractorplus.service.RefactoringExtractorServiceImpl;
import org.remapper.service.GitService;
import org.remapper.util.GitServiceImpl;

import java.util.ArrayList;
import java.util.List;

public class ReExtractor {

    public List<Refactoring> detectAtCommit(String folder, String commitId) {
        List<Refactoring> results = new ArrayList<>();
        GitService gitService = new GitServiceImpl();
        try (Repository repo = gitService.openRepository(folder)) {
            RefactoringExtractorService service = new RefactoringExtractorServiceImpl();
            service.detectAtCommit(repo, commitId, new RefactoringHandler() {
                @Override
                public void handle(String commitId, List<Refactoring> refactorings) {
                    for (Refactoring refactoring : refactorings) {
                        String type = refactoring.getRefactoringType().toString();
                        if (type.equals("RENAME_METHOD") || type.equals("MOVE_CLASS") || type.equals("EXTRACT_CLASS")
                                || type.equals("RENAME_CLASS") || type.equals("EXTRACT_OPERATION") || type.equals("INLINE_OPERATION")
                                || type.equals("RENAME_ATTRIBUTE") || type.equals("CHANGE_RETURN_TYPE") || type.equals("CHANGE_ATTRIBUTE_TYPE")
                                || type.equals("EXTRACT_VARIABLE") || type.equals("INLINE_VARIABLE") || type.equals("CHANGE_VARIABLE_TYPE")
                                || type.equals("EXTRACT_SUPERCLASS") || type.equals("PULL_UP_OPERATION") || type.equals("RENAME_VARIABLE")
                                || type.equals("EXTRACT_AND_MOVE_OPERATION") || type.equals("PUSH_DOWN_OPERATION")
                                || type.equals("MOVE_OPERATION") || type.equals("MOVE_ATTRIBUTE") || type.equals("EXTRACT_INTERFACE")
                                || type.equals("EXTRACT_SUBCLASS") || type.equals("CHANGE_TYPE_DECLARATION_KIND")) {
                            results.add(refactoring);
                        }
                    }
                }

                @Override
                public void handleException(String commit, Exception e) {
                    System.err.println("Error processing commit " + commit);
                    e.printStackTrace(System.err);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }
}
