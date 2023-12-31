package org.reextractorplus.service;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.reextractorplus.dto.AnnotationListDiff;
import org.reextractorplus.dto.Visibility;
import org.reextractorplus.handler.RefactoringHandler;
import org.reextractorplus.refactoring.*;
import org.reextractorplus.util.MethodUtils;
import org.reextractorplus.util.StringUtils;
import org.remapper.dto.*;
import org.remapper.handler.MatchingHandler;
import org.remapper.service.EntityMatcherService;
import org.remapper.service.EntityMatcherServiceImpl;
import org.remapper.util.DiceFunction;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class RefactoringExtractorServiceImpl implements RefactoringExtractorService {

    @Override
    public void detectAtCommit(Repository repository, String commitId, RefactoringHandler handler) {
        RevWalk walk = new RevWalk(repository);
        try {
            RevCommit commit = walk.parseCommit(repository.resolve(commitId));
            if (commit.getParentCount() > 0) {
                walk.parseCommit(commit.getParent(0));
                this.detectRefactorings(repository, handler, commit);
            }
        } catch (MissingObjectException ignored) {
        } catch (Exception e) {
            handler.handleException(commitId, e);
        } finally {
            walk.close();
            walk.dispose();
        }
    }

    @Override
    public void detectAtCommit(Repository repository, String commitId, RefactoringHandler handler, int timeout) {
        ExecutorService service = Executors.newSingleThreadExecutor();
        Future<?> f = null;
        try {
            Runnable r = () -> detectAtCommit(repository, commitId, handler);
            f = service.submit(r);
            f.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            service.shutdown();
        }
    }

    protected void detectRefactorings(Repository repository, final RefactoringHandler handler, RevCommit currentCommit) throws Exception {
        List<Refactoring> refactoringsAtRevision;
        EntityMatcherService service = new EntityMatcherServiceImpl();
        String commitId = currentCommit.getId().getName();
        if (currentCommit.getParentCount() > 0) {
            MatchPair matchPair = service.matchEntities(repository, currentCommit, new MatchingHandler() {
                @Override
                public void handleException(String commit, Exception e) {
                    System.err.println("Error processing commit " + commit);
                    e.printStackTrace(System.err);
                }
            });
            refactoringsAtRevision = detectRefactorings(matchPair);
        } else {
            refactoringsAtRevision = Collections.emptyList();
        }
        handler.handle(commitId, refactoringsAtRevision);
    }

    protected List<Refactoring> detectRefactorings(MatchPair matchPair) {
        List<Refactoring> refactorings = new ArrayList<>();
        Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities = matchPair.getMatchedEntities();
        Set<DeclarationNodeTree> inlinedEntities = matchPair.getInlinedEntities();
        Set<DeclarationNodeTree> extractedEntities = matchPair.getExtractedEntities();
        Set<DeclarationNodeTree> addedEntities = matchPair.getAddedEntities();
        detectRefactoringsInMatchedEntities(matchedEntities, refactorings);
        detectRefactoringsBetweenMatchedAndExtractedEntities(matchedEntities, extractedEntities, addedEntities, refactorings);
        detectRefactoringsBetweenMatchedAndInlinedEntities(matchedEntities, inlinedEntities, refactorings);

        Set<Pair<MethodNode, MethodNode>> methodNodePairs = mapMethodNodePairs(matchedEntities, inlinedEntities, extractedEntities);
        Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements = matchPair.getMatchedStatements();
        Set<StatementNodeTree> deletedStatements = matchPair.getDeletedStatements();
        Set<StatementNodeTree> addedStatements = matchPair.getAddedStatements();
        detectRefactoringsInMatchedStatements(matchedStatements, refactorings);
        detectRefactoringsBetweenMatchedAndAddedStatements(methodNodePairs, matchedStatements, addedStatements, refactorings);
        detectRefactoringsBetweenMatchedAndDeletedStatements(methodNodePairs, matchedStatements, deletedStatements, refactorings);
        detectRefactoringsBetweenAddedAndDeletedStatements(methodNodePairs, addedStatements, deletedStatements, matchPair, refactorings);

        return refactorings;
    }

    private Set<Pair<MethodNode, MethodNode>> mapMethodNodePairs(Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities,
                                                                 Set<DeclarationNodeTree> inlinedEntities,
                                                                 Set<DeclarationNodeTree> extractedEntities) {
        Set<Pair<MethodNode, MethodNode>> methodNodePairs = new LinkedHashSet<>();
        for (Pair<DeclarationNodeTree, DeclarationNodeTree> matchedEntity : matchedEntities) {
            DeclarationNodeTree left = matchedEntity.getLeft();
            DeclarationNodeTree right = matchedEntity.getRight();
            if (left.getType() == EntityType.METHOD && right.getType() == EntityType.METHOD)
                methodNodePairs.add(Pair.of(left.getMethodNode(), right.getMethodNode()));
        }
        for (DeclarationNodeTree inlinedEntity : inlinedEntities) {
            if (inlinedEntity.getType() == EntityType.METHOD) {
                List<EntityInfo> dependencies = inlinedEntity.getDependencies();
                for (Pair<DeclarationNodeTree, DeclarationNodeTree> matchedEntity : matchedEntities) {
                    DeclarationNodeTree left = matchedEntity.getLeft();
                    DeclarationNodeTree right = matchedEntity.getRight();
                    if (left.getType() == EntityType.METHOD && right.getType() == EntityType.METHOD) {
                        if (dependencies.contains(left.getEntity())) {
                            methodNodePairs.add(Pair.of(inlinedEntity.getMethodNode(), right.getMethodNode()));
                        }
                    }
                }
            }
        }
        for (DeclarationNodeTree extractedEntity : extractedEntities) {
            if (extractedEntity.getType() == EntityType.METHOD) {
                List<EntityInfo> dependencies = extractedEntity.getDependencies();
                for (Pair<DeclarationNodeTree, DeclarationNodeTree> matchedEntity : matchedEntities) {
                    DeclarationNodeTree left = matchedEntity.getLeft();
                    DeclarationNodeTree right = matchedEntity.getRight();
                    if (left.getType() == EntityType.METHOD && right.getType() == EntityType.METHOD) {
                        if (dependencies.contains(right.getEntity())) {
                            methodNodePairs.add(Pair.of(left.getMethodNode(), extractedEntity.getMethodNode()));
                        }
                    }
                }
            }
        }
        return methodNodePairs;
    }

    private void detectRefactoringsInMatchedEntities(Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities,
                                                     List<Refactoring> refactorings) {
        for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
            DeclarationNodeTree oldEntity = pair.getLeft();
            DeclarationNodeTree newEntity = pair.getRight();
            boolean isMove = !oldEntity.getNamespace().equals(newEntity.getNamespace()) &&
                    !matchedEntities.contains(Pair.of(oldEntity.getParent(), newEntity.getParent()));
            if (oldEntity.getType() == EntityType.METHOD && newEntity.getType() == EntityType.METHOD) {
                processOperations(isMove, matchedEntities, oldEntity, newEntity, refactorings);
            } else if (oldEntity.getType() == EntityType.FIELD && newEntity.getType() == EntityType.FIELD) {
                processAttributes(isMove, matchedEntities, oldEntity, newEntity, refactorings);
            } else if (oldEntity.getType() == EntityType.ENUM_CONSTANT && newEntity.getType() == EntityType.ENUM_CONSTANT) {
                processEnumConstants(isMove, oldEntity, newEntity, refactorings);
            } else if ((oldEntity.getType() == EntityType.CLASS || oldEntity.getType() == EntityType.INTERFACE ||
                    oldEntity.getType() == EntityType.ENUM) || (newEntity.getType() == EntityType.CLASS ||
                    newEntity.getType() == EntityType.INTERFACE || newEntity.getType() == EntityType.ENUM)) {
                processClasses(isMove, oldEntity, newEntity, refactorings);
            }
        }
    }

    private void processOperations(boolean isMove, Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities, DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity,
                                   List<Refactoring> refactorings) {
        MethodDeclaration removedOperation = (MethodDeclaration) oldEntity.getDeclaration();
        MethodDeclaration addedOperation = (MethodDeclaration) newEntity.getDeclaration();
        if (isMove) {
            if (isSubTypeOf(matchedEntities, oldEntity, newEntity)) {
                PullUpOperationRefactoring refactoring = new PullUpOperationRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            } else if (isSubTypeOf(matchedEntities, newEntity, oldEntity)) {
                PushDownOperationRefactoring refactoring = new PushDownOperationRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            } else {
                if (!oldEntity.getName().equals(newEntity.getName())) {
                    MoveAndRenameOperationRefactoring refactoring = new MoveAndRenameOperationRefactoring(oldEntity, newEntity);
                    refactorings.add(refactoring);
                } else {
                    MoveOperationRefactoring refactoring = new MoveOperationRefactoring(oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
            }
        } else {
            if (!oldEntity.getName().equals(newEntity.getName()) &&
                    !(removedOperation.isConstructor() && addedOperation.isConstructor())) {
                RenameOperationRefactoring refactoring = new RenameOperationRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
        String originalType = removedOperation.getReturnType2() == null ? "" : removedOperation.getReturnType2().toString();
        String changedType = addedOperation.getReturnType2() == null ? "" : addedOperation.getReturnType2().toString();
        if (!StringUtils.equals(originalType, changedType)) {
            ChangeReturnTypeRefactoring refactoring = new ChangeReturnTypeRefactoring(originalType, changedType, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        checkForOperationAnnotationChanges(oldEntity, newEntity, refactorings);
        checkForOperationModifierChanges(oldEntity, newEntity, refactorings);
        checkForThrownExceptionTypeChanges(oldEntity, newEntity, refactorings);
        checkForOperationParameterChanges(oldEntity, newEntity, refactorings);
    }

    private void processAttributes(boolean isMove, Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities, DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity,
                                   List<Refactoring> refactorings) {
        FieldDeclaration removedAttribute = (FieldDeclaration) oldEntity.getDeclaration();
        FieldDeclaration addedAttribute = (FieldDeclaration) newEntity.getDeclaration();
        if (isMove) {
            if (isSubTypeOf(matchedEntities, oldEntity, newEntity)) {
                PullUpAttributeRefactoring refactoring = new PullUpAttributeRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            } else if (isSubTypeOf(matchedEntities, newEntity, oldEntity)) {
                PushDownAttributeRefactoring refactoring = new PushDownAttributeRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            } else {
                if (!oldEntity.getName().equals(newEntity.getName())) {
                    MoveAndRenameAttributeRefactoring refactoring = new MoveAndRenameAttributeRefactoring(oldEntity, newEntity);
                    refactorings.add(refactoring);
                } else {
                    MoveAttributeRefactoring refactoring = new MoveAttributeRefactoring(oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
            }
        } else {
            if (!oldEntity.getName().equals(newEntity.getName())) {
                RenameAttributeRefactoring refactoring = new RenameAttributeRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
        String originalType = removedAttribute.getType().toString();
        String changedType = addedAttribute.getType().toString();
        if (!StringUtils.equals(originalType, changedType)) {
            ChangeAttributeTypeRefactoring refactoring = new ChangeAttributeTypeRefactoring(oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        checkForAttributeAnnotationChanges(oldEntity, newEntity, refactorings);
        checkForAttributeModifierChanges(oldEntity, newEntity, refactorings);
        List<MethodDeclaration> oldMethods = new ArrayList<>();
        List<MethodDeclaration> newMethods = new ArrayList<>();
        oldEntity.getDeclaration().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                oldMethods.add(node);
                return true;
            }
        });
        newEntity.getDeclaration().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                newMethods.add(node);
                return true;
            }
        });
        for (MethodDeclaration oldMethod : oldMethods) {
            for (MethodDeclaration newMethod : newMethods) {
                if (oldMethod.getName().getIdentifier().equals(newMethod.getName().getIdentifier()) &&
                        oldMethod.getReturnType2().toString().equals(newMethod.getReturnType2().toString()) &&
                        oldMethod.parameters().size() == newMethod.parameters().size() && equalsParameters(oldMethod, newMethod)) {
                    DeclarationNodeTree leftEntity = new LeafNode((CompilationUnit) oldMethod.getRoot(), oldEntity.getLocation().getFilePath(), oldMethod);
                    DeclarationNodeTree rightEntity = new LeafNode((CompilationUnit) newMethod.getRoot(), newEntity.getLocation().getFilePath(), newMethod);
                    leftEntity.setDeclaration(oldMethod);
                    leftEntity.setType(EntityType.METHOD);
                    leftEntity.setParent(new InternalNode((CompilationUnit) oldMethod.getRoot(), oldEntity.getLocation().getFilePath(), oldMethod));
                    leftEntity.getParent().setType(EntityType.CLASS);
                    if (oldMethod.getParent() instanceof AnonymousClassDeclaration && oldMethod.getParent().getParent() instanceof ClassInstanceCreation)
                        leftEntity.setNamespace(oldEntity.getNamespace() + "." + oldEntity.getName() + ".new " +
                                ((ClassInstanceCreation) oldMethod.getParent().getParent()).getType().toString());
                    rightEntity.setDeclaration(newMethod);
                    rightEntity.setType(EntityType.METHOD);
                    rightEntity.setParent(new InternalNode((CompilationUnit) oldMethod.getRoot(), oldEntity.getLocation().getFilePath(), oldMethod));
                    rightEntity.getParent().setType(EntityType.CLASS);
                    if (newMethod.getParent() instanceof AnonymousClassDeclaration && newMethod.getParent().getParent() instanceof ClassInstanceCreation)
                        rightEntity.setNamespace(newEntity.getNamespace() + "." + newEntity.getName() + ".new " +
                                ((ClassInstanceCreation) newMethod.getParent().getParent()).getType().toString());
                    checkForOperationAnnotationChanges(leftEntity, rightEntity, refactorings);
                    checkForOperationModifierChanges(leftEntity, rightEntity, refactorings);
                    checkForThrownExceptionTypeChanges(leftEntity, rightEntity, refactorings);
                }
            }
        }
    }

    private void processEnumConstants(boolean isMove, DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity,
                                      List<Refactoring> refactorings) {
        if (isMove) {
            if (!oldEntity.getName().equals(newEntity.getName())) {
                MoveAndRenameAttributeRefactoring refactoring = new MoveAndRenameAttributeRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            } else {
                MoveAttributeRefactoring refactoring = new MoveAttributeRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        } else {
            if (!oldEntity.getName().equals(newEntity.getName())) {
                RenameAttributeRefactoring refactoring = new RenameAttributeRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
        checkForEnumConstantAnnotationChanges(oldEntity, newEntity, refactorings);
    }

    private void processClasses(boolean isMove, DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity,
                                List<Refactoring> refactorings) {
        if (isMove) {
            if (!oldEntity.getName().equals(newEntity.getName())) {
                MoveAndRenameClassRefactoring refactoring = new MoveAndRenameClassRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            } else {
                MoveClassRefactoring refactoring = new MoveClassRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        } else {
            if (!oldEntity.getName().equals(newEntity.getName())) {
                RenameClassRefactoring refactoring = new RenameClassRefactoring(oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
        if (oldEntity.getType() != newEntity.getType()) {
            ChangeTypeDeclarationKindRefactoring refactoring = new ChangeTypeDeclarationKindRefactoring(oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        checkForClassAnnotationChanges(oldEntity, newEntity, refactorings);
        checkForClassModifierChanges(oldEntity, newEntity, refactorings);
    }

    private void checkForOperationAnnotationChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        MethodDeclaration removedOperation = (MethodDeclaration) oldEntity.getDeclaration();
        MethodDeclaration addedOperation = (MethodDeclaration) newEntity.getDeclaration();
        List<IExtendedModifier> modifiers1 = removedOperation.modifiers();
        List<IExtendedModifier> modifiers2 = addedOperation.modifiers();
        AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
        for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
            AddMethodAnnotationRefactoring refactoring = new AddMethodAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
            RemoveMethodAnnotationRefactoring refactoring = new RemoveMethodAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
            ModifyMethodAnnotationRefactoring refactoring = new ModifyMethodAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void checkForOperationModifierChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        MethodDeclaration removedOperation = (MethodDeclaration) oldEntity.getDeclaration();
        MethodDeclaration addedOperation = (MethodDeclaration) newEntity.getDeclaration();
        int methodModifiers1 = removedOperation.getModifiers();
        int methodModifiers2 = addedOperation.getModifiers();
        if (!Flags.isFinal(methodModifiers1) && Flags.isFinal(methodModifiers2)) {
            AddMethodModifierRefactoring refactoring = new AddMethodModifierRefactoring("final", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isFinal(methodModifiers1) && !Flags.isFinal(methodModifiers2)) {
            RemoveMethodModifierRefactoring refactoring = new RemoveMethodModifierRefactoring("final", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isAbstract(methodModifiers1) && Flags.isAbstract(methodModifiers2)) {
            AddMethodModifierRefactoring refactoring = new AddMethodModifierRefactoring("abstract", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isAbstract(methodModifiers1) && !Flags.isAbstract(methodModifiers2)) {
            RemoveMethodModifierRefactoring refactoring = new RemoveMethodModifierRefactoring("abstract", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isStatic(methodModifiers1) && Flags.isStatic(methodModifiers2)) {
            AddMethodModifierRefactoring refactoring = new AddMethodModifierRefactoring("static", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isStatic(methodModifiers1) && !Flags.isStatic(methodModifiers2)) {
            RemoveMethodModifierRefactoring refactoring = new RemoveMethodModifierRefactoring("static", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isSynchronized(methodModifiers1) && Flags.isSynchronized(methodModifiers2)) {
            AddMethodModifierRefactoring refactoring = new AddMethodModifierRefactoring("synchronized", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isSynchronized(methodModifiers1) && !Flags.isSynchronized(methodModifiers2)) {
            RemoveMethodModifierRefactoring refactoring = new RemoveMethodModifierRefactoring("synchronized", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        Visibility originalAccessModifier = getVisibility(methodModifiers1, oldEntity);
        Visibility changedAccessModifier = getVisibility(methodModifiers2, newEntity);
        if (originalAccessModifier != changedAccessModifier) {
            ChangeOperationAccessModifierRefactoring refactoring = new ChangeOperationAccessModifierRefactoring(originalAccessModifier, changedAccessModifier, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private Visibility getVisibility(int modifiers, DeclarationNodeTree entity) {
        Visibility visibility;
        boolean isInterfaceMethod = entity.getParent().getType() == EntityType.INTERFACE;
        if ((modifiers & Modifier.PUBLIC) != 0)
            visibility = Visibility.PUBLIC;
        else if ((modifiers & Modifier.PROTECTED) != 0)
            visibility = Visibility.PROTECTED;
        else if ((modifiers & Modifier.PRIVATE) != 0)
            visibility = Visibility.PRIVATE;
        else if (isInterfaceMethod)
            visibility = Visibility.PUBLIC;
        else
            visibility = Visibility.PACKAGE;
        return visibility;
    }

    private void checkForThrownExceptionTypeChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        MethodDeclaration removedOperation = (MethodDeclaration) oldEntity.getDeclaration();
        MethodDeclaration addedOperation = (MethodDeclaration) newEntity.getDeclaration();
        List<SimpleType> exceptionTypes1 = removedOperation.thrownExceptionTypes();
        List<SimpleType> exceptionTypes2 = addedOperation.thrownExceptionTypes();
        Set<SimpleType> addedExceptionTypes = new LinkedHashSet<>();
        Set<SimpleType> removedExceptionTypes = new LinkedHashSet<>();
        for (SimpleType exceptionType1 : exceptionTypes1) {
            boolean found = false;
            for (SimpleType exceptionType2 : exceptionTypes2) {
                if (exceptionType1.getName().getFullyQualifiedName().equals(exceptionType2.getName().getFullyQualifiedName())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                removedExceptionTypes.add(exceptionType1);
            }
        }
        for (SimpleType exceptionType2 : exceptionTypes2) {
            boolean found = false;
            for (SimpleType exceptionType1 : exceptionTypes1) {
                if (exceptionType1.getName().getFullyQualifiedName().equals(exceptionType2.getName().getFullyQualifiedName())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                addedExceptionTypes.add(exceptionType2);
            }
        }
        if (removedExceptionTypes.size() > 0 && addedExceptionTypes.size() == 0) {
            for (SimpleType exceptionType : removedExceptionTypes) {
                RemoveThrownExceptionTypeRefactoring refactoring = new RemoveThrownExceptionTypeRefactoring(exceptionType, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
        if (addedExceptionTypes.size() > 0 && removedExceptionTypes.size() == 0) {
            for (SimpleType exceptionType : addedExceptionTypes) {
                AddThrownExceptionTypeRefactoring refactoring = new AddThrownExceptionTypeRefactoring(exceptionType, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
        if (removedExceptionTypes.size() > 0 && addedExceptionTypes.size() > 0) {
            ChangeThrownExceptionTypeRefactoring refactoring = new ChangeThrownExceptionTypeRefactoring(removedExceptionTypes, addedExceptionTypes, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void checkForOperationParameterChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        MethodDeclaration removedOperation = (MethodDeclaration) oldEntity.getDeclaration();
        MethodDeclaration addedOperation = (MethodDeclaration) newEntity.getDeclaration();
        List<SingleVariableDeclaration> addedParameters = new ArrayList<>();
        List<SingleVariableDeclaration> removedParameters = new ArrayList<>();
        List<AbstractMap.SimpleEntry<SingleVariableDeclaration, SingleVariableDeclaration>> matchedParameters = new ArrayList<>();
        List<Pair<SingleVariableDeclaration, SingleVariableDeclaration>> parameterDiffList = new ArrayList<>();
        for (Object obj1 : removedOperation.parameters()) {
            SingleVariableDeclaration parameter1 = (SingleVariableDeclaration) obj1;
            boolean found = false;
            for (Object obj2 : addedOperation.parameters()) {
                SingleVariableDeclaration parameter2 = (SingleVariableDeclaration) obj2;
                if (equalsIncludingName(parameter1, parameter2)) {
                    matchedParameters.add(new AbstractMap.SimpleEntry<>(parameter1, parameter2));
                    found = true;
                    break;
                }
            }
            if (!found) {
                removedParameters.add(parameter1);
            }
        }
        for (Object obj1 : addedOperation.parameters()) {
            SingleVariableDeclaration parameter1 = (SingleVariableDeclaration) obj1;
            boolean found = false;
            for (Object obj2 : removedOperation.parameters()) {
                SingleVariableDeclaration parameter2 = (SingleVariableDeclaration) obj2;
                if (equalsIncludingName(parameter1, parameter2)) {
                    matchedParameters.add(new AbstractMap.SimpleEntry<>(parameter2, parameter1));
                    found = true;
                    break;
                }
            }
            if (!found) {
                addedParameters.add(parameter1);
            }
        }
        for (AbstractMap.SimpleEntry<SingleVariableDeclaration, SingleVariableDeclaration> matchedParameter : matchedParameters) {
            SingleVariableDeclaration parameter1 = matchedParameter.getKey();
            SingleVariableDeclaration parameter2 = matchedParameter.getValue();
            parameterDiffList.add(Pair.of(parameter1, parameter2));
        }
        int matchedParameterCount = matchedParameters.size() / 2;
        List<SingleVariableDeclaration> parameters1 = removedOperation.parameters();
        List<String> parameterNames1 = parameters1.stream().map(parameter -> parameter.getName().getIdentifier()).collect(Collectors.toList());
        for (SingleVariableDeclaration removedParameter : removedParameters) {
            parameterNames1.remove(removedParameter.getName().getIdentifier());
        }
        List<SingleVariableDeclaration> parameters2 = addedOperation.parameters();
        List<String> parameterNames2 = parameters2.stream().map(parameter -> parameter.getName().getIdentifier()).collect(Collectors.toList());
        for (SingleVariableDeclaration addedParameter : addedParameters) {
            parameterNames2.remove(addedParameter.getName().getIdentifier());
        }
        if (matchedParameterCount == parameterNames1.size() && matchedParameterCount == parameterNames2.size() &&
                parameterNames1.size() > 1 && !parameterNames1.equals(parameterNames2)) {
            ReorderParameterRefactoring refactoring = new ReorderParameterRefactoring(oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        //first round match parameters with the same name
        for (Iterator<SingleVariableDeclaration> removedParameterIterator = removedParameters.iterator(); removedParameterIterator.hasNext(); ) {
            SingleVariableDeclaration removedParameter = removedParameterIterator.next();
            for (Iterator<SingleVariableDeclaration> addedParameterIterator = addedParameters.iterator(); addedParameterIterator.hasNext(); ) {
                SingleVariableDeclaration addedParameter = addedParameterIterator.next();
                if (removedParameter.getName().getIdentifier().equals(addedParameter.getName().getIdentifier())) {
                    parameterDiffList.add(Pair.of(removedParameter, addedParameter));
                    addedParameterIterator.remove();
                    removedParameterIterator.remove();
                    break;
                }
            }
        }
        //second round match parameters with the same type
        for (Iterator<SingleVariableDeclaration> removedParameterIterator = removedParameters.iterator(); removedParameterIterator.hasNext(); ) {
            SingleVariableDeclaration removedParameter = removedParameterIterator.next();
            for (Iterator<SingleVariableDeclaration> addedParameterIterator = addedParameters.iterator(); addedParameterIterator.hasNext(); ) {
                SingleVariableDeclaration addedParameter = addedParameterIterator.next();
                if (removedParameter.getType().toString().equals(addedParameter.getType().toString()) &&
                        !existsAnotherAddedParameterWithTheSameType(removedOperation, addedOperation, addedParameters, addedParameter)) {
                    parameterDiffList.add(Pair.of(removedParameter, addedParameter));
                    addedParameterIterator.remove();
                    removedParameterIterator.remove();
                    break;
                }
            }
        }
        //third round match parameters with different type and name
        List<SingleVariableDeclaration> removedParametersWithoutReturnType = removedOperation.parameters();
        List<SingleVariableDeclaration> addedParametersWithoutReturnType = addedOperation.parameters();
        if (matchedParameterCount == removedParametersWithoutReturnType.size() - 1 && matchedParameterCount == addedParametersWithoutReturnType.size() - 1) {
            for (Iterator<SingleVariableDeclaration> removedParameterIterator = removedParameters.iterator(); removedParameterIterator.hasNext(); ) {
                SingleVariableDeclaration removedParameter = removedParameterIterator.next();
                int indexOfRemovedParameter = indexOfParameter(removedParametersWithoutReturnType, removedParameter);
                for (Iterator<SingleVariableDeclaration> addedParameterIterator = addedParameters.iterator(); addedParameterIterator.hasNext(); ) {
                    SingleVariableDeclaration addedParameter = addedParameterIterator.next();
                    int indexOfAddedParameter = indexOfParameter(addedParametersWithoutReturnType, addedParameter);
                    if (indexOfRemovedParameter == indexOfAddedParameter &&
                            usedParameters(removedOperation, addedOperation, removedParameter, addedParameter)) {
                        parameterDiffList.add(Pair.of(removedParameter, addedParameter));
                        addedParameterIterator.remove();
                        removedParameterIterator.remove();
                        break;
                    }
                }
            }
        }
        getParameterRefactorings(parameterDiffList, addedParameters, removedParameters, oldEntity, newEntity, refactorings);
    }

    private void getParameterRefactorings(List<Pair<SingleVariableDeclaration, SingleVariableDeclaration>> parameterDiffList,
                                          List<SingleVariableDeclaration> addedParameters, List<SingleVariableDeclaration> removedParameters,
                                          DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        for (Pair<SingleVariableDeclaration, SingleVariableDeclaration> parameterDiff : parameterDiffList) {
            SingleVariableDeclaration parameter1 = parameterDiff.getLeft();
            SingleVariableDeclaration parameter2 = parameterDiff.getRight();
            if (!parameter1.getName().getIdentifier().equals(parameter2.getName().getIdentifier())) {
                RenameParameterRefactoring refactoring = new RenameParameterRefactoring(parameter1, parameter2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            if (!parameter1.getType().toString().equals(parameter2.getType().toString())) {
                ChangeParameterTypeRefactoring refactoring = new ChangeParameterTypeRefactoring(parameter1, parameter2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            int parameterModifiers1 = parameter1.getModifiers();
            int parameterModifiers2 = parameter2.getModifiers();
            List<IExtendedModifier> modifiers1 = parameter1.modifiers();
            List<IExtendedModifier> modifiers2 = parameter2.modifiers();
            AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
            for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
                AddParameterAnnotationRefactoring refactoring = new AddParameterAnnotationRefactoring(annotation, parameter1, parameter2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
                RemoveParameterAnnotationRefactoring refactoring = new RemoveParameterAnnotationRefactoring(annotation, parameter1, parameter2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
                ModifyParameterAnnotationRefactoring refactoring = new ModifyParameterAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), parameter1, parameter2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            if (!Flags.isFinal(parameterModifiers1) && Flags.isFinal(parameterModifiers2)) {
                AddParameterModifierRefactoring refactoring = new AddParameterModifierRefactoring("final", parameter1, parameter2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            if (Flags.isFinal(parameterModifiers1) && !Flags.isFinal(parameterModifiers2)) {
                RemoveParameterModifierRefactoring refactoring = new RemoveParameterModifierRefactoring("final", parameter1, parameter2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
        for (SingleVariableDeclaration parameter : addedParameters) {
            AddParameterRefactoring refactoring = new AddParameterRefactoring(parameter, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (SingleVariableDeclaration parameter : removedParameters) {
            RemoveParameterRefactoring refactoring = new RemoveParameterRefactoring(parameter, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private boolean usedParameters(MethodDeclaration removedOperation, MethodDeclaration addedOperation,
                                   SingleVariableDeclaration removedParameter, SingleVariableDeclaration addedParameter) {
        List<String> removedOperationVariables = getAllVariables(removedOperation);
        List<String> addedOperationVariables = getAllVariables(addedOperation);
        if (removedOperationVariables.contains(removedParameter.getName()) ==
                addedOperationVariables.contains(addedParameter.getName())) {
            if (!removedOperation.isConstructor() && !addedOperation.isConstructor()) {
                return !removedOperationVariables.contains(addedParameter.getName()) &&
                        !addedOperationVariables.contains(removedParameter.getName());
            } else {
                return true;
            }
        }
        return false;
    }

    private List<String> getAllVariables(MethodDeclaration methodDeclaration) {
        List<String> operationVariables = new ArrayList<>();
        methodDeclaration.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                operationVariables.add(node.getName().getIdentifier());
                return true;
            }
        });
        return operationVariables;
    }

    private int indexOfParameter(List<SingleVariableDeclaration> parameters, SingleVariableDeclaration parameter) {
        int index = 0;
        for (SingleVariableDeclaration p : parameters) {
            if (equalsIncludingName(p, parameter)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private boolean existsAnotherAddedParameterWithTheSameType(MethodDeclaration removedOperation, MethodDeclaration addedOperation,
                                                               List<SingleVariableDeclaration> addedParameters, SingleVariableDeclaration parameter) {
        if (hasTwoParametersWithTheSameType(removedOperation) && hasTwoParametersWithTheSameType(addedOperation)) {
            return false;
        }
        for (SingleVariableDeclaration addedParameter : addedParameters) {
            if (!addedParameter.getName().equals(parameter.getName()) &&
                    addedParameter.getType().toString().equals(parameter.getType().toString())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasTwoParametersWithTheSameType(MethodDeclaration methodDeclaration) {
        List<SingleVariableDeclaration> parameterTypes = methodDeclaration.parameters();
        if (parameterTypes.size() == 2) {
            if (parameterTypes.get(0).getType().toString().equals(parameterTypes.get(1).getType().toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean equalsIncludingName(SingleVariableDeclaration parameter1, SingleVariableDeclaration parameter2) {
        return parameter1.getName().getIdentifier().equals(parameter2.getName().getIdentifier()) &&
                parameter1.getType().toString().equals(parameter2.getType().toString()) &&
                parameter1.isVarargs() == parameter2.isVarargs();
    }

    private void checkForAttributeAnnotationChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        FieldDeclaration removedAttribute = (FieldDeclaration) oldEntity.getDeclaration();
        FieldDeclaration addedAttribute = (FieldDeclaration) newEntity.getDeclaration();
        List<IExtendedModifier> modifiers1 = removedAttribute.modifiers();
        List<IExtendedModifier> modifiers2 = addedAttribute.modifiers();
        AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
        for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
            AddAttributeAnnotationRefactoring refactoring = new AddAttributeAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
            RemoveAttributeAnnotationRefactoring refactoring = new RemoveAttributeAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
            ModifyAttributeAnnotationRefactoring refactoring = new ModifyAttributeAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void checkForAttributeModifierChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        FieldDeclaration removedAttribute = (FieldDeclaration) oldEntity.getDeclaration();
        FieldDeclaration addedAttribute = (FieldDeclaration) newEntity.getDeclaration();
        int attributeModifiers1 = removedAttribute.getModifiers();
        int attributeModifiers2 = addedAttribute.getModifiers();
        if (!Flags.isFinal(attributeModifiers1) && Flags.isFinal(attributeModifiers2)) {
            AddAttributeModifierRefactoring refactoring = new AddAttributeModifierRefactoring("final", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isFinal(attributeModifiers1) && !Flags.isFinal(attributeModifiers2)) {
            RemoveAttributeModifierRefactoring refactoring = new RemoveAttributeModifierRefactoring("final", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isStatic(attributeModifiers1) && Flags.isStatic(attributeModifiers2)) {
            AddAttributeModifierRefactoring refactoring = new AddAttributeModifierRefactoring("static", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isStatic(attributeModifiers1) && !Flags.isStatic(attributeModifiers2)) {
            RemoveAttributeModifierRefactoring refactoring = new RemoveAttributeModifierRefactoring("static", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isTransient(attributeModifiers1) && Flags.isTransient(attributeModifiers2)) {
            AddAttributeModifierRefactoring refactoring = new AddAttributeModifierRefactoring("transient", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isTransient(attributeModifiers1) && !Flags.isTransient(attributeModifiers2)) {
            RemoveAttributeModifierRefactoring refactoring = new RemoveAttributeModifierRefactoring("transient", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isVolatile(attributeModifiers1) && Flags.isVolatile(attributeModifiers2)) {
            AddAttributeModifierRefactoring refactoring = new AddAttributeModifierRefactoring("volatile", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isVolatile(attributeModifiers1) && !Flags.isVolatile(attributeModifiers2)) {
            RemoveAttributeModifierRefactoring refactoring = new RemoveAttributeModifierRefactoring("volatile", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        Visibility originalAccessModifier = getVisibility(attributeModifiers1, oldEntity);
        Visibility changedAccessModifier = getVisibility(attributeModifiers2, newEntity);
        if (originalAccessModifier != changedAccessModifier) {
            ChangeAttributeAccessModifierRefactoring refactoring = new ChangeAttributeAccessModifierRefactoring(originalAccessModifier, changedAccessModifier, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void checkForEnumConstantAnnotationChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        EnumConstantDeclaration removedAttribute = (EnumConstantDeclaration) oldEntity.getDeclaration();
        EnumConstantDeclaration addedAttribute = (EnumConstantDeclaration) newEntity.getDeclaration();
        List<IExtendedModifier> modifiers1 = removedAttribute.modifiers();
        List<IExtendedModifier> modifiers2 = addedAttribute.modifiers();
        AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
        for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
            AddAttributeAnnotationRefactoring refactoring = new AddAttributeAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
            RemoveAttributeAnnotationRefactoring refactoring = new RemoveAttributeAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
            ModifyAttributeAnnotationRefactoring refactoring = new ModifyAttributeAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void checkForClassAnnotationChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        AbstractTypeDeclaration removedClass = (AbstractTypeDeclaration) oldEntity.getDeclaration();
        AbstractTypeDeclaration addedClass = (AbstractTypeDeclaration) newEntity.getDeclaration();
        List<IExtendedModifier> modifiers1 = removedClass.modifiers();
        List<IExtendedModifier> modifiers2 = addedClass.modifiers();
        AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
        for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
            AddClassAnnotationRefactoring refactoring = new AddClassAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
            RemoveClassAnnotationRefactoring refactoring = new RemoveClassAnnotationRefactoring(annotation, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
            ModifyClassAnnotationRefactoring refactoring = new ModifyClassAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void checkForClassModifierChanges(DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        AbstractTypeDeclaration removedClass = (AbstractTypeDeclaration) oldEntity.getDeclaration();
        AbstractTypeDeclaration addedClass = (AbstractTypeDeclaration) newEntity.getDeclaration();
        int methodModifiers1 = removedClass.getModifiers();
        int methodModifiers2 = addedClass.getModifiers();
        if (!Flags.isAbstract(methodModifiers1) && Flags.isAbstract(methodModifiers2)) {
            AddClassModifierRefactoring refactoring = new AddClassModifierRefactoring("abstract", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isAbstract(methodModifiers1) && !Flags.isAbstract(methodModifiers2)) {
            RemoveClassModifierRefactoring refactoring = new RemoveClassModifierRefactoring("abstract", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isFinal(methodModifiers1) && Flags.isFinal(methodModifiers2)) {
            AddClassModifierRefactoring refactoring = new AddClassModifierRefactoring("final", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isFinal(methodModifiers1) && !Flags.isFinal(methodModifiers2)) {
            RemoveClassModifierRefactoring refactoring = new RemoveClassModifierRefactoring("final", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!Flags.isStatic(methodModifiers1) && Flags.isStatic(methodModifiers2)) {
            AddClassModifierRefactoring refactoring = new AddClassModifierRefactoring("static", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isStatic(methodModifiers1) && !Flags.isStatic(methodModifiers2)) {
            RemoveClassModifierRefactoring refactoring = new RemoveClassModifierRefactoring("static", oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        Visibility originalAccessModifier = getVisibility(methodModifiers1, oldEntity);
        Visibility changedAccessModifier = getVisibility(methodModifiers2, newEntity);
        if (originalAccessModifier != changedAccessModifier) {
            ChangeClassAccessModifierRefactoring refactoring = new ChangeClassAccessModifierRefactoring(originalAccessModifier, changedAccessModifier, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void detectRefactoringsBetweenMatchedAndExtractedEntities(Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities,
                                                                      Set<DeclarationNodeTree> extractedEntities, Set<DeclarationNodeTree> addedEntities,
                                                                      List<Refactoring> refactorings) {
        for (DeclarationNodeTree extractedEntity : extractedEntities) {
            if (extractedEntity.getType() == EntityType.METHOD) {
                List<EntityInfo> dependencies = extractedEntity.getDependencies();
                for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
                    DeclarationNodeTree oldEntity = pair.getLeft();
                    DeclarationNodeTree newEntity = pair.getRight();
                    if (oldEntity.getType() == EntityType.METHOD && newEntity.getType() == EntityType.METHOD) {
                        if (!dependencies.contains(newEntity.getEntity()))
                            continue;
                        if (MethodUtils.isNewFunction(oldEntity.getDeclaration(), newEntity.getDeclaration()))
                            continue;
                        if (MethodUtils.isGetter((MethodDeclaration) extractedEntity.getDeclaration()) || MethodUtils.isSetter((MethodDeclaration) extractedEntity.getDeclaration()))
                            continue;
                        MethodNode methodNode = extractedEntity.getMethodNode();
                        int totalLOC = methodNode.getAllControls().size() + methodNode.getAllBlocks().size() + methodNode.getAllOperations().size() - 1;
                        int unmatchedLOC = methodNode.getUnmatchedNodes().size();
                        int matchedLOC = totalLOC - unmatchedLOC;
                        double dice = org.reextractorplus.util.DiceFunction.calculateBodyDice((LeafNode) extractedEntity, (LeafNode) newEntity, (LeafNode) oldEntity);
                        if (dice < 0.5 && matchedLOC <= unmatchedLOC)
                            continue;
                        boolean isMove = !oldEntity.getNamespace().equals(extractedEntity.getNamespace()) &&
                                !matchedEntities.contains(Pair.of(oldEntity.getParent(), extractedEntity.getParent()));
                        if (isMove) {
                            ExtractAndMoveOperationRefactoring refactoring = new ExtractAndMoveOperationRefactoring(oldEntity, newEntity, extractedEntity);
                            refactorings.add(refactoring);
                        } else {
                            ExtractOperationRefactoring refactoring = new ExtractOperationRefactoring(oldEntity, newEntity, extractedEntity);
                            refactorings.add(refactoring);
                        }
                    }
                }
            }
        }
        for (DeclarationNodeTree addedEntity : addedEntities) {
            if (addedEntity.getType() == EntityType.CLASS || addedEntity.getType() == EntityType.INTERFACE) {
                Set<DeclarationNodeTree> mapping = new HashSet<>();
                for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
                    DeclarationNodeTree oldEntity = pair.getLeft();
                    DeclarationNodeTree newEntity = pair.getRight();
                    if ((oldEntity.getType() == EntityType.METHOD && newEntity.getType() == EntityType.METHOD) ||
                            (oldEntity.getType() == EntityType.FIELD && newEntity.getType() == EntityType.FIELD)) {
                        if (newEntity.getParent() == addedEntity)
                            mapping.add(oldEntity.getParent());
                    }
                }
                for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
                    DeclarationNodeTree oldEntity = pair.getLeft();
                    DeclarationNodeTree newEntity = pair.getRight();
                    if (mapping.contains(oldEntity)) {
                        TypeDeclaration newClass = (TypeDeclaration) newEntity.getDeclaration();
                        TypeDeclaration addedClass = (TypeDeclaration) addedEntity.getDeclaration();
                        if (isSubTypeOf(newClass, addedClass)) {
                            if (addedEntity.getType() == EntityType.INTERFACE) {
                                ExtractInterfaceRefactoring refactoring = new ExtractInterfaceRefactoring(oldEntity, newEntity, addedEntity);
                                refactorings.add(refactoring);
                            } else {
                                ExtractSuperClassRefactoring refactoring = new ExtractSuperClassRefactoring(oldEntity, newEntity, addedEntity);
                                refactorings.add(refactoring);
                            }
                        } else if (isSubTypeOf(addedClass, newClass)) {
                            ExtractSubClassRefactoring refactoring = new ExtractSubClassRefactoring(oldEntity, newEntity, addedEntity);
                            refactorings.add(refactoring);
                        } else {
                            ExtractClassRefactoring refactoring = new ExtractClassRefactoring(oldEntity, newEntity, addedEntity);
                            refactorings.add(refactoring);
                        }
                    }
                    if (oldEntity.getType() == EntityType.CLASS && newEntity.getType() == EntityType.CLASS) {
                        TypeDeclaration newClass = (TypeDeclaration) newEntity.getDeclaration();
                        TypeDeclaration addedClass = (TypeDeclaration) addedEntity.getDeclaration();
                        if (isSubTypeOf(newClass, addedClass)) {
                            if (addedEntity.getType() == EntityType.INTERFACE) {
                                ExtractInterfaceRefactoring refactoring = new ExtractInterfaceRefactoring(oldEntity, newEntity, addedEntity);
                                refactorings.add(refactoring);
                            } else {
                                ExtractSuperClassRefactoring refactoring = new ExtractSuperClassRefactoring(oldEntity, newEntity, addedEntity);
                                refactorings.add(refactoring);
                            }
                        }
                    }
                }
            }
        }
    }

    private void detectRefactoringsBetweenMatchedAndInlinedEntities(Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities,
                                                                    Set<DeclarationNodeTree> inlinedEntities, List<Refactoring> refactorings) {
        for (DeclarationNodeTree inlinedEntity : inlinedEntities) {
            if (inlinedEntity.getType() == EntityType.METHOD) {
                List<EntityInfo> dependencies = inlinedEntity.getDependencies();
                for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
                    DeclarationNodeTree oldEntity = pair.getLeft();
                    DeclarationNodeTree newEntity = pair.getRight();
                    if (oldEntity.getType() == EntityType.METHOD && newEntity.getType() == EntityType.METHOD) {
                        if (!dependencies.contains(oldEntity.getEntity()))
                            continue;
                        if (MethodUtils.isNewFunction(newEntity.getDeclaration(), oldEntity.getDeclaration()))
                            continue;
                        if (MethodUtils.isGetter((MethodDeclaration) inlinedEntity.getDeclaration()) || MethodUtils.isSetter((MethodDeclaration) inlinedEntity.getDeclaration()))
                            continue;
                        MethodNode methodNode = inlinedEntity.getMethodNode();
                        int totalLOC = methodNode.getAllControls().size() + methodNode.getAllBlocks().size() + methodNode.getAllOperations().size() - 1;
                        int unmatchedLOC = methodNode.getUnmatchedNodes().size();
                        int matchedLOC = totalLOC - unmatchedLOC;
                        double dice = org.reextractorplus.util.DiceFunction.calculateBodyDice((LeafNode) inlinedEntity, (LeafNode) oldEntity, (LeafNode) newEntity);
                        if (dice < 0.5 && matchedLOC <= unmatchedLOC)
                            continue;
                        boolean isMove = !inlinedEntity.getNamespace().equals(newEntity.getNamespace()) &&
                                !matchedEntities.contains(Pair.of(inlinedEntity.getParent(), newEntity.getParent()));
                        if (isMove) {
                            MoveAndInlineOperationRefactoring refactoring = new MoveAndInlineOperationRefactoring(oldEntity, newEntity, inlinedEntity);
                            refactorings.add(refactoring);
                        } else {
                            InlineOperationRefactoring refactoring = new InlineOperationRefactoring(oldEntity, newEntity, inlinedEntity);
                            refactorings.add(refactoring);
                        }
                    }
                }
            }
        }
    }

    private DeclarationNodeTree findMatchedEntity(Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities, DeclarationNodeTree entity) {
        for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
            if (pair.getLeft() == entity)
                return pair.getRight();
            if (pair.getRight() == entity)
                return pair.getLeft();
        }
        return null;
    }

    private boolean isSubTypeOf(Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities, DeclarationNodeTree oldEntity, DeclarationNodeTree newEntity) {
        TypeDeclaration removedClass = (TypeDeclaration) oldEntity.getParent().getDeclaration();
        DeclarationNodeTree matchedAddedEntity = findMatchedEntity(matchedEntities, newEntity.getParent());
        TypeDeclaration matchedAddedClass = matchedAddedEntity == null ? null : (TypeDeclaration) (matchedAddedEntity.getDeclaration());
        TypeDeclaration addedClass = (TypeDeclaration) newEntity.getParent().getDeclaration();
        DeclarationNodeTree matchedDeletedEntity = findMatchedEntity(matchedEntities, oldEntity.getParent());
        TypeDeclaration matchedDeletedClass = matchedDeletedEntity == null ? null : (TypeDeclaration) (matchedDeletedEntity.getDeclaration());
        return (matchedAddedClass != null && isSubTypeOf(removedClass, matchedAddedClass))
                || (matchedDeletedClass != null && isSubTypeOf(matchedDeletedClass, addedClass));
    }

    private boolean isSubTypeOf(TypeDeclaration removedClass, TypeDeclaration addedClass) {
        ITypeBinding removedBinding = removedClass.resolveBinding();
        ITypeBinding addedBinding = addedClass.resolveBinding();
        boolean superClass;
        boolean isInterface;
        if (removedBinding != null) {
            if (removedBinding.getSuperclass() != null) {
                if (addedBinding != null) {
                    superClass = removedBinding.getSuperclass().getTypeDeclaration().isEqualTo(addedBinding) ||
                            StringUtils.equals(removedBinding.getSuperclass().getQualifiedName(), addedBinding.getQualifiedName());
                    if (superClass)
                        return true;
                }
            }
            ITypeBinding[] interfaces = removedBinding.getInterfaces();
            for (ITypeBinding typeBinding : interfaces) {
                if (addedBinding != null) {
                    isInterface = typeBinding.getTypeDeclaration().isEqualTo(addedBinding) ||
                            StringUtils.equals(typeBinding.getQualifiedName(), addedBinding.getQualifiedName());
                    if (isInterface)
                        return true;
                }
            }
        }
        return false;
    }

    private void detectRefactoringsInMatchedStatements(Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements,
                                                       List<Refactoring> refactorings) {
        for (Pair<StatementNodeTree, StatementNodeTree> pair : matchedStatements) {
            StatementNodeTree oldStatement = pair.getLeft();
            StatementNodeTree newStatement = pair.getRight();
            DeclarationNodeTree oldEntity = oldStatement.getRoot().getMethodEntity();
            DeclarationNodeTree newEntity = newStatement.getRoot().getMethodEntity();
            if (oldStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT && newStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                processVariableDeclarationStatement(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT && newStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT) {
                processEnhancedForStatement(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement.getType() == StatementType.FOR_STATEMENT && newStatement.getType() == StatementType.FOR_STATEMENT) {
                processForStatement(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement.getBlockType() == BlockType.CATCH && newStatement.getBlockType() == BlockType.CATCH) {
                processCatchClause(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement.getType() == StatementType.TRY_STATEMENT && newStatement.getType() == StatementType.TRY_STATEMENT) {
                processTryStatement(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if ((oldStatement.getType() == StatementType.FOR_STATEMENT || oldStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                    oldStatement.getType() == StatementType.WHILE_STATEMENT || oldStatement.getType() == StatementType.DO_STATEMENT) &&
                    (newStatement.getType() == StatementType.FOR_STATEMENT || newStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                            newStatement.getType() == StatementType.WHILE_STATEMENT || newStatement.getType() == StatementType.DO_STATEMENT)) {
                processLoopStatement(oldStatement, newStatement, oldEntity, newEntity, matchedStatements, refactorings);
            }
            if ((oldStatement.getType() == StatementType.EXPRESSION_STATEMENT || oldStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) &&
                    (newStatement.getType() == StatementType.EXPRESSION_STATEMENT || newStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) ||
                    (oldStatement.getType() == StatementType.RETURN_STATEMENT && newStatement.getType() == StatementType.RETURN_STATEMENT)) {
                processAnonymousWithLambda(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if ((oldStatement.getType() == StatementType.FOR_STATEMENT || oldStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                    oldStatement.getType() == StatementType.WHILE_STATEMENT || oldStatement.getType() == StatementType.DO_STATEMENT) &&
                    (newStatement.getType() == StatementType.EXPRESSION_STATEMENT || newStatement.getType() == StatementType.RETURN_STATEMENT)) {
                processLoopWithPipeline(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if ((oldStatement.getType() == StatementType.EXPRESSION_STATEMENT || oldStatement.getType() == StatementType.RETURN_STATEMENT) &&
                    (newStatement.getType() == StatementType.FOR_STATEMENT || newStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                            newStatement.getType() == StatementType.WHILE_STATEMENT || newStatement.getType() == StatementType.DO_STATEMENT)) {
                processPipelineWithLoop(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement.getType() == StatementType.IF_STATEMENT && newStatement.getType() == StatementType.IF_STATEMENT) {
                processInvertCondition(oldStatement, newStatement, oldEntity, newEntity, matchedStatements, refactorings);
            }
            if (oldStatement.getType() == StatementType.SWITCH_STATEMENT && newStatement.getType() == StatementType.IF_STATEMENT) {
                processSwitchWithIf(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT && newStatement.getType() != StatementType.VARIABLE_DECLARATION_STATEMENT) {
                processInlineVariable(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement.getType() != StatementType.VARIABLE_DECLARATION_STATEMENT && newStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                processExtractVariable(oldStatement, newStatement, oldEntity, newEntity, refactorings);
            }
            if (oldStatement instanceof OperationNode && newStatement instanceof OperationNode) {
                List<LambdaExpression> oldLambdaExpressions = new ArrayList<>();
                List<LambdaExpression> newLambdaExpressions = new ArrayList<>();
                oldStatement.getStatement().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(LambdaExpression node) {
                        oldLambdaExpressions.add(node);
                        return true;
                    }
                });
                newStatement.getStatement().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(LambdaExpression node) {
                        newLambdaExpressions.add(node);
                        return true;
                    }
                });
                if (oldLambdaExpressions.size() == 1 && newLambdaExpressions.size() == 1) {
                    LambdaExpression oldLambdaExpression = oldLambdaExpressions.get(0);
                    LambdaExpression newLambdaExpression = newLambdaExpressions.get(0);
                    List<VariableDeclaration> oldParameters = oldLambdaExpression.parameters();
                    List<VariableDeclaration> newParameters = newLambdaExpression.parameters();
                    if (oldParameters.size() == 1 && newParameters.size() == 1) {
                        VariableDeclaration oldFragment = oldParameters.get(0);
                        VariableDeclaration newFragment = newParameters.get(0);
                        if (!oldFragment.getName().getIdentifier().equals(newFragment.getName().getIdentifier())) {
                            RenameVariableRefactoring refactoring = new RenameVariableRefactoring(oldFragment, newFragment, oldEntity, newEntity);
                            refactorings.add(refactoring);
                        }
                    }
                }
                List<MethodDeclaration> oldMethods = new ArrayList<>();
                List<MethodDeclaration> newMethods = new ArrayList<>();
                oldStatement.getStatement().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodDeclaration node) {
                        oldMethods.add(node);
                        return true;
                    }
                });
                newStatement.getStatement().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodDeclaration node) {
                        newMethods.add(node);
                        return true;
                    }
                });
                for (MethodDeclaration oldMethod : oldMethods) {
                    for (MethodDeclaration newMethod : newMethods) {
                        if (oldMethod.getName().getIdentifier().equals(newMethod.getName().getIdentifier()) &&
                                oldMethod.getReturnType2() != null && newMethod.getReturnType2() != null &&
                                oldMethod.getReturnType2().toString().equals(newMethod.getReturnType2().toString()) &&
                                oldMethod.parameters().size() == newMethod.parameters().size() && equalsParameters(oldMethod, newMethod)) {
                            DeclarationNodeTree leftEntity = new LeafNode((CompilationUnit) oldMethod.getRoot(), oldStatement.getLocation().getFilePath(), oldMethod);
                            DeclarationNodeTree rightEntity = new LeafNode((CompilationUnit) newMethod.getRoot(), newStatement.getLocation().getFilePath(), newMethod);
                            leftEntity.setDeclaration(oldMethod);
                            leftEntity.setType(EntityType.METHOD);
                            leftEntity.setParent(new InternalNode((CompilationUnit) oldMethod.getRoot(), oldStatement.getLocation().getFilePath(), oldMethod));
                            leftEntity.getParent().setType(EntityType.CLASS);
                            if (oldMethod.getParent() instanceof AnonymousClassDeclaration && oldMethod.getParent().getParent() instanceof ClassInstanceCreation)
                                leftEntity.setNamespace(oldStatement.getRoot().getMethodEntity().getNamespace() + "." + oldStatement.getRoot().getMethodEntity().getName() + ".new " +
                                        ((ClassInstanceCreation) oldMethod.getParent().getParent()).getType().toString());
                            rightEntity.setDeclaration(newMethod);
                            rightEntity.setType(EntityType.METHOD);
                            rightEntity.setParent(new InternalNode((CompilationUnit) oldMethod.getRoot(), oldStatement.getLocation().getFilePath(), oldMethod));
                            rightEntity.getParent().setType(EntityType.CLASS);
                            if (newMethod.getParent() instanceof AnonymousClassDeclaration && newMethod.getParent().getParent() instanceof ClassInstanceCreation)
                                rightEntity.setNamespace(newStatement.getRoot().getMethodEntity().getNamespace() + "." + newStatement.getRoot().getMethodEntity().getName() + ".new " +
                                        ((ClassInstanceCreation) newMethod.getParent().getParent()).getType().toString());
                            checkForOperationAnnotationChanges(leftEntity, rightEntity, refactorings);
                            checkForOperationModifierChanges(leftEntity, rightEntity, refactorings);
                            checkForThrownExceptionTypeChanges(leftEntity, rightEntity, refactorings);
                        }
                    }
                }
            }
        }
    }

    private boolean equalsParameters(MethodDeclaration oldMethod, MethodDeclaration newMethod) {
        List<SingleVariableDeclaration> oldParameters = oldMethod.parameters();
        List<SingleVariableDeclaration> newParameters = newMethod.parameters();
        for (int i = 0; i < oldParameters.size(); i++) {
            SingleVariableDeclaration oldParameter = oldParameters.get(i);
            SingleVariableDeclaration newParameter = newParameters.get(i);
            if (!oldParameter.getName().getIdentifier().equals(newParameter.getName().getIdentifier()) ||
                    !oldParameter.getType().toString().equals(newParameter.getType().toString()))
                return false;
        }
        return true;
    }

    private void processVariableDeclarationStatement(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                                     DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        VariableDeclarationStatement statement1 = (VariableDeclarationStatement) oldStatement.getStatement();
        VariableDeclarationStatement statement2 = (VariableDeclarationStatement) newStatement.getStatement();
        List<VariableDeclarationFragment> fragments1 = statement1.fragments();
        List<VariableDeclarationFragment> fragments2 = statement2.fragments();
        Type type1 = statement1.getType();
        Type type2 = statement2.getType();
        for (int i = 0; i < Math.min(fragments1.size(), fragments2.size()); i++) {
            VariableDeclarationFragment fragment1 = fragments1.get(i);
            VariableDeclarationFragment fragment2 = fragments2.get(i);
            if (!fragment1.getName().getIdentifier().equals(fragment2.getName().getIdentifier())) {
                RenameVariableRefactoring refactoring = new RenameVariableRefactoring(fragment1, fragment2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            if (!type1.toString().equals(type2.toString())) {
                ChangeVariableTypeRefactoring refactoring = new ChangeVariableTypeRefactoring(fragment1, fragment2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            List<IExtendedModifier> modifiers1 = statement1.modifiers();
            List<IExtendedModifier> modifiers2 = statement2.modifiers();
            AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
            for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
                AddVariableAnnotationRefactoring refactoring = new AddVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
                RemoveVariableAnnotationRefactoring refactoring = new RemoveVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
                ModifyVariableAnnotationRefactoring refactoring = new ModifyVariableAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), fragment1, fragment2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            int variableModifiers1 = statement1.getModifiers();
            int variableModifiers2 = statement2.getModifiers();
            if (!Flags.isFinal(variableModifiers1) && Flags.isFinal(variableModifiers2)) {
                AddVariableModifierRefactoring refactoring = new AddVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
            if (Flags.isFinal(variableModifiers1) && !Flags.isFinal(variableModifiers2)) {
                RemoveVariableModifierRefactoring refactoring = new RemoveVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
                refactorings.add(refactoring);
            }
        }
    }

    private void processEnhancedForStatement(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                             DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        EnhancedForStatement statement1 = (EnhancedForStatement) oldStatement.getStatement();
        EnhancedForStatement statement2 = (EnhancedForStatement) newStatement.getStatement();
        SingleVariableDeclaration fragment1 = statement1.getParameter();
        SingleVariableDeclaration fragment2 = statement2.getParameter();
        Type type1 = fragment1.getType();
        Type type2 = fragment2.getType();
        if (!fragment1.getName().getIdentifier().equals(fragment2.getName().getIdentifier())) {
            RenameVariableRefactoring refactoring = new RenameVariableRefactoring(fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!type1.toString().equals(type2.toString())) {
            ChangeVariableTypeRefactoring refactoring = new ChangeVariableTypeRefactoring(fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        List<IExtendedModifier> modifiers1 = fragment1.modifiers();
        List<IExtendedModifier> modifiers2 = fragment2.modifiers();
        AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
        for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
            AddVariableAnnotationRefactoring refactoring = new AddVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
            RemoveVariableAnnotationRefactoring refactoring = new RemoveVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
            ModifyVariableAnnotationRefactoring refactoring = new ModifyVariableAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        int variableModifiers1 = fragment1.getModifiers();
        int variableModifiers2 = fragment2.getModifiers();
        if (!Flags.isFinal(variableModifiers1) && Flags.isFinal(variableModifiers2)) {
            AddVariableModifierRefactoring refactoring = new AddVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isFinal(variableModifiers1) && !Flags.isFinal(variableModifiers2)) {
            RemoveVariableModifierRefactoring refactoring = new RemoveVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void processForStatement(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                     DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        ForStatement statement1 = (ForStatement) oldStatement.getStatement();
        ForStatement statement2 = (ForStatement) newStatement.getStatement();
        List initializers1 = statement1.initializers();
        List initializers2 = statement2.initializers();
        if (initializers1.size() == 1 && initializers2.size() == 1) {
            Object obj1 = initializers1.get(0);
            Object obj2 = initializers2.get(0);
            if (obj1 instanceof VariableDeclarationExpression && obj2 instanceof VariableDeclarationExpression) {
                VariableDeclarationExpression initializer1 = (VariableDeclarationExpression) obj1;
                VariableDeclarationExpression initializer2 = (VariableDeclarationExpression) obj2;
                Type type1 = initializer1.getType();
                Type type2 = initializer2.getType();
                List<VariableDeclarationFragment> fragments1 = initializer1.fragments();
                List<VariableDeclarationFragment> fragments2 = initializer2.fragments();
                VariableDeclarationFragment fragment1 = fragments1.get(0);
                VariableDeclarationFragment fragment2 = fragments2.get(0);
                if (type1 != null && type2 != null && !type1.toString().equals(type2.toString())) {
                    ChangeVariableTypeRefactoring refactoring = new ChangeVariableTypeRefactoring(fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                if (!fragment1.getName().getIdentifier().equals(fragment2.getName().getIdentifier())) {
                    RenameVariableRefactoring refactoring = new RenameVariableRefactoring(fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                if (!type1.toString().equals(type2.toString())) {
                    ChangeVariableTypeRefactoring refactoring = new ChangeVariableTypeRefactoring(fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                List<IExtendedModifier> modifiers1 = initializer1.modifiers();
                List<IExtendedModifier> modifiers2 = initializer2.modifiers();
                AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
                for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
                    AddVariableAnnotationRefactoring refactoring = new AddVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
                    RemoveVariableAnnotationRefactoring refactoring = new RemoveVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
                    ModifyVariableAnnotationRefactoring refactoring = new ModifyVariableAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                int variableModifiers1 = initializer1.getModifiers();
                int variableModifiers2 = initializer2.getModifiers();
                if (!Flags.isFinal(variableModifiers1) && Flags.isFinal(variableModifiers2)) {
                    AddVariableModifierRefactoring refactoring = new AddVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                if (Flags.isFinal(variableModifiers1) && !Flags.isFinal(variableModifiers2)) {
                    RemoveVariableModifierRefactoring refactoring = new RemoveVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
            }
        }
    }

    private void processCatchClause(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                    DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        CatchClause statement1 = (CatchClause) oldStatement.getStatement().getParent();
        CatchClause statement2 = (CatchClause) newStatement.getStatement().getParent();
        SingleVariableDeclaration fragment1 = statement1.getException();
        SingleVariableDeclaration fragment2 = statement2.getException();
        Type type1 = fragment1.getType();
        Type type2 = fragment2.getType();
        if (!fragment1.getName().getIdentifier().equals(fragment2.getName().getIdentifier())) {
            RenameVariableRefactoring refactoring = new RenameVariableRefactoring(fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (!type1.toString().equals(type2.toString())) {
            ChangeVariableTypeRefactoring refactoring = new ChangeVariableTypeRefactoring(fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        List<IExtendedModifier> modifiers1 = fragment1.modifiers();
        List<IExtendedModifier> modifiers2 = fragment2.modifiers();
        AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
        for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
            AddVariableAnnotationRefactoring refactoring = new AddVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
            RemoveVariableAnnotationRefactoring refactoring = new RemoveVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
            ModifyVariableAnnotationRefactoring refactoring = new ModifyVariableAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        int variableModifiers1 = fragment1.getModifiers();
        int variableModifiers2 = fragment2.getModifiers();
        if (!Flags.isFinal(variableModifiers1) && Flags.isFinal(variableModifiers2)) {
            AddVariableModifierRefactoring refactoring = new AddVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        if (Flags.isFinal(variableModifiers1) && !Flags.isFinal(variableModifiers2)) {
            RemoveVariableModifierRefactoring refactoring = new RemoveVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void processTryStatement(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                     DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        TryStatement statement1 = (TryStatement) oldStatement.getStatement();
        TryStatement statement2 = (TryStatement) newStatement.getStatement();
        List<VariableDeclarationExpression> resources1 = statement1.resources();
        List<VariableDeclarationExpression> resources2 = statement2.resources();
        for (int i = 0; i < Math.min(resources1.size(), resources2.size()); i++) {
            VariableDeclarationExpression expression1 = resources1.get(i);
            VariableDeclarationExpression expression2 = resources2.get(i);
            List<VariableDeclarationFragment> fragments1 = expression1.fragments();
            List<VariableDeclarationFragment> fragments2 = expression2.fragments();
            Type type1 = expression1.getType();
            Type type2 = expression2.getType();
            for (int j = 0; j < Math.min(fragments1.size(), fragments2.size()); j++) {
                VariableDeclarationFragment fragment1 = fragments1.get(j);
                VariableDeclarationFragment fragment2 = fragments2.get(j);
                if (!fragment1.getName().getIdentifier().equals(fragment2.getName().getIdentifier())) {
                    RenameVariableRefactoring refactoring = new RenameVariableRefactoring(fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                if (!type1.toString().equals(type2.toString())) {
                    ChangeVariableTypeRefactoring refactoring = new ChangeVariableTypeRefactoring(fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                List<IExtendedModifier> modifiers1 = expression1.modifiers();
                List<IExtendedModifier> modifiers2 = expression2.modifiers();
                AnnotationListDiff annotationListDiff = new AnnotationListDiff(modifiers1, modifiers2);
                for (Annotation annotation : annotationListDiff.getAddedAnnotations()) {
                    AddVariableAnnotationRefactoring refactoring = new AddVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                for (Annotation annotation : annotationListDiff.getRemovedAnnotations()) {
                    RemoveVariableAnnotationRefactoring refactoring = new RemoveVariableAnnotationRefactoring(annotation, fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                for (Pair<Annotation, Annotation> annotationDiff : annotationListDiff.getAnnotationDiffs()) {
                    ModifyVariableAnnotationRefactoring refactoring = new ModifyVariableAnnotationRefactoring(annotationDiff.getLeft(), annotationDiff.getRight(), fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                int variableModifiers1 = expression1.getModifiers();
                int variableModifiers2 = expression2.getModifiers();
                if (!Flags.isFinal(variableModifiers1) && Flags.isFinal(variableModifiers2)) {
                    AddVariableModifierRefactoring refactoring = new AddVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
                if (Flags.isFinal(variableModifiers1) && !Flags.isFinal(variableModifiers2)) {
                    RemoveVariableModifierRefactoring refactoring = new RemoveVariableModifierRefactoring("final", fragment1, fragment2, oldEntity, newEntity);
                    refactorings.add(refactoring);
                }
            }
        }
    }

    private void processLoopStatement(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                      DeclarationNodeTree newEntity, Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements,
                                      List<Refactoring> refactorings) {
        if (oldStatement.getType() != newStatement.getType()) {
            ChangeLoopTypeRefactoring refactoring = new ChangeLoopTypeRefactoring(oldStatement.getType().getName(), newStatement.getType().getName(), oldEntity, newEntity);
            refactorings.add(refactoring);
        }
        StatementNodeTree parent = newStatement.getParent();
        while (parent.getDepth() > 0 &&
                !(parent.getType() == StatementType.FOR_STATEMENT || parent.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                        parent.getType() == StatementType.WHILE_STATEMENT || parent.getType() == StatementType.DO_STATEMENT)) {
            parent = parent.getParent();
        }
        StatementNodeTree child = retrieveSNTByBFS(oldStatement);
        if (child != null && matchedStatements.contains(Pair.of(child, parent))) {
            LoopInterchangeRefactoring refactoring1 = new LoopInterchangeRefactoring(oldStatement.getExpression(), newStatement.getExpression(), oldEntity, newEntity);
            refactorings.add(refactoring1);
        }
    }

    private void processAnonymousWithLambda(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                            DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        ASTNode statement1 = oldStatement.getStatement();
        ASTNode statement2 = newStatement.getStatement();
        List<AnonymousClassDeclaration> anonymous1 = new ArrayList<>();
        List<LambdaExpression> lambda1 = new ArrayList<>();
        List<AnonymousClassDeclaration> anonymous2 = new ArrayList<>();
        List<LambdaExpression> lambda2 = new ArrayList<>();
        statement1.accept(new ASTVisitor() {
            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                anonymous1.add(node);
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                lambda1.add(node);
                return true;
            }
        });
        statement2.accept(new ASTVisitor() {
            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                anonymous2.add(node);
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                lambda2.add(node);
                return true;
            }
        });
        if (anonymous1.size() == 1 && lambda1.size() == 0 && lambda2.size() == 1 && anonymous2.size() == 0) {
            ReplaceAnonymousWithLambdaRefactoring refactoring = new ReplaceAnonymousWithLambdaRefactoring(anonymous1.get(0), lambda2.get(0), oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void processLoopWithPipeline(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                         DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        ASTNode statement = newStatement.getStatement();
        if (MethodUtils.isStreamAPI(statement)) {
            String loop = oldStatement.getExpression();
            String pipeline = newStatement.getExpression();
            ReplaceLoopWithPipelineRefactoring refactoring = new ReplaceLoopWithPipelineRefactoring(loop, pipeline, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void processPipelineWithLoop(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                         DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        ASTNode statement = oldStatement.getStatement();
        if (MethodUtils.isStreamAPI(statement)) {
            String pipeline = oldStatement.getExpression();
            String loop = newStatement.getExpression();
            ReplacePipelineWithLoopRefactoring refactoring = new ReplacePipelineWithLoopRefactoring(pipeline, loop, oldEntity, newEntity);
            refactorings.add(refactoring);
        }
    }

    private void processInvertCondition(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                        DeclarationNodeTree newEntity, Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements,
                                        List<Refactoring> refactorings) {
        if (!oldStatement.getExpression().equals(newStatement.getExpression()))
            return;
        String expression1 = oldStatement.getExpression();
        String expression2 = newStatement.getExpression();
        boolean invertedFlag = false;
        if (!expression1.equals(expression2) && (expression1.replace("==", "!=").equals(expression2) || expression1.replace("!=", "==").equals(expression2))) {
            InvertConditionRefactoring refactoring = new InvertConditionRefactoring(expression1, expression2, oldEntity, newEntity);
            refactorings.add(refactoring);
            invertedFlag = true;
        }
        if (!expression1.equals(expression2) && (invertedFlag && expression1.replace("if(", "if(!").equals(expression2) || expression1.replace("if(!", "if(").equals(expression2))) {
            InvertConditionRefactoring refactoring = new InvertConditionRefactoring(expression1, expression2, oldEntity, newEntity);
            refactorings.add(refactoring);
            invertedFlag = true;
        }
        if (!expression1.equals(expression2) && (invertedFlag && expression1.replace("if(", "if(!").equals(expression2.replace("if(!(", "if（!")) ||
                expression1.replace("if(!", "if(").equals(expression2.replace("))", ")")))) {
            InvertConditionRefactoring refactoring = new InvertConditionRefactoring(expression1, expression2, oldEntity, newEntity);
            refactorings.add(refactoring);
            invertedFlag = true;
        }
        List<StatementNodeTree> children1 = oldStatement.getChildren();
        List<StatementNodeTree> children2 = newStatement.getChildren();
        if (!invertedFlag) {
            for (StatementNodeTree child1 : children1) {
                if (invertedFlag)
                    break;
                for (StatementNodeTree child2 : children2) {
                    if (matchedStatements.contains(Pair.of(child1, child2)) && !Objects.equals(child1.getBlockExpression(), child2.getBlockExpression()) &&
                            ((child1.getBlockType() == BlockType.IF && child2.getBlockType() == BlockType.ELSE) ||
                                    (child1.getBlockType() == BlockType.ELSE && child2.getBlockType() == BlockType.IF))) {
                        InvertConditionRefactoring refactoring = new InvertConditionRefactoring(expression1, expression2, oldEntity, newEntity);
                        refactorings.add(refactoring);
                        invertedFlag = true;
                        break;
                    }
                }
            }
        }
    }

    private void processSwitchWithIf(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                     DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        ReplaceSwitchWithIfRefactoring refactoring = new ReplaceSwitchWithIfRefactoring(oldStatement.getExpression(), newStatement.getExpression(), oldEntity, newEntity);
        refactorings.add(refactoring);
    }

    private StatementNodeTree retrieveSNTByBFS(StatementNodeTree current) {
        Queue<StatementNodeTree> queue = new LinkedList<>(current.getChildren());
        Set<StatementNodeTree> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            StatementNodeTree node = queue.poll();
            if (node.getType() == StatementType.FOR_STATEMENT || node.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                    node.getType() == StatementType.WHILE_STATEMENT || node.getType() == StatementType.DO_STATEMENT)
                return node;
            for (StatementNodeTree child : node.getChildren()) {
                if (!visited.contains(child)) {
                    queue.add(child);
                    visited.add(child);
                }
            }
        }
        return null;
    }

    private boolean isCommonStr(String str1, String str2) {
        int minLength = Math.min(str1.length(), str2.length());
        int commonLength = 0;

        for (int i = 0; i < minLength; i++) {
            if (str1.charAt(i) == str2.charAt(i)) {
                commonLength++;
            } else {
                break;
            }
        }
        return commonLength > 0;
    }

    private void processInlineVariable(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                       DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        VariableDeclarationStatement statement = (VariableDeclarationStatement) oldStatement.getStatement();
        List<VariableDeclarationFragment> fragments = statement.fragments();
        for (int i = 0; i < fragments.size(); i++) {
            VariableDeclarationFragment fragment = fragments.get(i);
            Expression initializer = fragment.getInitializer();
            if (newStatement.getExpression().contains(initializer.toString())) {
                boolean exist = false;
                List<StatementNodeTree> allOperations = oldEntity.getMethodNode().getAllOperations();
                List<StatementNodeTree> allControls = oldEntity.getMethodNode().getAllControls();
                for (StatementNodeTree operation : allOperations) {
                    if (operation.isMatched())
                        continue;
                    if (operation.getType() == newStatement.getType()) {
                        boolean sameExp = isSameExp(operation, newStatement);
                        if (sameExp && isCommonStr(operation.getExpression(), newStatement.getExpression())) {
                            exist = true;
                            break;
                        }
                    }
                }
                for (StatementNodeTree control : allControls) {
                    if (control.isMatched())
                        continue;
                    boolean sameExp = isSameExp(control, newStatement);
                    if (control.getType() == newStatement.getType()) {
                        if (sameExp && isCommonStr(control.getExpression(), newStatement.getExpression())) {
                            exist = true;
                            break;
                        }
                    }
                }
                if (exist) {
                    boolean identical = false;
                    for (StatementNodeTree operation : allOperations) {
                        if (operation.getExpression().equals(oldStatement.getExpression())) {
                            identical = true;
                            break;
                        }
                    }
                    if (!identical) {
                        InlineVariableRefactoring refactoring = new InlineVariableRefactoring(fragment, oldEntity, newEntity);
                        refactorings.add(refactoring);
                    }
                }
            }
        }
    }

    private boolean isSameExp(StatementNodeTree statement1, StatementNodeTree statement2) {
        if (statement1.getType() == StatementType.EXPRESSION_STATEMENT && statement2.getType() == StatementType.EXPRESSION_STATEMENT) {
            ExpressionStatement exp1 = (ExpressionStatement) statement1.getStatement();
            ExpressionStatement exp2 = (ExpressionStatement) statement2.getStatement();
            if (exp1.getExpression().getClass() == exp2.getExpression().getClass())
                return true;
            else return false;
        } else
            return true;
    }

    private void processExtractVariable(StatementNodeTree oldStatement, StatementNodeTree newStatement, DeclarationNodeTree oldEntity,
                                        DeclarationNodeTree newEntity, List<Refactoring> refactorings) {
        VariableDeclarationStatement statement = (VariableDeclarationStatement) newStatement.getStatement();
        List<VariableDeclarationFragment> fragments = statement.fragments();
        for (int i = 0; i < fragments.size(); i++) {
            VariableDeclarationFragment fragment = fragments.get(i);
            Expression initializer = fragment.getInitializer();
            if (oldStatement.getExpression().contains(initializer.toString())) {
                boolean exist = false;
                List<StatementNodeTree> allOperations = newEntity.getMethodNode().getAllOperations();
                List<StatementNodeTree> allControls = newEntity.getMethodNode().getAllControls();
                for (StatementNodeTree operation : allOperations) {
                    if (operation.isMatched())
                        continue;
                    boolean sameExp = isSameExp(operation, oldStatement);
                    if (operation.getType() == oldStatement.getType()) {
                        if (sameExp && isCommonStr(operation.getExpression(), oldStatement.getExpression())) {
                            exist = true;
                            break;
                        }
                    }
                }
                for (StatementNodeTree control : allControls) {
                    if (control.isMatched())
                        continue;
                    boolean sameExp = isSameExp(control, oldStatement);
                    if (control.getType() == oldStatement.getType()) {
                        if (sameExp && isCommonStr(control.getExpression(), oldStatement.getExpression())) {
                            exist = true;
                            break;
                        }
                    }
                }
                if (exist) {
                    boolean identical = false;
                    for (StatementNodeTree operation : allOperations) {
                        if (operation.getExpression().equals(newStatement.getExpression())) {
                            identical = true;
                            break;
                        }
                    }
                    if (!identical) {
                        ExtractVariableRefactoring refactoring = new ExtractVariableRefactoring(fragment, oldEntity, newEntity);
                        refactorings.add(refactoring);
                    }
                }
            }
        }
    }

    private void detectRefactoringsBetweenMatchedAndAddedStatements(Set<Pair<MethodNode, MethodNode>> methodNodePairs,
                                                                    Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements,
                                                                    Set<StatementNodeTree> addedStatements, List<Refactoring> refactorings) {
        for (StatementNodeTree addedStatement : addedStatements) {
            if (addedStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                boolean extractedFlag = false;
                for (Pair<StatementNodeTree, StatementNodeTree> pair : matchedStatements) {
                    StatementNodeTree oldStatement = pair.getLeft();
                    StatementNodeTree newStatement = pair.getRight();
                    if (!methodNodePairs.contains(Pair.of(oldStatement.getRoot(), addedStatement.getRoot())))
                        continue;
                    DeclarationNodeTree oldEntity = oldStatement.getRoot().getMethodEntity();
                    DeclarationNodeTree newEntity = newStatement.getRoot().getMethodEntity();
                    String expression1 = oldStatement.getExpression();
                    String expression2 = newStatement.getExpression();
                    List<VariableDeclarationFragment> fragments = ((VariableDeclarationStatement) addedStatement.getStatement()).fragments();
                    if (!extractedFlag) {
                        for (VariableDeclarationFragment fragment : fragments) {
                            if (expression2.contains(fragment.getName().getIdentifier()) && fragment.getInitializer() != null &&
                                    expression1.contains(fragment.getInitializer().toString()) &&
                                    !expression1.equals(expression2)) {
                                if (oldStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT && newStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                                    VariableDeclarationFragment fragment1 = (VariableDeclarationFragment) ((VariableDeclarationStatement) oldStatement.getStatement()).fragments().get(0);
                                    VariableDeclarationFragment fragment2 = (VariableDeclarationFragment) ((VariableDeclarationStatement) newStatement.getStatement()).fragments().get(0);
                                    if (fragment1.getInitializer().toString().equals(fragment2.getInitializer().toString()))
                                        continue;
                                }
                                if (oldStatement.hasChildren() && newStatement.hasChildren())
                                    continue;
                                ExtractVariableRefactoring refactoring = new ExtractVariableRefactoring(fragment, oldEntity, newEntity);
                                refactorings.add(refactoring);
                                extractedFlag = true;
                            }
                        }
                    }
                }
            } else if (addedStatement.getType() == StatementType.IF_STATEMENT) {
                for (Pair<StatementNodeTree, StatementNodeTree> pair : matchedStatements) {
                    StatementNodeTree oldStatement = pair.getLeft();
                    StatementNodeTree newStatement = pair.getRight();
                    if (oldStatement.getType() == StatementType.IF_STATEMENT && newStatement.getType() == StatementType.IF_STATEMENT) {
                        String expression1 = oldStatement.getExpression();
                        String expression2 = newStatement.getExpression();
                        String addedExpression = addedStatement.getExpression();
                        if ((expression1.contains(addedExpression.replace("if(", "")) ||
                                expression1.contains(addedExpression.replace("if(", "").replace(")", ""))) &&
                                !(expression2.contains(addedExpression.replace("if(", ""))
                                        || expression2.contains(addedExpression.replace("if(", "").replace(")", ""))
                                        || expression2.contains(addedExpression.replace(")", "")))) {
                            Set<String> splitConditionals = new LinkedHashSet<>();
                            splitConditionals.add(expression2);
                            splitConditionals.add(addedExpression);
                            DeclarationNodeTree oldEntity = oldStatement.getRoot().getMethodEntity();
                            DeclarationNodeTree newEntity = newStatement.getRoot().getMethodEntity();
                            SplitConditionalRefactoring refactoring = new SplitConditionalRefactoring(expression1, splitConditionals, oldEntity, newEntity);
                            refactorings.add(refactoring);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void detectRefactoringsBetweenMatchedAndDeletedStatements(Set<Pair<MethodNode, MethodNode>> methodNodePairs,
                                                                      Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements,
                                                                      Set<StatementNodeTree> deletedStatements, List<Refactoring> refactorings) {
        for (StatementNodeTree deletedStatement : deletedStatements) {
            if (deletedStatement.getType() == StatementType.EXPRESSION_STATEMENT) {
                processExpressionStatement(methodNodePairs, deletedStatement, matchedStatements, refactorings);
            }
            if (deletedStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                processVariableDeclarationStatement(methodNodePairs, deletedStatement, matchedStatements, refactorings);
            }
            if (deletedStatement.getType() == StatementType.IF_STATEMENT) {
                processIfStatement(methodNodePairs, deletedStatement, matchedStatements, refactorings);
            }
        }
    }

    private void processExpressionStatement(Set<Pair<MethodNode, MethodNode>> methodNodePairs,
                                            StatementNodeTree deletedStatement, Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements,
                                            List<Refactoring> refactorings) {
        Map<Assignment, StatementNodeTree> assignments = new HashMap<>();
        deletedStatement.getStatement().accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                assignments.put(node, deletedStatement);
                return true;
            }
        });
        if (assignments.size() == 0)
            return;
        boolean mergeFlag = false;
        for (Pair<StatementNodeTree, StatementNodeTree> pair : matchedStatements) {
            StatementNodeTree oldStatement = pair.getLeft();
            StatementNodeTree newStatement = pair.getRight();
            if (!methodNodePairs.contains(Pair.of(deletedStatement.getRoot(), newStatement.getRoot())))
                continue;
            if (mergeFlag)
                break;
            if (oldStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT && newStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                VariableDeclarationStatement statement1 = (VariableDeclarationStatement) oldStatement.getStatement();
                VariableDeclarationStatement statement2 = (VariableDeclarationStatement) newStatement.getStatement();
                if (statement1.toString().equals(statement2.toString())) {
                    List<VariableDeclarationFragment> fragments1 = statement1.fragments();
                    List<VariableDeclarationFragment> fragments2 = statement2.fragments();
                    for (Assignment assignment : assignments.keySet()) {
                        for (int i = 0; i < Math.min(fragments1.size(), fragments2.size()); i++) {
                            VariableDeclarationFragment fragment1 = fragments1.get(i);
                            VariableDeclarationFragment fragment2 = fragments2.get(i);
                            if (fragment1.getName().getIdentifier().equals(assignment.getLeftHandSide().toString()) &&
                                    fragment2.getInitializer() != null &&
                                    fragment2.getInitializer().toString().equals(assignment.getRightHandSide().toString())) {
                                DeclarationNodeTree oldEntity = oldStatement.getRoot().getMethodEntity();
                                DeclarationNodeTree newEntity = newStatement.getRoot().getMethodEntity();
                                MergeDeclarationAndAssignmentRefactoring refactoring = new MergeDeclarationAndAssignmentRefactoring(oldStatement.getExpression(), deletedStatement.getExpression(), newStatement.getExpression(), oldEntity, newEntity);
                                refactorings.add(refactoring);
                                mergeFlag = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void processVariableDeclarationStatement(Set<Pair<MethodNode, MethodNode>> methodNodePairs,
                                                     StatementNodeTree deletedStatement, Set<Pair<StatementNodeTree,
            StatementNodeTree>> matchedStatements, List<Refactoring> refactorings) {
        boolean inlinedFlag = false;
        boolean removedFlag = false;
        boolean mergeFlag = false;
        for (Pair<StatementNodeTree, StatementNodeTree> pair : matchedStatements) {
            StatementNodeTree oldStatement = pair.getLeft();
            StatementNodeTree newStatement = pair.getRight();
            if (!methodNodePairs.contains(Pair.of(deletedStatement.getRoot(), newStatement.getRoot())))
                continue;
            DeclarationNodeTree oldEntity = oldStatement.getRoot().getMethodEntity();
            DeclarationNodeTree newEntity = newStatement.getRoot().getMethodEntity();
            String expression1 = oldStatement.getExpression();
            String expression2 = newStatement.getExpression();
            if (!mergeFlag) {
                if (expression1.contains("=") && expression2.contains("=")) {
                    VariableDeclarationStatement statement1 = (VariableDeclarationStatement) deletedStatement.getStatement();
                    List<VariableDeclarationFragment> fragments = statement1.fragments();
                    for (VariableDeclarationFragment fragment : fragments) {
                        if ((statement1.getType().toString() + " " + fragment.getName().getIdentifier() + expression1.substring(expression1.indexOf("="))).
                                equals(expression2)) {
                            MergeDeclarationAndAssignmentRefactoring refactoring = new MergeDeclarationAndAssignmentRefactoring(expression1, deletedStatement.getExpression(), expression2, oldEntity, newEntity);
                            refactorings.add(refactoring);
                            mergeFlag = true;
                        }
                    }
                }
            }
            if (!mergeFlag && !inlinedFlag && !removedFlag) {
                if (oldStatement.getType() == StatementType.RETURN_STATEMENT &&
                        newStatement.getType() == StatementType.RETURN_STATEMENT) {
                    ReturnStatement statement1 = (ReturnStatement) oldStatement.getStatement();
                    ReturnStatement statement2 = (ReturnStatement) newStatement.getStatement();
                    VariableDeclarationStatement variableDeclaration = (VariableDeclarationStatement) deletedStatement.getStatement();
                    List<VariableDeclarationFragment> fragments = variableDeclaration.fragments();
                    for (VariableDeclarationFragment fragment : fragments) {
                        if (statement1.getExpression() != null &&
                                statement1.getExpression().toString().equals(fragment.getName().getIdentifier()) &&
                                fragment.getInitializer() != null && statement2.getExpression() != null &&
                                statement2.getExpression().toString().equals(fragment.getInitializer().toString())) {
                            InlineVariableRefactoring refactoring = new InlineVariableRefactoring(fragment, oldEntity, newEntity);
                            refactorings.add(refactoring);
                            removedFlag = true;
                        }
                    }
                }
                if (oldStatement.getType() == StatementType.EXPRESSION_STATEMENT &&
                        newStatement.getType() == StatementType.RETURN_STATEMENT) {
                    ExpressionStatement statement1 = (ExpressionStatement) oldStatement.getStatement();
                    ReturnStatement statement2 = (ReturnStatement) newStatement.getStatement();
                    VariableDeclarationStatement variableDeclaration = (VariableDeclarationStatement) deletedStatement.getStatement();
                    List<VariableDeclarationFragment> fragments = variableDeclaration.fragments();
                    for (VariableDeclarationFragment fragment : fragments) {
                        if (statement1.getExpression() instanceof Assignment
                                && ((Assignment) statement1.getExpression()).getLeftHandSide().toString().equals(fragment.getName().getIdentifier())
                                && ((Assignment) statement1.getExpression()).getRightHandSide().toString().equals(statement2.getExpression().toString())) {
                            InlineVariableRefactoring refactoring = new InlineVariableRefactoring(fragment, oldEntity, newEntity);
                            refactorings.add(refactoring);
                            removedFlag = true;
                        }
                    }
                }
                VariableDeclarationStatement statement = (VariableDeclarationStatement) deletedStatement.getStatement();
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) statement.fragments().get(0);
                Expression initializer = fragment.getInitializer();
                if (initializer != null) {
                    if (oldStatement.getExpression().replace(fragment.getName().getIdentifier(), initializer.toString()).equals(newStatement.getExpression()) &&
                            !expression1.equals(expression2)) {
                        InlineVariableRefactoring refactoring = new InlineVariableRefactoring(fragment, oldEntity, newEntity);
                        refactorings.add(refactoring);
                        inlinedFlag = true;
                    }
                }
            }
        }
    }

    private void processIfStatement(Set<Pair<MethodNode, MethodNode>> methodNodePairs,
                                    StatementNodeTree deletedStatement, Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements,
                                    List<Refactoring> refactorings) {
        boolean ternaryFlag = false;
        boolean mergeFlag = false;
        for (Pair<StatementNodeTree, StatementNodeTree> pair : matchedStatements) {
            StatementNodeTree oldStatement = pair.getLeft();
            StatementNodeTree newStatement = pair.getRight();
            if (!methodNodePairs.contains(Pair.of(deletedStatement.getRoot(), newStatement.getRoot())))
                continue;
            DeclarationNodeTree oldEntity = oldStatement.getRoot().getMethodEntity();
            DeclarationNodeTree newEntity = newStatement.getRoot().getMethodEntity();
            if (!ternaryFlag) {
                if (oldStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT &&
                        newStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                    String expression2 = newStatement.getExpression();
                    String expression1 = deletedStatement.getExpression();
                    expression1 = expression1.substring(expression1.indexOf("if") + 2);
                    if ((expression2.contains(expression1 + " ? ") || (expression2.contains(expression1.replace("(", "").replace(")", "") + " ? ")) ||
                            expression2.contains(expression1.replace("==", "!=") + " ? ") ||
                            expression2.contains(expression1.replace("!=", "==") + " ? ") ||
                            expression2.contains(expression1.replace("(", "(!") + " ? ") ||
                            expression2.contains(expression1.replace("(!", "(") + " ? ")) &&
                            expression2.contains(" : ") && expression2.lastIndexOf(expression1 + " ? ") < expression2.lastIndexOf(" : ")) {
                        ReplaceIfWithTernaryRefactoring refactoring = new ReplaceIfWithTernaryRefactoring(expression1, expression2, oldEntity, newEntity);
                        refactorings.add(refactoring);
                        ternaryFlag = true;
                    }
                }
            }

            if (!mergeFlag) {
                if (oldStatement.getType() == StatementType.IF_STATEMENT && newStatement.getType() == StatementType.IF_STATEMENT) {
                    String expression1 = oldStatement.getExpression();
                    String expression2 = newStatement.getExpression();
                    String deletedExpression = deletedStatement.getExpression();
                    if ((expression2.contains(deletedExpression.replace("if(", "")) ||
                            expression2.contains(deletedExpression.replace("if(", "").replace(")", ""))) &&
                            !(expression1.contains(deletedExpression.replace("if(", ""))
                                    || expression1.contains(deletedExpression.replace("if(", "").replace(")", ""))
                                    || expression1.contains(deletedExpression.replace(")", "")))) {
                        Set<String> mergedConditionals = new LinkedHashSet<>();
                        mergedConditionals.add(expression1);
                        mergedConditionals.add(deletedExpression);
                        MergeConditionalRefactoring refactoring = new MergeConditionalRefactoring(mergedConditionals, expression2, oldEntity, newEntity);
                        refactorings.add(refactoring);
                        mergeFlag = true;
                    }
                }
            }
        }
    }

    private void detectRefactoringsBetweenAddedAndDeletedStatements(Set<Pair<MethodNode, MethodNode>> methodNodePairs,
                                                                    Set<StatementNodeTree> addedStatements, Set<StatementNodeTree> deletedStatements,
                                                                    MatchPair matchPair, List<Refactoring> refactorings) {
        for (StatementNodeTree deletedStatement : deletedStatements) {
            for (StatementNodeTree addedStatement : addedStatements) {
                if (!methodNodePairs.contains(Pair.of(deletedStatement.getRoot(), addedStatement.getRoot())))
                    continue;
                if ((deletedStatement.getType() == StatementType.FOR_STATEMENT || deletedStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                        deletedStatement.getType() == StatementType.WHILE_STATEMENT || deletedStatement.getType() == StatementType.DO_STATEMENT) &&
                        (addedStatement.getType() == StatementType.EXPRESSION_STATEMENT || addedStatement.getType() == StatementType.RETURN_STATEMENT)) {
                    if (MethodUtils.isStreamAPI(addedStatement.getStatement()) && DiceFunction.calculateContextSimilarity(matchPair, deletedStatement, addedStatement) > 0.5) {
                        String loop = deletedStatement.getExpression();
                        String pipeline = addedStatement.getExpression();
                        DeclarationNodeTree oldEntity = deletedStatement.getRoot().getMethodEntity();
                        DeclarationNodeTree newEntity = addedStatement.getRoot().getMethodEntity();
                        ReplaceLoopWithPipelineRefactoring refactoring = new ReplaceLoopWithPipelineRefactoring(loop, pipeline, oldEntity, newEntity);
                        refactorings.add(refactoring);
                    }
                }

                if ((addedStatement.getType() == StatementType.FOR_STATEMENT || addedStatement.getType() == StatementType.ENHANCED_FOR_STATEMENT ||
                        addedStatement.getType() == StatementType.WHILE_STATEMENT || addedStatement.getType() == StatementType.DO_STATEMENT) &&
                        (deletedStatement.getType() == StatementType.EXPRESSION_STATEMENT || deletedStatement.getType() == StatementType.RETURN_STATEMENT)) {
                    if (MethodUtils.isStreamAPI(deletedStatement.getStatement()) && DiceFunction.calculateContextSimilarity(matchPair, deletedStatement, addedStatement) > 0.5) {
                        String pipeline = deletedStatement.getExpression();
                        String loop = addedStatement.getExpression();
                        DeclarationNodeTree oldEntity = deletedStatement.getRoot().getMethodEntity();
                        DeclarationNodeTree newEntity = addedStatement.getRoot().getMethodEntity();
                        ReplacePipelineWithLoopRefactoring refactoring = new ReplacePipelineWithLoopRefactoring(pipeline, loop, oldEntity, newEntity);
                        refactorings.add(refactoring);
                    }
                }

                if (addedStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                    String expression1 = deletedStatement.getExpression();
                    expression1 = expression1.substring(expression1.indexOf("if") + 2);
                    String expression2 = addedStatement.getExpression();
                    if ((expression2.contains(expression1 + " ? ") || (expression2.contains(expression1.replace("(", "").replace(")", "") + " ? ")) ||
                            expression2.contains(expression1.replace("==", "!=") + " ? ") ||
                            expression2.contains(expression1.replace("!=", "==") + " ? ") ||
                            expression2.contains(expression1.replace("(", "(!") + " ? ") ||
                            expression2.contains(expression1.replace("(!", "(") + " ? ")) &&
                            expression2.contains(" : ") && expression2.lastIndexOf(expression1 + " ? ") < expression2.lastIndexOf(" : ") &&
                            DiceFunction.calculateContextSimilarity(matchPair, deletedStatement, addedStatement) > 0.5) {
                        DeclarationNodeTree oldEntity = deletedStatement.getRoot().getMethodEntity();
                        DeclarationNodeTree newEntity = addedStatement.getRoot().getMethodEntity();
                        ReplaceIfWithTernaryRefactoring refactoring = new ReplaceIfWithTernaryRefactoring(expression1, expression2, oldEntity, newEntity);
                        refactorings.add(refactoring);
                    }
                }
            }
        }

        for (Pair<MethodNode, MethodNode> methodNode : methodNodePairs) {
            if (!matchPair.getMatchedEntities().contains(Pair.of(methodNode.getLeft().getMethodEntity(), methodNode.getRight().getMethodEntity())))
                continue;
            List<StatementNodeTree> deletedStmts = methodNode.getLeft().getUnmatchedNodes();
            List<StatementNodeTree> addedStmts = methodNode.getRight().getUnmatchedNodes();
            for (StatementNodeTree deletedStatement : deletedStmts) {
                boolean discovered = false;
                if (deletedStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                    VariableDeclarationStatement statement = (VariableDeclarationStatement) deletedStatement.getStatement();
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) statement.fragments().get(0);
                    Expression initializer = fragment.getInitializer();
                    if (initializer == null)
                        continue;
                    String expression = initializer.toString();
                    for (StatementNodeTree addedStmt : addedStmts) {
                        if (discovered)
                            break;
                        if (addedStmt.hasChildren())
                            continue;
                        if (addedStmt.getExpression().contains(expression)) {
                            for (StatementNodeTree deletedStmt : deletedStatement.getParent().getChildren()) {
                                if (deletedStmt == deletedStatement)
                                    continue;
                                if (deletedStmt.getExpression().contains(fragment.getName().getIdentifier()) &&
                                        addedStmt.getExpression().contains(expression) &&
                                        deletedStmt.getType() == addedStmt.getType()) {
                                    DeclarationNodeTree oldEntity = deletedStmt.getRoot().getMethodEntity();
                                    DeclarationNodeTree newEntity = addedStmt.getRoot().getMethodEntity();
                                    InlineVariableRefactoring refactoring = new InlineVariableRefactoring(fragment, oldEntity, newEntity);
                                    refactorings.add(refactoring);
                                    discovered = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            for (StatementNodeTree addedStatement : addedStmts) {
                boolean discovered = false;
                if (addedStatement.getType() == StatementType.VARIABLE_DECLARATION_STATEMENT) {
                    VariableDeclarationStatement statement = (VariableDeclarationStatement) addedStatement.getStatement();
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) statement.fragments().get(0);
                    Expression initializer = fragment.getInitializer();
                    if (initializer == null)
                        continue;
                    String expression = initializer.toString();
                    for (StatementNodeTree deletedStmt : deletedStmts) {
                        if (discovered)
                            break;
                        if (deletedStmt.hasChildren())
                            continue;
                        if (deletedStmt.getExpression().contains(expression)) {
                            for (StatementNodeTree addedStmt : addedStatement.getParent().getChildren()) {
                                if (addedStmt == addedStatement)
                                    continue;
                                if (addedStmt.getExpression().contains(fragment.getName().getIdentifier()) &&
                                        deletedStmt.getExpression().contains(expression) &&
                                        deletedStmt.getType() == addedStmt.getType()) {
                                    DeclarationNodeTree oldEntity = deletedStmt.getRoot().getMethodEntity();
                                    DeclarationNodeTree newEntity = deletedStmt.getRoot().getMethodEntity();
                                    ExtractVariableRefactoring refactoring = new ExtractVariableRefactoring(fragment, oldEntity, newEntity);
                                    refactorings.add(refactoring);
                                    discovered = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
