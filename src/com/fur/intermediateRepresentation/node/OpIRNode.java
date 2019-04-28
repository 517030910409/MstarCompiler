package com.fur.intermediateRepresentation.node;

import com.fur.enumerate.OperatorList;
import com.fur.intermediateRepresentation.IRRegister;
import com.fur.intermediateRepresentation.IntermediateRepresentationVisitor;

public class OpIRNode extends BaseIRNode {

    private OperatorList operator;
    private IRRegister destIRRegister, sourceIRRegister;
    private int immediate;

    public OpIRNode(OperatorList operator, IRRegister destIRRegister, IRRegister sourceIRRegister) {
        this.destIRRegister = destIRRegister;
        this.sourceIRRegister = sourceIRRegister;
    }

    public OpIRNode(OperatorList operator, IRRegister destIRRegister, int immediate) {
        this.destIRRegister = destIRRegister;
        this.immediate = immediate;
    }

    public OperatorList getOperator() {
        return operator;
    }

    public IRRegister getDestIRRegister() {
        return destIRRegister;
    }

    @Override
    public <T> T accept(IntermediateRepresentationVisitor<? extends T> visitor) {
        return visitor.visitOpIRNode(this);
    }
}
