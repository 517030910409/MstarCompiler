package com.fur.intermediateRepresentation;

import com.fur.Position;
import com.fur.abstractSyntaxTree.AbstractSyntaxTreeBaseVisitor;
import com.fur.abstractSyntaxTree.node.*;
import com.fur.enumerate.OperatorList;
import com.fur.enumerate.PrimaryTypeList;
import com.fur.intermediateRepresentation.node.*;
import com.fur.nasm.label.NASMLabel;
import com.fur.nasm.label.NASMLabels;
import com.fur.nasm.memory.NASMStackMemory;
import com.fur.nasm.memory.NASMStaticMemory;
import com.fur.symbolTable.Entity.*;
import com.fur.type.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IntermediateRepresentationBuilder extends AbstractSyntaxTreeBaseVisitor<FunctionIRNode> {

    private ClassEntity globalEntity;
    private BaseEntity currentEntity;
    private List<IRRegister> reallocateIRRegisters = new ArrayList<>();
    private NASMLabels labels = new NASMLabels();

    public IntermediateRepresentationBuilder(ClassEntity _globalEntity) {
        globalEntity = _globalEntity;
        currentEntity = globalEntity;
        getFunctionLabel();
    }

    private void getFunctionLabel() {
        for (String name : globalEntity.getScope().keySet()) {
            BaseEntity entity = globalEntity.getScope().get(name);
            if (entity instanceof ClassEntity) {
                for (String functionName : ((ClassEntity) entity).getScope().keySet()) {
                    BaseEntity memberFunctionEntity = ((ClassEntity) entity).getScope().get(functionName);
                    if (memberFunctionEntity instanceof FunctionEntity) {
                        FunctionLabelIRNode functionEntryLabel = new FunctionLabelIRNode((FunctionEntity) memberFunctionEntity);
                        functionEntryLabel.setNasmLabel(new NASMLabel("CLASS_" + name + "_MEMBER_" + functionName));
                        ((FunctionEntity) memberFunctionEntity).setEntryLabel(functionEntryLabel);
                        ((FunctionEntity) memberFunctionEntity).setReturnLabel(new LabelIRNode());
                    }
                }
            }
            if (entity instanceof FunctionEntity) {
                FunctionLabelIRNode label = new FunctionLabelIRNode((FunctionEntity) entity);
                label.setNasmLabel(new NASMLabel("FUNCTION_" + name));
                ((FunctionEntity) entity).setEntryLabel(label);
                ((FunctionEntity) entity).setReturnLabel(new LabelIRNode());
            }
        }
    }

    private BaseType getArrayType(BaseType baseType, int dimension) {
        if (dimension == 0) return baseType;
        return new ArrayType(getArrayType(baseType, dimension - 1));
    }

    private FunctionIRNode loadMemory(IRRegister address) {
        List<BaseIRNode> body = new ArrayList<>();
        if (!(address.getType() instanceof AddressType)) return new FunctionIRNode(body, address);
        IRRegister destIRRegister = new IRRegister();
        destIRRegister.setType(((AddressType) address.getType()).getBaseType());
        body.add(new OpIRNode(OperatorList.LOAD, destIRRegister, address));
        return new FunctionIRNode(body, destIRRegister);
    }
    
    private IRRegister reallocate(IRRegister irRegister) {
        if (irRegister.getMemory() != null)
            return irRegister;
        if (irRegister.getReallocate() != null)
            return irRegister.getReallocate();
        Map<IRRegister, Boolean> reallocateMap = new HashMap<>();
        for (IRRegister reallocateIRRegister : reallocateIRRegisters)
            reallocateMap.put(reallocateIRRegister, true);
        for (IRRegister nearbyIRRegister : irRegister.getNearbyIRRegisters())
            reallocateMap.put(nearbyIRRegister.getReallocate(), false);
        for (IRRegister reallocateIRRegister : reallocateIRRegisters)
            if (reallocateMap.get(reallocateIRRegister)) {
                irRegister.setReallocate(reallocateIRRegister);
                return reallocateIRRegister;
            }
        IRRegister reallocateIRRegister = new IRRegister();
        reallocateIRRegisters.add(reallocateIRRegister);
        irRegister.setReallocate(reallocateIRRegister);
        return reallocateIRRegister;
    }

    private void liveAnalyze(List<BaseIRNode> instructions) {
        while (true) {
            boolean diff = false;
            for (int i = 0; i < instructions.size() - 1; i++) {
                BaseIRNode instruction = instructions.get(i);
                List<IRRegister> liveIRRegister = new ArrayList<>(instruction.getLiveIRRegister());
                if (instruction instanceof BranchIRNode) {
                    instruction.getLiveIRRegister().addAll(((BranchIRNode) instruction).getTrueDestIRNode().getLiveIRRegister());
                    instruction.getLiveIRRegister().addAll(((BranchIRNode) instruction).getFalseDestIDNode().getLiveIRRegister());
                } else if (instruction instanceof JumpIRNode)
                    instruction.getLiveIRRegister().addAll(((JumpIRNode) instruction).getDestLabelNode().getLiveIRRegister());
                else instruction.getLiveIRRegister().addAll(instructions.get(i + 1).getLiveIRRegister());
                if (instruction instanceof CmpIRNode)
                    instruction.getLiveIRRegister().remove(((CmpIRNode) instruction).getDestIRRegister());
                if (instruction instanceof OpIRNode)
                    if (((OpIRNode) instruction).getOperator() == OperatorList.ASSIGN)
                        instruction.getLiveIRRegister().remove(((OpIRNode) instruction).getDestIRRegister());
                if (instruction instanceof CallIRNode)
                    instruction.getLiveIRRegister().remove(((CallIRNode) instruction).getDestIRRegister());
                if (instruction instanceof BranchIRNode)
                    instruction.getLiveIRRegister().add(((BranchIRNode) instruction).getConditionRegister());
                if (instruction instanceof CallIRNode)
                    for (IRRegister parameterIRRegister : ((CallIRNode) instruction).getParameterIRRegisters())
                        instruction.getLiveIRRegister().add(parameterIRRegister);
                if (instruction instanceof CmpIRNode) {
                    instruction.getLiveIRRegister().add(((CmpIRNode) instruction).getOperateIRRegister1());
                    instruction.getLiveIRRegister().add(((CmpIRNode) instruction).getOperateIRRegister2());
                }
                if (instruction instanceof OpIRNode)
                    if (((OpIRNode) instruction).getSourceIRRegister() != null)
                        instruction.getLiveIRRegister().add(((OpIRNode) instruction).getSourceIRRegister());
                if (instruction instanceof RetIRNode)
                    instruction.getLiveIRRegister().add(((RetIRNode) instruction).getReturnIRRegister());
                if (liveIRRegister.size() != instruction.getLiveIRRegister().size()) diff = true;
                else {
                    int j = 0;
                    for (IRRegister live : instruction.getLiveIRRegister())
                        if (live != liveIRRegister.get(j++)) diff = true;
                }
            }
            if (!diff) break;
        }
        for (BaseIRNode instruction : instructions)
            for (IRRegister irRegister1 : instruction.getLiveIRRegister())
                for (IRRegister irRegister2 : instruction.getLiveIRRegister())
                    irRegister1.getNearbyIRRegisters().add(irRegister2);
        for (BaseIRNode instruction : instructions) {
            if (instruction instanceof BranchIRNode)
                ((BranchIRNode) instruction).setConditionRegister(reallocate(((BranchIRNode) instruction).getConditionRegister()));
            if (instruction instanceof CmpIRNode) {
                ((CmpIRNode) instruction).setDestIRRegister(reallocate(((CmpIRNode) instruction).getDestIRRegister()));
                ((CmpIRNode) instruction).setOperateIRRegister1(reallocate(((CmpIRNode) instruction).getOperateIRRegister1()));
                ((CmpIRNode) instruction).setOperateIRRegister2(reallocate(((CmpIRNode) instruction).getOperateIRRegister2()));
            }
            if (instruction instanceof OpIRNode) {
                ((OpIRNode) instruction).setDestIRRegister(reallocate(((OpIRNode) instruction).getDestIRRegister()));
                if (((OpIRNode) instruction).getSourceIRRegister() != null)
                    ((OpIRNode) instruction).setSourceIRRegister(reallocate(((OpIRNode) instruction).getSourceIRRegister()));
            }
            if (instruction instanceof RetIRNode)
                ((RetIRNode) instruction).setReturnIRRegister(reallocate(((RetIRNode) instruction).getReturnIRRegister()));
            if (instruction instanceof CallIRNode) {
                ((CallIRNode) instruction).setDestIRRegister(reallocate(((CallIRNode) instruction).getDestIRRegister()));
                for (int i = 0; i < ((CallIRNode) instruction).getParameterIRRegisters().size(); i++)
                    ((CallIRNode) instruction).setParameterIRRegisters(i, reallocate(((CallIRNode) instruction).getParameterIRRegisters().get(i)));
            }
        }
    }

    private List<BaseIRNode> memoryAllocate(List<BaseIRNode> instructions) {
        List<BaseIRNode> code = new ArrayList<>();
        FunctionLabelIRNode currentFunction = null;
        for (BaseIRNode instruction : instructions) {
            if (instruction instanceof FunctionLabelIRNode) {
                currentFunction = (FunctionLabelIRNode) instruction;
                FunctionEntity functionEntity = currentFunction.getEntity();
                if (functionEntity == null) continue;
                for (int i = 0; i < functionEntity.getParameterList().size(); i++) {
                    VariableEntity parameterEntity = functionEntity.getParameterList().get(i);
                    IRRegister paramaterIRRegister = parameterEntity.getIRRegister();
                    if (i < 6) paramaterIRRegister.setMemory(new NASMStackMemory(i + 1));
                    else paramaterIRRegister.setMemory(new NASMStackMemory(4 - i));
                }
            }
        }
        int cnt = 0;
        for (BaseIRNode instruction : instructions) {
            if (instruction instanceof FunctionLabelIRNode) {
                currentFunction = (FunctionLabelIRNode) instruction;
                if (currentFunction.getEntity() != null) {
                    cnt = currentFunction.getEntity().getParameterList().size();
                    if (cnt > 6) cnt = 6;
                } else cnt = 0;
            }
            if (currentFunction != null) {
                if (instruction instanceof BranchIRNode)
                    if (((BranchIRNode) instruction).getConditionRegister().getMemory() == null)
                        ((BranchIRNode) instruction).getConditionRegister().setMemory(new NASMStackMemory(++cnt));
                if (instruction instanceof OpIRNode) {
                    if (((OpIRNode) instruction).getDestIRRegister().getMemory() == null)
                        ((OpIRNode) instruction).getDestIRRegister().setMemory(new NASMStackMemory(++cnt));
                    if (((OpIRNode) instruction).getSourceIRRegister() != null)
                        if (((OpIRNode) instruction).getSourceIRRegister().getMemory() == null)
                            ((OpIRNode) instruction).getSourceIRRegister().setMemory(new NASMStackMemory(++cnt));
                }
                if (instruction instanceof CallIRNode) {
                    if (((CallIRNode) instruction).getDestIRRegister().getMemory() == null)
                        ((CallIRNode) instruction).getDestIRRegister().setMemory(new NASMStackMemory(++cnt));
                    for (IRRegister parameterIRRegister : ((CallIRNode) instruction).getParameterIRRegisters())
                        if (parameterIRRegister.getMemory() == null)
                            parameterIRRegister.setMemory(new NASMStackMemory(++cnt));
                }
                if (instruction instanceof CmpIRNode) {
                    if (((CmpIRNode) instruction).getDestIRRegister().getMemory() == null)
                        ((CmpIRNode) instruction).getDestIRRegister().setMemory(new NASMStackMemory(++cnt));
                    if (((CmpIRNode) instruction).getOperateIRRegister1().getMemory() == null)
                        ((CmpIRNode) instruction).getOperateIRRegister1().setMemory(new NASMStackMemory(++cnt));
                    if (((CmpIRNode) instruction).getOperateIRRegister2().getMemory() == null)
                        ((CmpIRNode) instruction).getOperateIRRegister2().setMemory(new NASMStackMemory(++cnt));
                }
                if (instruction instanceof RetIRNode)
                    if (((RetIRNode) instruction).getReturnIRRegister().getMemory() == null)
                        ((RetIRNode) instruction).getReturnIRRegister().setMemory(new NASMStackMemory(++cnt));
            }
            if (instruction instanceof RetIRNode) {
                assert currentFunction != null;
                currentFunction.setIrRegisterSize(cnt);
                currentFunction = null;
            }
            if (instruction instanceof LabelIRNode)
                if (((LabelIRNode) instruction).getNasmLabel() == null)
                    ((LabelIRNode) instruction).setNasmLabel(labels.getnew());
            code.add(instruction);
        }
        return code;
    }

    @Override
    public FunctionIRNode visitCompilationUnitNode(CompilationUnitNode node) {
        List<BaseIRNode> body = new ArrayList<>();
        FunctionLabelIRNode mainLabel = new FunctionLabelIRNode(null);
        mainLabel.setNasmLabel(new NASMLabel("main"));
        body.add(mainLabel);
        for (BaseNode variableDeclarationNode : node.getBaseNodes())
            if (variableDeclarationNode instanceof VariableDeclarationNode) {
                IRRegister variableIRRegister = visit(variableDeclarationNode).getReturnRegister();
                variableIRRegister.setMemory(new NASMStaticMemory(labels.getnew()));
            }
        for (BaseNode variableInitializeStatement : node.getBaseNodes())
            if (variableInitializeStatement instanceof ExpressionStatementNode)
                body.addAll(visit(variableInitializeStatement).getBodyNode());
        IRRegister exitIRRegister = new IRRegister();
        body.add(new CallIRNode(globalEntity.getFunctionEntity("main").getEntryLabel(), exitIRRegister, new ArrayList<>()));
        body.add(new RetIRNode(exitIRRegister));
        for (BaseNode baseNode : node.getBaseNodes()) {
            if (baseNode instanceof ClassDeclarationNode) {
                currentEntity = globalEntity.getClassEntity(((ClassDeclarationNode) baseNode).getName());
                for (FunctionDeclarationNode memberFunctionNode : ((ClassDeclarationNode) baseNode).getFunctionDeclarationNodes()) {
                    FunctionIRNode function = visit(memberFunctionNode);
                    body.addAll(function.getBodyNode());
                }
                currentEntity = globalEntity;
            }
            if (baseNode instanceof FunctionDeclarationNode)
                body.addAll(visit(baseNode).getBodyNode());
        }
        body.add(new LabelIRNode());
        //liveAnalyze(body);
        return new FunctionIRNode(memoryAllocate(body), null);
    }

    @Override
    public FunctionIRNode visitFunctionDeclarationNode(FunctionDeclarationNode node) {
        assert currentEntity instanceof ClassEntity;
        ClassEntity oldEntity = (ClassEntity) currentEntity;
        currentEntity = ((ClassEntity) currentEntity).getFunctionEntity(node.getName());
        List<BaseIRNode> body = new ArrayList<>();
        IRRegister destIRRegister = new IRRegister();
        body.add(((FunctionEntity) currentEntity).getEntryLabel());
        if (oldEntity != globalEntity) {
            IRRegister parameterRegister = new IRRegister();
            VariableEntity parameterEntity = ((FunctionEntity) currentEntity).get("this");
            parameterEntity.setIRRegister(parameterRegister);
            body.add(new OpIRNode(OperatorList.ASSIGN, parameterRegister, 0));
        }
        for (VariableEntity parameterEntity : ((FunctionEntity) currentEntity).getParameterList()) {
            IRRegister parameterRegister = new IRRegister();
            parameterRegister.setType(parameterEntity.getType());
            parameterEntity.setIRRegister(parameterRegister);
            body.add(new OpIRNode(OperatorList.ASSIGN, parameterRegister, 0));
        }
        ((FunctionEntity) currentEntity).setReturnRegister(destIRRegister);
        body.addAll(visit(node.getBlockStatementNodes()).getBodyNode());
        body.add(((FunctionEntity) currentEntity).getReturnLabel());
        body.add(new RetIRNode(destIRRegister));
        currentEntity = oldEntity;
        return new FunctionIRNode(body, destIRRegister);
    }

    @Override
    public FunctionIRNode visitReturnStatementNode(ReturnStatementNode node) {
        BaseEntity functionEntity = currentEntity;
        while (!(functionEntity instanceof FunctionEntity)) functionEntity = functionEntity.getParentEntity();
        List<BaseIRNode> body = new ArrayList<>();
        if (node.getReturnExpressionNode() != null){
            FunctionIRNode returnExpression = visit(node.getReturnExpressionNode());
            body.addAll(returnExpression.getBodyNode());
            body.add(new OpIRNode(OperatorList.ASSIGN, ((FunctionEntity) functionEntity).getReturnRegister(), returnExpression.getReturnRegister()));
        }
        body.add(new JumpIRNode(((FunctionEntity) functionEntity).getReturnLabel()));
        return new FunctionIRNode(body, null);
    }

    @Override
    public FunctionIRNode visitBlockStatementNode(BlockStatementNode node) {
        BaseEntity oldEntity = currentEntity;
        if (currentEntity instanceof BlockEntity) currentEntity = ((BlockEntity) currentEntity).get(node.getPosition());
        if (currentEntity instanceof FunctionEntity) currentEntity = ((FunctionEntity) currentEntity).getBlockEntity();
        List<BaseIRNode> body = new ArrayList<>();
        for (BaseNode variableDeclarationNode : node.getStatementNodes())
            if (variableDeclarationNode instanceof VariableDeclarationNode)
                body.addAll(visit(variableDeclarationNode).getBodyNode());
        for (BaseNode statementNode : node.getStatementNodes())
            if (statementNode instanceof BaseStatementNode)
                body.addAll(visit(statementNode).getBodyNode());
        currentEntity = oldEntity;
        return new FunctionIRNode(body, null);
    }

    @Override
    public FunctionIRNode visitLoopStatementNode(LoopStatementNode node) {
        BaseEntity oldEntity = currentEntity;
        assert currentEntity instanceof BlockEntity;
        currentEntity = ((BlockEntity) currentEntity).get(node.getPosition());
        assert currentEntity instanceof LoopEntity;
        LabelIRNode conditionLabel = new LabelIRNode();
        ((LoopEntity) currentEntity).setConditionLabel(conditionLabel);
        LabelIRNode continueLabel = new LabelIRNode();
        ((LoopEntity) currentEntity).setContinueLabel(continueLabel);
        LabelIRNode breakLabel = new LabelIRNode();
        ((LoopEntity) currentEntity).setBreakLabel(breakLabel);
        List<BaseIRNode> body = new ArrayList<>();
        if (node.getInitExpressionNode() != null)
            body.addAll(visit(node.getInitExpressionNode()).getBodyNode());
        body.add(conditionLabel);
        FunctionIRNode conditionExpression = visit(node.getConditionExpressionNode());
        body.addAll(conditionExpression.getBodyNode());
        body.add(new BranchIRNode(conditionExpression.getReturnRegister(), conditionLabel, breakLabel));
        body.add(continueLabel);
        FunctionIRNode bodyExpression = visit(node.getBodyStatementNode());
        body.addAll(bodyExpression.getBodyNode());
        if (node.getUpdateExpressionNode() != null) body.addAll(visit(node.getUpdateExpressionNode()).getBodyNode());
        body.add(new JumpIRNode(conditionLabel));
        body.add(breakLabel);
        currentEntity = oldEntity;
        return new FunctionIRNode(body, null);
    }

    @Override
    public FunctionIRNode visitBreakStatementNode(BreakStatementNode node) {
        BaseEntity loopEntity = currentEntity;
        List<BaseIRNode> body = new ArrayList<>();
        while (!(loopEntity instanceof LoopEntity)) loopEntity = loopEntity.getParentEntity();
        body.add(new JumpIRNode(((LoopEntity) loopEntity).getBreakLabel()));
        return new FunctionIRNode(body, null);
    }

    @Override
    public FunctionIRNode visitContinueStatementNode(ContinueStatementNode node) {
        BaseEntity loopEntity = currentEntity;
        List<BaseIRNode> body = new ArrayList<>();
        while (!(loopEntity instanceof LoopEntity)) loopEntity = loopEntity.getParentEntity();
        body.add(new JumpIRNode(((LoopEntity) loopEntity).getConditionLabel()));
        return new FunctionIRNode(body, null);
    }

    @Override
    public FunctionIRNode visitIfStatementNode(IfStatementNode node) {
        BaseEntity oldEntity = currentEntity;
        assert currentEntity instanceof BlockEntity;
        currentEntity = ((BlockEntity) currentEntity).get(node.getPosition());
        assert currentEntity instanceof IfEntity;
        LabelIRNode thenLabel = new LabelIRNode();
        ((IfEntity) currentEntity).setThenLabel(thenLabel);
        LabelIRNode elseLabel = new LabelIRNode();
        ((IfEntity) currentEntity).setElseLabel(elseLabel);
        LabelIRNode endLabel = new LabelIRNode();
        ((IfEntity) currentEntity).setEndLabel(endLabel);
        FunctionIRNode conditionExpression = visit(node.getConditionExpressionNode());
        List<BaseIRNode> body = new ArrayList<>(conditionExpression.getBodyNode());
        body.add(new BranchIRNode(conditionExpression.getReturnRegister(), thenLabel, elseLabel));
        body.add(thenLabel);
        body.addAll(visit(node.getThenStatementNode()).getBodyNode());
        body.add(new JumpIRNode(endLabel));
        body.add(elseLabel);
        body.addAll(visit(node.getThenStatementNode()).getBodyNode());
        body.add(new JumpIRNode(endLabel));
        body.add(endLabel);
        currentEntity = oldEntity;
        return new FunctionIRNode(body, null);
    }

    @Override
    public FunctionIRNode visitVariableDeclarationNode(VariableDeclarationNode node) {
        IRRegister variableRegister = new IRRegister();
        variableRegister.setType(node.getTypeNode().getType());
        VariableEntity variableEntity;
        if (currentEntity instanceof BlockEntity) variableEntity = ((BlockEntity) currentEntity).get(node.getName());
        else {
            assert currentEntity == globalEntity;
            variableEntity = ((ClassEntity) currentEntity).getVariableEntity(node.getName());
        }
        variableEntity.setIRRegister(variableRegister);
        List<BaseIRNode> body = new ArrayList<>();
        body.add(new OpIRNode(OperatorList.ASSIGN, variableRegister, 0));
        return new FunctionIRNode(body, variableRegister);
    }

    @Override
    public FunctionIRNode visitExpressionStatementNode(ExpressionStatementNode node) {
        List<BaseIRNode> instructions = visit(node.getExpressionNode()).getBodyNode();
        List<BaseIRNode> body = new ArrayList<>();
        List<BaseIRNode> preBody = new ArrayList<>();
        List<BaseIRNode> midBody = new ArrayList<>();
        List<BaseIRNode> sufBody = new ArrayList<>();
        for (BaseIRNode instruction : instructions)
            if (instruction instanceof OpIRNode) {
                if (((OpIRNode) instruction).getOperator() == OperatorList.PREFIXINC || ((OpIRNode) instruction).getOperator() == OperatorList.PREFIXDEC || ((OpIRNode) instruction).getOperator() == OperatorList.SUFFIXINC || ((OpIRNode) instruction).getOperator() == OperatorList.SUFFIXDEC) {
                    if (((OpIRNode) instruction).getOperator() == OperatorList.PREFIXINC)
                        preBody.add(new OpIRNode(OperatorList.ADD, ((OpIRNode) instruction).getDestIRRegister(), 1));
                    if (((OpIRNode) instruction).getOperator() == OperatorList.SUFFIXINC)
                        sufBody.add(new OpIRNode(OperatorList.ADD, ((OpIRNode) instruction).getDestIRRegister(), 1));
                    if (((OpIRNode) instruction).getOperator() == OperatorList.PREFIXDEC)
                        preBody.add(new OpIRNode(OperatorList.SUB, ((OpIRNode) instruction).getDestIRRegister(), 1));
                    if (((OpIRNode) instruction).getOperator() == OperatorList.SUFFIXDEC)
                        sufBody.add(new OpIRNode(OperatorList.SUB, ((OpIRNode) instruction).getDestIRRegister(), 1));
                } else midBody.add(instruction);
            } else midBody.add(instruction);
        body.addAll(preBody);
        body.addAll(midBody);
        body.addAll(sufBody);
        return new FunctionIRNode(body, null);
    }

    @Override
    public FunctionIRNode visitArrayExpressionNode(ArrayExpressionNode node) {
        FunctionIRNode address = visit(node.getAddress());
        List<BaseIRNode> body = new ArrayList<>(address.getBodyNode());
        address = loadMemory(address.getReturnRegister());
        body.addAll(address.getBodyNode());
        FunctionIRNode index = visit(node.getIndex());
        body.addAll(index.getBodyNode());
        index = loadMemory(index.getReturnRegister());
        body.addAll(index.getBodyNode());
        IRRegister destIRRegister = new IRRegister();
        body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, index.getReturnRegister()));
        body.add(new OpIRNode(OperatorList.MUL, destIRRegister, 8));
        body.add(new OpIRNode(OperatorList.ADD, destIRRegister, address.getReturnRegister()));
        destIRRegister.setType(node.getType());
        return new FunctionIRNode(body, destIRRegister);
    }

    @Override
    public FunctionIRNode visitBinaryExpressionNode(BinaryExpressionNode node) {
        FunctionIRNode leftExpression = visit(node.getLeftExpressionNode());
        List<BaseIRNode> body = new ArrayList<>(leftExpression.getBodyNode());
        FunctionIRNode rightExpression = visit(node.getRightExressionNode());
        if (node.getOperator() == OperatorList.ASSIGN) {
            body.addAll(rightExpression.getBodyNode());
            rightExpression = loadMemory(rightExpression.getReturnRegister());
            body.addAll(rightExpression.getBodyNode());
            if (leftExpression.getReturnRegister().getType() instanceof AddressType)
                body.add(new OpIRNode(OperatorList.STORE, leftExpression.getReturnRegister(), rightExpression.getReturnRegister()));
            else body.add(new OpIRNode(OperatorList.ASSIGN, leftExpression.getReturnRegister(), rightExpression.getReturnRegister()));
            return new FunctionIRNode(body, leftExpression.getReturnRegister());
        } else {
            leftExpression = loadMemory(leftExpression.getReturnRegister());
            body.addAll(leftExpression.getBodyNode());
            IRRegister destIRRegister = new IRRegister();
            destIRRegister.setType(node.getType());
            if (node.getOperator() == OperatorList.LOGICALAND || node.getOperator() == OperatorList.LOGICALOR) {
                LabelIRNode continueLabel = new LabelIRNode();
                LabelIRNode breakLabel = new LabelIRNode();
                LabelIRNode endLabel = new LabelIRNode();
                if (node.getOperator() == OperatorList.AND) body.add(new BranchIRNode(leftExpression.getReturnRegister(), continueLabel, breakLabel));
                else body.add(new BranchIRNode(leftExpression.getReturnRegister(), breakLabel, continueLabel));
                body.add(continueLabel);
                body.addAll(rightExpression.getBodyNode());
                rightExpression = loadMemory(rightExpression.getReturnRegister());
                body.addAll(rightExpression.getBodyNode());
                body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, leftExpression.getReturnRegister()));
                if (node.getOperator() == OperatorList.AND) body.add(new OpIRNode(OperatorList.AND, destIRRegister, rightExpression.getReturnRegister()));
                else body.add(new OpIRNode(OperatorList.OR, destIRRegister, rightExpression.getReturnRegister()));
                body.add(new JumpIRNode(endLabel));
                body.add(breakLabel);
                body.add(new OpIRNode(OperatorList.ADD, destIRRegister, node.getOperator() == OperatorList.OR ? 1 : 0));
                body.add(new JumpIRNode(endLabel));
                body.add(endLabel);
            }
            if (node.getOperator() == OperatorList.LT || node.getOperator() == OperatorList.GT || node.getOperator() == OperatorList.LEQ || node.getOperator() == OperatorList.GEQ || node.getOperator() == OperatorList.EQUAL || node.getOperator() == OperatorList.NOTEQUAL) {
                body.addAll(rightExpression.getBodyNode());
                rightExpression = loadMemory(rightExpression.getReturnRegister());
                body.addAll(rightExpression.getBodyNode());
                if (leftExpression.getReturnRegister().getType() instanceof ClassType) {
                    List<IRRegister> parameterIRRegister = new ArrayList<>();
                    parameterIRRegister.add(leftExpression.getReturnRegister());
                    parameterIRRegister.add(rightExpression.getReturnRegister());
                    FunctionEntity functionEntity = null;
                    if (node.getOperator() == OperatorList.LT) functionEntity = globalEntity.getFunctionEntity("string_lt");
                    if (node.getOperator() == OperatorList.LEQ) functionEntity = globalEntity.getFunctionEntity("string_leq");
                    if (node.getOperator() == OperatorList.GT) functionEntity = globalEntity.getFunctionEntity("string_gt");
                    if (node.getOperator() == OperatorList.GEQ) functionEntity = globalEntity.getFunctionEntity("string_geq");
                    if (node.getOperator() == OperatorList.EQUAL) functionEntity = globalEntity.getFunctionEntity("string_equal");
                    if (node.getOperator() == OperatorList.NOTEQUAL) functionEntity = globalEntity.getFunctionEntity("string_notequal");
                    assert functionEntity != null;
                    body.add(new CallIRNode(functionEntity.getEntryLabel(), destIRRegister, parameterIRRegister));
                } else body.add(new CmpIRNode(node.getOperator(), destIRRegister, leftExpression.getReturnRegister(), rightExpression.getReturnRegister()));
            }
            if (node.getOperator() == OperatorList.ADD || node.getOperator() == OperatorList.SUB || node.getOperator() == OperatorList.MUL || node.getOperator() == OperatorList.DIV || node.getOperator() == OperatorList.MOD || node.getOperator() == OperatorList.LEFTSHIFT || node.getOperator() == OperatorList.RIGHTSHIFT || node.getOperator() == OperatorList.AND || node.getOperator() == OperatorList.OR || node.getOperator() == OperatorList.XOR) {
                if (node.getOperator() == OperatorList.ADD && leftExpression.getReturnRegister().getType() instanceof ClassType) {
                    body.addAll(rightExpression.getBodyNode());
                    rightExpression = loadMemory(rightExpression.getReturnRegister());
                    body.addAll(rightExpression.getBodyNode());
                    List<IRRegister> parameterIRRegister = new ArrayList<>();
                    parameterIRRegister.add(leftExpression.getReturnRegister());
                    parameterIRRegister.add(rightExpression.getReturnRegister());
                    body.add(new CallIRNode(globalEntity.getFunctionEntity("string_concat").getEntryLabel(), destIRRegister, parameterIRRegister));
                } else {
                    body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, leftExpression.getReturnRegister()));
                    body.addAll(rightExpression.getBodyNode());
                    rightExpression = loadMemory(rightExpression.getReturnRegister());
                    body.addAll(rightExpression.getBodyNode());
                    body.add(new OpIRNode(node.getOperator(), destIRRegister, rightExpression.getReturnRegister()));
                }
            }
            return new FunctionIRNode(body, destIRRegister);
        }
    }

    @Override
    public FunctionIRNode visitCreatorExpressionNode(CreatorExpressionNode node) {
        List<BaseIRNode> body = new ArrayList<>();
        IRRegister destIRRegister = new IRRegister();
        BaseType type = node.getTypeNode().getType();
        if (node.getFixedDimension().size() == 0 && type instanceof ClassType) {
            assert node.getRestDimension() == 0;
            String className = ((ClassType) type).getClassName();
            body.add(new OpIRNode(OperatorList.MALLOC, destIRRegister, globalEntity.getClassEntity(className).getSize() * 8));
            if (globalEntity.getClassEntity(className).hasFunctionEntity(className)) {
                FunctionEntity constructorEntity = globalEntity.getClassEntity(className).getFunctionEntity(className);
                List<IRRegister> parameterIRRegister = new ArrayList<>();
                parameterIRRegister.add(destIRRegister);
                body.add(new CallIRNode(constructorEntity.getEntryLabel(), new IRRegister(), parameterIRRegister));
            }
        } else {
            FunctionIRNode dimension = visit(node.getFixedDimension().get(0));
            body.addAll(dimension.getBodyNode());
            dimension = loadMemory(dimension.getReturnRegister());
            body.addAll(dimension.getBodyNode());
            IRRegister lengthIRRegister = new IRRegister();
            body.add(new OpIRNode(OperatorList.ASSIGN, lengthIRRegister, dimension.getReturnRegister()));
            body.add(new OpIRNode(OperatorList.ADD, lengthIRRegister, 1));
            body.add(new OpIRNode(OperatorList.MUL, lengthIRRegister, 8));
            body.add(new OpIRNode(OperatorList.MALLOC, destIRRegister, lengthIRRegister));
            body.add(new OpIRNode(OperatorList.STORE, destIRRegister, dimension.getReturnRegister()));
            body.add(new OpIRNode(OperatorList.ADD, destIRRegister, 8));
            if (node.getFixedDimension().size() > 1 || (type instanceof ClassType && node.getRestDimension() == 0)) {
                List<BaseExpressionNode> fixedDimension = new ArrayList<>();
                for (int i = 1; i < node.getFixedDimension().size(); i++)
                    fixedDimension.add(node.getFixedDimension().get(i));
                CreatorExpressionNode baseCreatorExpression = new CreatorExpressionNode(node.getTypeNode(), fixedDimension, node.getRestDimension(), (Position) null);
                FunctionIRNode baseCreator = visit(baseCreatorExpression);
                IRRegister iteratorIRRegister = new IRRegister();
                body.add(new OpIRNode(OperatorList.ASSIGN, iteratorIRRegister, 0));
                LabelIRNode conditionLabel = new LabelIRNode();
                LabelIRNode continueLabel = new LabelIRNode();
                LabelIRNode breakLabel = new LabelIRNode();
                body.add(conditionLabel);
                IRRegister conditionIRRegister = new IRRegister();
                body.add(new CmpIRNode(OperatorList.LT, conditionIRRegister, iteratorIRRegister, dimension.getReturnRegister()));
                body.add(new BranchIRNode(conditionIRRegister, continueLabel, breakLabel));
                body.add(continueLabel);
                body.addAll(baseCreator.getBodyNode());
                IRRegister addressIRRegister = new IRRegister();
                body.add(new OpIRNode(OperatorList.ASSIGN, addressIRRegister, iteratorIRRegister));
                body.add(new OpIRNode(OperatorList.MUL, addressIRRegister, 8));
                body.add(new OpIRNode(OperatorList.ADD, addressIRRegister, destIRRegister));
                body.add(new OpIRNode(OperatorList.STORE, addressIRRegister, baseCreator.getReturnRegister()));
                body.add(new OpIRNode(OperatorList.ADD, iteratorIRRegister, 1));
                body.add(new JumpIRNode(conditionLabel));
                body.add(breakLabel);
            }
        }
        destIRRegister.setType(node.getType());
        return new FunctionIRNode(body, destIRRegister);
    }

    @Override
    public FunctionIRNode visitDotExpressionNode(DotExpressionNode node) {
        IRRegister destIRRegister = new IRRegister();
        FunctionIRNode object = visit(node.getObjectExpressionNode());
        List<BaseIRNode> body = new ArrayList<>(object.getBodyNode());
        object = loadMemory(object.getReturnRegister());
        body.addAll(object.getBodyNode());
        body.add(new OpIRNode(OperatorList.ADD, destIRRegister, object.getReturnRegister()));
        ClassType classType = (ClassType) object.getReturnRegister().getType();
        String member = node.getIdentifierExpressionNode().getIdentifierName();
        ClassEntity classEntity = globalEntity.getClassEntity(classType.getClassName());
        int index = classEntity.getIndex(member);
        body.add(new OpIRNode(OperatorList.ADD, destIRRegister, index * 8));
        destIRRegister.setType(node.getType());
        return new FunctionIRNode(body, destIRRegister);
    }

    @Override
    public FunctionIRNode visitFunctionExpressionNode(FunctionExpressionNode node) {
        List<BaseIRNode> body = new ArrayList<>();
        IRRegister destIRRegister = new IRRegister();
        FunctionEntity functionEntity = node.getEntity();
        List<IRRegister> parameterIRRegisters = new ArrayList<>();
        if (node.getfunctionNode() instanceof DotExpressionNode) {
            FunctionIRNode objectNode = visit(((DotExpressionNode) node.getfunctionNode()).getObjectExpressionNode());
            body.addAll(objectNode.getBodyNode());
            objectNode = loadMemory(objectNode.getReturnRegister());
            body.addAll(objectNode.getBodyNode());
            parameterIRRegisters.add(objectNode.getReturnRegister());
        }
        for (int i = 0; i < node.getArguments().size(); i++) {
            FunctionIRNode argumentNode = visit(node.getArguments().get(i));
            body.addAll(argumentNode.getBodyNode());
            argumentNode = loadMemory(argumentNode.getReturnRegister());
            body.addAll(argumentNode.getBodyNode());
            parameterIRRegisters.add(argumentNode.getReturnRegister());
        }
        destIRRegister.setType(node.getType());
        body.add(new CallIRNode(functionEntity.getEntryLabel(), destIRRegister, parameterIRRegisters));
        return new FunctionIRNode(body, destIRRegister);
    }

    @Override
    public FunctionIRNode visitIdentifierExpressionNode(IdentifierExpressionNode node) {
        BaseEntity entity = currentEntity;
        FunctionEntity functionEntity = null;
        while (entity != null) {
            if (entity instanceof BlockEntity) {
                VariableEntity variableEntity = ((BlockEntity) entity).get(node.getIdentifierName());
                if (variableEntity != null)
                    if (variableEntity.getPosition().above(node.getPosition()))
                        return new FunctionIRNode(new ArrayList<>(), variableEntity.getIRRegister());
            }
            if (entity instanceof FunctionEntity) {
                functionEntity = (FunctionEntity) entity;
                VariableEntity variableEntity = ((FunctionEntity) entity).get(node.getIdentifierName());
                if (variableEntity != null)
                    if (variableEntity.getPosition().above(node.getPosition()))
                        return new FunctionIRNode(new ArrayList<>(), variableEntity.getIRRegister());
            }
            if (entity instanceof ClassEntity) {
                if (entity == globalEntity) {
                    VariableEntity variableEntity = ((ClassEntity) entity).getVariableEntity(node.getIdentifierName());
                    if (variableEntity != null)
                        if (variableEntity.getPosition().above(node.getPosition()))
                            return new FunctionIRNode(new ArrayList<>(), variableEntity.getIRRegister());
                } else {
                    VariableEntity variableEntity = ((ClassEntity) entity).getVariableEntity(node.getIdentifierName());
                    if (variableEntity != null) {
                        assert functionEntity != null;
                        IRRegister thisIRRegister = functionEntity.getParameterList().get(0).getIRRegister();
                        List<BaseIRNode> body = new ArrayList<>();
                        int index = ((ClassEntity) entity).getIndex(node.getIdentifierName());
                        IRRegister indexIRRegister = new IRRegister();
                        body.add(new OpIRNode(OperatorList.ASSIGN, indexIRRegister, index));
                        IRRegister destIRRegister = new IRRegister();
                        body.add(new OpIRNode(OperatorList.ADD, destIRRegister, thisIRRegister));
                        body.add(new OpIRNode(OperatorList.ADD, destIRRegister, indexIRRegister));
                        if (variableEntity.getType() instanceof PrimaryType)
                            body.add(new OpIRNode(OperatorList.MEMORY, destIRRegister, destIRRegister));
                        destIRRegister.setType(variableEntity.getType());
                        return new FunctionIRNode(body, destIRRegister);
                    }
                }
            }
            entity = entity.getParentEntity();
        }
        return null;
    }

    @Override
    public FunctionIRNode visitLiteralExpressionNode(LiteralExpressionNode node) {
        List<BaseIRNode> body = new ArrayList<>();
        IRRegister destIRRegister = new IRRegister();
        if (node.getType() instanceof ClassType) {
            body.add(new OpIRNode(OperatorList.MALLOC, destIRRegister, node.getValue().length() + 9));
            body.add(new OpIRNode(OperatorList.STORE, destIRRegister, node.getValue().length()));
            body.add(new OpIRNode(OperatorList.ADD, destIRRegister, 8));
            int value = 0;
            for (int i = 0; i < node.getValue().length() + 1; i++) {
                char character = i == node.getValue().length() ? 0 : node.getValue().charAt(i);
                int index = i / 4;
                int offset = i % 4;
                if (offset == 0) value = 0;
                if (offset == 0) value += character;
                if (offset == 1) value += character << 8;
                if (offset == 2) value += character << 16;
                if (offset == 3) value += character << 24;
                if (offset == 3 || i == node.getValue().length()) {
                    IRRegister addressIRRegister = new IRRegister();
                    body.add(new OpIRNode(OperatorList.ASSIGN, addressIRRegister, destIRRegister));
                    body.add(new OpIRNode(OperatorList.ADD, addressIRRegister, index * 4));
                    body.add(new OpIRNode(OperatorList.STORECHAR, addressIRRegister, value));
                }
            }
            destIRRegister.setType(new ClassType("string"));
        } else {
            assert node.getType() instanceof PrimaryType;
            if (((PrimaryType) node.getType()).getType() == PrimaryTypeList.BOOL) {
                if (node.getValue().equals("true")) body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, 1));
                else body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, 0));
            } else body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, Integer.parseInt(node.getValue())));
            destIRRegister.setType(new PrimaryType(PrimaryTypeList.INT));
        }
        return new FunctionIRNode(body, destIRRegister);
    }

    @Override
    public FunctionIRNode visitUnaryExpressionNode(UnaryExpressionNode node) {
        IRRegister destIRRegister = new IRRegister();
        FunctionIRNode expression = visit(node.getExpressionNode());
        List<BaseIRNode> body = new ArrayList<>(expression.getBodyNode());
        if (node.getOperator() == OperatorList.NEG) body.add(new OpIRNode(OperatorList.SUB, destIRRegister, expression.getReturnRegister()));
        if (node.getOperator() == OperatorList.NOT) {
            body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, 0xffff));
            body.add(new OpIRNode(OperatorList.XOR, destIRRegister, expression.getReturnRegister()));
        }
        if (node.getOperator() == OperatorList.LOGICALNOT) {
            body.add(new OpIRNode(OperatorList.ASSIGN, destIRRegister, 1));
            body.add(new OpIRNode(OperatorList.XOR, destIRRegister, expression.getReturnRegister()));
        }
        if (node.getOperator() == OperatorList.PREFIXINC || node.getOperator() == OperatorList.PREFIXDEC || node.getOperator() == OperatorList.SUFFIXINC || node.getOperator() == OperatorList.SUFFIXDEC)
            body.add(new OpIRNode(node.getOperator(), expression.getReturnRegister(), 1));
        return new FunctionIRNode(body, destIRRegister);
    }

}
