package org.reextractorplus;

import gr.uom.java.xmi.decomposition.AbstractCodeFragment;
import gr.uom.java.xmi.decomposition.LeafMapping;
import gr.uom.java.xmi.diff.ExtractVariableRefactoring;
import gr.uom.java.xmi.diff.InlineVariableRefactoring;
import org.eclipse.jgit.lib.Repository;
import org.refactoringminer.api.*;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.refactoringminer.util.GitServiceImpl;

import java.util.ArrayList;
import java.util.List;

public class RefactoringMiner {

    public List<Refactoring> detectAtCommit(String folder, String commitId) {
        List<Refactoring> results = new ArrayList<>();
        GitService gitService = new GitServiceImpl();
        try (Repository repo = gitService.openRepository(folder)) {
            GitHistoryRefactoringMiner detector = new GitHistoryRefactoringMinerImpl();
            detector.detectAtCommit(repo, commitId, new RefactoringHandler() {
                @Override
                public void handle(String commitId, List<Refactoring> refactorings) {
                    for (Refactoring refactoring : refactorings) {
                        String type = refactoring.getRefactoringType().toString();
                        if (type.equals("RENAME_METHOD") || type.equals("MOVE_CLASS") || type.equals("EXTRACT_CLASS")
                                || type.equals("RENAME_CLASS") || type.equals("EXTRACT_OPERATION") || type.equals("INLINE_OPERATION")
                                || type.equals("RENAME_ATTRIBUTE") || type.equals("CHANGE_RETURN_TYPE") || type.equals("CHANGE_ATTRIBUTE_TYPE")
                                || type.equals("CHANGE_VARIABLE_TYPE") || type.equals("EXTRACT_SUPERCLASS") || type.equals("PULL_UP_OPERATION")
                                || type.equals("RENAME_VARIABLE") || type.equals("EXTRACT_AND_MOVE_OPERATION") || type.equals("PUSH_DOWN_OPERATION")
                                || type.equals("MOVE_OPERATION") || type.equals("MOVE_ATTRIBUTE") || type.equals("EXTRACT_INTERFACE")
                                || type.equals("EXTRACT_SUBCLASS") || type.equals("CHANGE_TYPE_DECLARATION_KIND")) {
                            results.add(refactoring);
                        }
                        if (refactoring.getRefactoringType() == RefactoringType.EXTRACT_VARIABLE) {
                            ExtractVariableRefactoring ref = (ExtractVariableRefactoring) refactoring;
                            List<LeafMapping> subExpressionMappings = ref.getSubExpressionMappings();
                            boolean equal = false;
                            for (LeafMapping mapping : subExpressionMappings) {
                                AbstractCodeFragment fragment1 = mapping.getFragment1();
                                AbstractCodeFragment fragment2 = mapping.getFragment2();
                                if (fragment1.getString().equals(fragment2.getString())) {
                                    equal = true;
                                    break;
                                }
                            }
                            if (!equal)
                                results.add(refactoring);
                        }
                        if (refactoring.getRefactoringType() == RefactoringType.INLINE_VARIABLE) {
                            InlineVariableRefactoring ref = (InlineVariableRefactoring) refactoring;
                            List<LeafMapping> subExpressionMappings = ref.getSubExpressionMappings();
                            boolean equal = false;
                            for (LeafMapping mapping : subExpressionMappings) {
                                AbstractCodeFragment fragment1 = mapping.getFragment1();
                                AbstractCodeFragment fragment2 = mapping.getFragment2();
                                if (fragment1.getString().equals(fragment2.getString())) {
                                    equal = true;
                                    break;
                                }
                            }
                            if (!equal)
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
