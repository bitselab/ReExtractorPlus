package org.reextractor.service;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.lib.Repository;
import org.reextractor.dto.EntityInfo;
import org.reextractor.dto.EntityType;
import org.remapper.dto.DeclarationNodeTree;
import org.remapper.dto.MatchPair;
import org.remapper.dto.StatementNodeTree;
import org.remapper.dto.StatementType;
import org.remapper.handler.MatchingHandler;
import org.remapper.service.EntityMatcherService;
import org.remapper.service.EntityMatcherServiceImpl;
import org.remapper.service.GitService;
import org.remapper.util.GitServiceImpl;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public class ReMapper {

    public Set<Pair<EntityInfo, EntityInfo>> match(String projectPath, String commitId) throws IOException {
        GitService gitService = new GitServiceImpl();
        EntityMatcherService matchService = new EntityMatcherServiceImpl();
        Set<Pair<EntityInfo, EntityInfo>> matchPairs = new LinkedHashSet<>();
        try (Repository repo = gitService.openRepository(projectPath)) {
            matchService.matchAtCommit(repo, commitId, new MatchingHandler() {
                @Override
                public void handle(String commitId, MatchPair matchPair) {
                    Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities = matchPair.getMatchedEntities();
                    Set<Pair<StatementNodeTree, StatementNodeTree>> matchedStatements = matchPair.getMatchedStatements();
                    for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
                        EntityInfo entityInfo1 = getEntityInfo(pair.getLeft());
                        EntityInfo entityInfo2 = getEntityInfo(pair.getRight());
                        matchPairs.add(Pair.of(entityInfo1, entityInfo2));
                    }
                    for (Pair<StatementNodeTree, StatementNodeTree> pair : matchedStatements) {
                        StatementNodeTree left = pair.getLeft();
                        StatementNodeTree right = pair.getRight();
                        if (left.getType() == StatementType.LAMBDA_EXPRESSION_BODY && right.getType() == StatementType.LAMBDA_EXPRESSION_BODY)
                            continue;
                        if (left.getExpression().equals("{") && right.getExpression().equals("{")) {
                            if (left.getBlockExpression().equals("finally") && right.getBlockExpression().equals("finally")) {
                                left.setExpression("finally");
                                right.setExpression("finally");
                            } else
                                continue;
                        }
                        EntityInfo entityInfo1 = getEntityInfo(left);
                        EntityInfo entityInfo2 = getEntityInfo(right);
                        matchPairs.add(Pair.of(entityInfo1, entityInfo2));
                    }
                }

                @Override
                public void handleException(String commitId, Exception e) {
                    System.out.println(commitId);
                    e.printStackTrace();
                }
            });
            return matchPairs;
        }
    }

    private EntityInfo getEntityInfo(DeclarationNodeTree entity) {
        EntityInfo entityInfo = new EntityInfo();
        String type = entity.getType().getName();
        if (type.equals("Compilation Unit"))
            entityInfo.setType(EntityType.COMPILATION_UNIT);
        else if (type.equals("Class"))
            entityInfo.setType(EntityType.CLASS);
        else if (type.equals("Interface"))
            entityInfo.setType(EntityType.INTERFACE);
        else if (type.equals("Enum"))
            entityInfo.setType(EntityType.ENUM);
        else if (type.equals("Annotation Type"))
            entityInfo.setType(EntityType.ANNOTATION_TYPE);
        else if (type.equals("Initializer"))
            entityInfo.setType(EntityType.INITIALIZER);
        else if (type.equals("Field"))
            entityInfo.setType(EntityType.FIELD);
        else if (type.equals("Method"))
            entityInfo.setType(EntityType.METHOD);
        else if (type.equals("Annotation Member"))
            entityInfo.setType(EntityType.ANNOTATION_MEMBER);
        else if (type.equals("Enum Constant"))
            entityInfo.setType(EntityType.ENUM_CONSTANT);
        if (type.equals("Class") || type.equals("Interface") || type.equals("Enum") || type.equals("Annotation Type"))
            entityInfo.setContainer(entity.getNamespace() + "." + entity.getName());
        else
            entityInfo.setContainer(entity.getNamespace());
        entityInfo.setName(entity.getName());
        entityInfo.setDeclaration(entity.getDeclaration());
        entityInfo.setLocationInfo(entity.getLocationInfo());
        return entityInfo;
    }

    private EntityInfo getEntityInfo(StatementNodeTree statement) {
        EntityInfo entityInfo = new EntityInfo();
        entityInfo.setContainer(statement.getRoot().getExpression());
        entityInfo.setType(EntityType.STATEMENT);
        entityInfo.setName(statement.getExpression());
        entityInfo.setLocationInfo(statement.getLocationInfo());
        return entityInfo;
    }
}
