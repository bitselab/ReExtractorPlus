package org.reextractorplus.refactoring;

import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.reextractorplus.util.MethodUtils;
import org.reextractorplus.util.VariableUtils;
import org.remapper.dto.DeclarationNodeTree;
import org.remapper.dto.LocationInfo;

import java.util.ArrayList;
import java.util.List;

public class ReorderParameterRefactoring implements Refactoring {

    private List<SingleVariableDeclaration> parametersBefore;
    private List<SingleVariableDeclaration> parametersAfter;
    private DeclarationNodeTree operationBefore;
    private DeclarationNodeTree operationAfter;

    public ReorderParameterRefactoring(DeclarationNodeTree operationBefore, DeclarationNodeTree operationAfter) {
        this.operationBefore = operationBefore;
        this.operationAfter = operationAfter;
        this.parametersBefore = new ArrayList<>();
        MethodDeclaration removedOperation = (MethodDeclaration) operationBefore.getDeclaration();
        parametersBefore = removedOperation.parameters();
        this.parametersAfter = new ArrayList<>();
        MethodDeclaration addedOperation = (MethodDeclaration) operationAfter.getDeclaration();
        parametersAfter = addedOperation.parameters();
    }

    public RefactoringType getRefactoringType() {
        return RefactoringType.REORDER_PARAMETER;
    }

    public LocationInfo leftSide() {
        return operationBefore.getLocation();
    }

    public LocationInfo rightSide() {
        return operationAfter.getLocation();
    }

    public String getName() {
        return this.getRefactoringType().getDisplayName();
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append("\t");
        int len = 0;
        sb.append("[");
        for (SingleVariableDeclaration parameter : parametersBefore) {
            sb.append(VariableUtils.getVariableDeclaration(parameter));
            if (len < parametersBefore.size() - 1)
                sb.append(", ");
            len += 1;
        }
        sb.append("]");
        sb.append(" to ");
        len = 0;
        sb.append("[");
        for (SingleVariableDeclaration parameter : parametersAfter) {
            sb.append(VariableUtils.getVariableDeclaration(parameter));
            if (len < parametersAfter.size() - 1)
                sb.append(", ");
            len += 1;
        }
        sb.append("]");
        sb.append(" in method ");
        sb.append(MethodUtils.getMethodDeclaration(operationAfter));
        sb.append(" from class ");
        sb.append(operationAfter.getNamespace());
        return sb.toString();
    }

    public List<SingleVariableDeclaration> getParametersBefore() {
        return parametersBefore;
    }

    public List<SingleVariableDeclaration> getParametersAfter() {
        return parametersAfter;
    }

    public DeclarationNodeTree getOperationBefore() {
        return operationBefore;
    }

    public DeclarationNodeTree getOperationAfter() {
        return operationAfter;
    }
}
