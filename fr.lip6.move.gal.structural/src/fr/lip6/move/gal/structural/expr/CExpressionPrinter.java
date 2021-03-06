package fr.lip6.move.gal.structural.expr;

import java.io.PrintWriter;

public class CExpressionPrinter implements ExprVisitor<Void> {

	private String prefix;
	protected PrintWriter pw;
	
	public CExpressionPrinter(PrintWriter pw, String prefix) {
		this.pw = pw;
		this.prefix = prefix;
	}

	public void close() {
		pw.close();
	}
	
	public void infix(BinOp binOp, String op) {
		pw.append("(");
		binOp.left.accept(this);
		pw.append(op);
		binOp.right.accept(this);
		pw.append(")");
	}

	@Override
	public Void visit(ArrayVarRef arrayVarRef) {
		throw new UnsupportedOperationException("Unexpected Array Ref in expression translated to C : "+ arrayVarRef);
	}

	@Override
	public Void visit(BinOp binOp) {
		switch (binOp.getOp()) {
		case AND : 
		{
			infix(binOp, "&&");
			break;
		}
		case OR :
		{
			infix(binOp, "||");
			break;
		}
		case NOT :
		{
			pw.append("!");
			binOp.left.accept(this);
			break;
		}
		case ADD :
		{
			infix(binOp, "+");
			break;
		}
		case EQ :
		{
			infix(binOp, "==");
			break;			
		}
		case NEQ :
		{
			infix(binOp, "!=");
			break;			
		}
		case LT :
		{
			infix(binOp, "<");
			break;			
		}
		case LEQ :
		{
			infix(binOp, "<=");
			break;			
		}
		case GEQ :
		{
			infix(binOp, ">=");
			break;			
		}
		case GT :
		{
			infix(binOp, ">");
			break;			
		}
		case DIV :
		{
			infix(binOp, "/");
			break;			
		}
		case MINUS :
		{
			infix(binOp, "-");
			break;			
		}
		case MOD :
		{
			infix(binOp, "%");
			break;			
		}
		case MULT :
		{
			infix(binOp, "*");
			break;			
		}
		default :
			throw new UnsupportedOperationException("Unexpected temporal operator in Boolean expression :"+binOp);
		}
		return null;
	}

	@Override
	public Void visit(Constant constant) {
		pw.append(Integer.toString(constant.getValue()));
		return null;
	}

	@Override
	public Void visit(NaryOp naryOp) {
		String symbol = null;
		switch (naryOp.getOp()) {
		case AND :
			symbol = "&&";
			break;
		case OR :
			symbol = "||";
			break;
		case ADD :
			symbol = "+";
			break;
		case MULT :
			symbol = "*";
			break;
		case ENABLED:case CARD:
			symbol = ",";
			break;
		default :
			throw new UnsupportedOperationException("Unexpected Nary operator in expression translated to C : " + naryOp);
		}
		pw.append("(");
		for (int i=0, ie = naryOp.nbChildren() ; i < ie ; i++) {
			Expression child = naryOp.childAt(i);
			child.accept(this);
			if (i < ie -1) {
				pw.append(symbol);
			}
		}
		pw.append(")");		
		return null;
	}

	@Override
	public Void visit(ParamRef paramRef) {
		throw new UnsupportedOperationException("Unexpected ParamRef in Boolean expression : "+paramRef);
	}

	@Override
	public Void visit(TransRef transRef) {
		pw.append("t"+transRef.getValue());
		return null;
//		throw new UnsupportedOperationException("Unexpected Transition Ref in expression translated to C :"+transRef);
	}

	@Override
	public Void visit(VarRef varRef) {
		pw.append(prefix).append("[").append(Integer.toString(varRef.getValue())).append("]");
		return null;
	}

	@Override
	public Void visitBool(BoolConstant boolConstant) {
		pw.append(boolConstant.getValue()!=0?"true":"false");
		return null;
	}

	@Override
	public Void visit(AtomicPropRef apRef) {
		pw.append(apRef.getAp().getName());
//		apRef.getAp().getExpression().accept(this);
		return null;
	}
	
	
}
