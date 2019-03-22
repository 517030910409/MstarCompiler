package com.fur.abstractSyntaxTree.node;

import com.fur.abstractSyntaxTree.AbstractSyntaxTreeVisitor;
import org.antlr.v4.runtime.Token;

public class VariableDeclarationNode extends BaseDeclarationNode {

    private BaseTypeNode typeNode;
    private BaseExpressionNode expressionNode;

    public VariableDeclarationNode(BaseTypeNode _typeNode, String _name, BaseExpressionNode _expressionNode, Token token) {
        super(_name, token);
        typeNode = _typeNode;
        expressionNode = _expressionNode;
    }

    public BaseTypeNode getTypeNode() {
        return typeNode;
    }

    public BaseExpressionNode getExpressionNode() {
        return expressionNode;
    }

    @Override
    public <T> T accept(AbstractSyntaxTreeVisitor<? extends T> visitor) {
        return visitor.visitVariableDeclarationNode(this);
    }

}