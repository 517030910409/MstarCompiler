package com.fur.abstractSyntaxTree.node;

import org.antlr.v4.runtime.Token;

public abstract class BaseExpressionNode extends BaseNode {

    public BaseExpressionNode(Token token) {
        super(token);
    }

}