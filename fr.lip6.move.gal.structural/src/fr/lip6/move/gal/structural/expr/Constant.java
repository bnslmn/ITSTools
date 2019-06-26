package fr.lip6.move.gal.structural.expr;

import android.util.SparseIntArray;

class Constant implements Expression {
	int value;

	public Constant(int value) {
		this.value = value;
	}

	@Override
	public int eval(SparseIntArray state) {
		return value;
	}

	@Override
	public <T> T accept(ExprVisitor<T> v) {
		return v.visit(this);
	}
}
