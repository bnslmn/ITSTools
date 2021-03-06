package fr.lip6.ltl.tgba;

import android.util.SparseBoolArray;
import fr.lip6.move.gal.structural.expr.Expression;

public class TGBAEdge {
	private Expression condition;
	private SparseBoolArray acceptance;
	private int src;
	private int dest;
	public TGBAEdge(int src, int dest, Expression condition, SparseBoolArray acceptance) {
		this.src = src;
		this.dest=dest;
		this.condition = condition;
		this.acceptance = acceptance;
	}
	@Override
	public String toString() {
		return "{ cond=" + condition + ", acceptance=" + acceptance + " source=" + src + " dest: "+ dest + "}";
	}
	
	public SparseBoolArray getAcceptance() {
		return acceptance;
	}
	
	public Expression getCondition() {
		return condition;
	}
	
	public int getDest() {
		return dest;
	}
	public int getSrc() {
		return src;
	}
}
