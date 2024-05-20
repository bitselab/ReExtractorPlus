package org.reextractor;

import org.eclipse.jgit.lib.Repository;
import org.reextractor.handler.RefactoringHandler;
import org.reextractor.refactoring.Refactoring;
import org.reextractor.refactoring.RefactoringType;
import org.reextractor.service.RefactoringExtractorService;
import org.reextractor.service.RefactoringExtractorServiceImpl;
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
                    results.addAll(filter(refactorings));
                }

                @Override
                public void handleException(String commit, Exception e) {
                    System.err.println("Error processing commit " + commit);
                    e.printStackTrace(System.err);
                }
            });
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return results;
    }

    private List<Refactoring> filter(List<Refactoring> refactorings) {
        List<Refactoring> list = new ArrayList<>();
        for (Refactoring refactoring : refactorings) {
            RefactoringType refactoringType = refactoring.getRefactoringType();
            if (refactoringType == RefactoringType.RENAME_CLASS)
                list.add(refactoring);
            if (refactoringType == RefactoringType.RENAME_METHOD)
                list.add(refactoring);
            if (refactoringType == RefactoringType.RENAME_ATTRIBUTE)
                list.add(refactoring);
            if (refactoringType == RefactoringType.RENAME_VARIABLE)
                list.add(refactoring);

            if (refactoringType == RefactoringType.MOVE_CLASS)
                list.add(refactoring);
            if (refactoringType == RefactoringType.MOVE_RENAME_CLASS)
                list.add(refactoring);
            if (refactoringType == RefactoringType.MOVE_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.MOVE_AND_RENAME_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.MOVE_ATTRIBUTE)
                list.add(refactoring);
            if (refactoringType == RefactoringType.MOVE_RENAME_ATTRIBUTE)
                list.add(refactoring);
            if (refactoringType == RefactoringType.PULL_UP_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.PUSH_DOWN_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.PULL_UP_ATTRIBUTE)
                list.add(refactoring);
            if (refactoringType == RefactoringType.PUSH_DOWN_ATTRIBUTE)
                list.add(refactoring);

            if (refactoringType == RefactoringType.EXTRACT_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.EXTRACT_AND_MOVE_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.EXTRACT_CLASS)
                list.add(refactoring);
            if (refactoringType == RefactoringType.EXTRACT_SUPERCLASS)
                list.add(refactoring);
            if (refactoringType == RefactoringType.EXTRACT_SUBCLASS)
                list.add(refactoring);
            if (refactoringType == RefactoringType.EXTRACT_INTERFACE)
                list.add(refactoring);
            if (refactoringType == RefactoringType.EXTRACT_VARIABLE)
                list.add(refactoring);

            if (refactoringType == RefactoringType.INLINE_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.MOVE_AND_INLINE_OPERATION)
                list.add(refactoring);
            if (refactoringType == RefactoringType.INLINE_VARIABLE)
                list.add(refactoring);

            if (refactoringType == RefactoringType.CHANGE_RETURN_TYPE)
                list.add(refactoring);
            if (refactoringType == RefactoringType.CHANGE_TYPE_DECLARATION_KIND)
                list.add(refactoring);
            if (refactoringType == RefactoringType.CHANGE_ATTRIBUTE_TYPE)
                list.add(refactoring);
            if (refactoringType == RefactoringType.CHANGE_VARIABLE_TYPE)
                list.add(refactoring);
        }
        return list;
    }
}
