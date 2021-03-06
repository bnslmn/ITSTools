package fr.lip6.move.gal.gal2smt.bmc;

import org.smtlib.ICommand;
import org.smtlib.IExpr;
import org.smtlib.IExpr.IDeclaration;
import org.smtlib.IExpr.IFactory;
import org.smtlib.IExpr.ISymbol;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import org.smtlib.IPrinter;
import org.smtlib.IResponse;
import org.smtlib.IResponse.IValueResponse;
import org.smtlib.ISolver;
import org.smtlib.ISort;
import org.smtlib.ISort.IApplication;
import org.smtlib.IVisitor;
import org.smtlib.IVisitor.VisitorException;
import org.smtlib.Utils;
import org.smtlib.impl.Script;
import org.smtlib.impl.Sort;
import org.smtlib.sexpr.ISexpr;
import org.smtlib.sexpr.ISexpr.ISeq;
import org.smtlib.sexpr.Printer;
import org.smtlib.SMT.Configuration;
import org.smtlib.command.C_assert;
import org.smtlib.command.C_define_fun;
import org.smtlib.command.C_get_value;

import fr.lip6.move.gal.InvariantProp;
import fr.lip6.move.gal.Property;
import fr.lip6.move.gal.SafetyProp;
import fr.lip6.move.gal.gal2smt.Result;
import fr.lip6.move.gal.gal2smt.Solver;
import fr.lip6.move.gal.gal2smt.smt.IBMCSolver;
import fr.lip6.move.gal.gal2smt.tosmt.GalExpressionTranslator;
import fr.lip6.move.gal.gal2smt.tosmt.NextTranslator;
import fr.lip6.move.gal.gal2smt.tosmt.QualifiedExpressionTranslator;
import fr.lip6.move.gal.semantics.IDeterministicNextBuilder;
import fr.lip6.move.gal.semantics.INext;

public class NextBMCSolver implements IBMCSolver {

	private static final String STATE = "s";
	protected static final String NEXT = "_next__";
	protected static final String ENABLED = "_enabled__";
	protected static final String ENABLEDSRC = "_enabledsrc__";
	public static final String TRANSNAME = "tr";
	public static final String TRANSSRC = "trsrc";
	protected final Solver engine;
	protected final Configuration conf;
	protected ISolver solver;
	protected final IFactory efactory;
	protected final ISort.IFactory sortfactory ;
	protected int depth = 0;

	protected IDeterministicNextBuilder nb;
	private boolean withAllDiff;
	private boolean shouldShow=false;

	public NextBMCSolver(Configuration smtConfig, Solver engine, boolean withAllDiff) {
		this.conf = smtConfig;
		this.efactory = smtConfig.exprFactory;
		this.sortfactory = smtConfig.sortFactory;
		this.engine = engine;
		this.withAllDiff = withAllDiff;
	}

	@Override
	public void init(IDeterministicNextBuilder spec) {
		Script script = new Script() ;

		declareState(script);

		this.nb = spec;

		declareTransitions(nb.getDeterministicNext(), script);

		//		for (ICommand c : script.commands()) {
		//		System.out.println(c);
		//	}

		startSolver();

		IResponse err = script.execute(solver);
		if (err.isError()) {
			throw new RuntimeException("Error when declaring system variables to SMT solver."+conf.defaultPrinter.toString(err));
		}
	}
	private boolean solverStarted = false;
	public void startSolver() {
		if (! solverStarted) {
			solver = engine.getSolver(conf);
			// start the solver
			IResponse err = solver.start();
			if (err.isError()) {
				throw new RuntimeException("Could not start solver "+ engine+" from path "+ conf.executable + " raised error :"+err);
			}

			// Logic + options
			if (shouldShow) {
				err = solver.set_option(efactory.keyword(Utils.PRODUCE_MODELS), efactory.symbol("true"));
				if (err.isError()) {
					throw new RuntimeException("Could not set :produce-models option :" + err);
				}
			}
			err = solver.set_logic("QF_AUFLIA", null);
			if (err.isError()) {
				throw new RuntimeException("Could not set logic :"+err);
			}
			solverStarted = true;
		}
	}

	private void declareState(Script script) {
		// integer sort
		IApplication ints = sortfactory.createSortExpression(efactory.symbol("Int"));
		// an array, indexed by integers, containing integers : (Array Int Int) 
		IApplication arraySort = sortfactory.createSortExpression(efactory.symbol("Array"), ints, ints);
		// an array, indexed by ints of such arrays : (Array Int (Array Int Int)) 
		IApplication arrayArraySort = sortfactory.createSortExpression(efactory.symbol("Array"), ints, arraySort);

		// declare state variable : a big array of arrays of integer
		script.add(
				new org.smtlib.command.C_declare_fun(
						efactory.symbol(STATE),
						Collections.emptyList(),
						arrayArraySort								
						)
				);
	}


	protected void declareTransitions(List<List<INext>> list, Script script) {
		if (list.isEmpty()) {
			return;
		}
		// add transition calls to build a global NEXT as OR of the various transitions
		final List<IExpr> trs = new ArrayList<IExpr>();		

		final GalExpressionTranslator et = new GalExpressionTranslator(conf);

		IApplication ints = sortfactory.createSortExpression(efactory.symbol("Int"));
		// an array, indexed by integers, containing integers : (Array Int Int) 
		IApplication arraySort = sortfactory.createSortExpression(efactory.symbol("Array"), ints, ints);
		// parameter time step for the shorthand versions that use it
		ISymbol sstep = efactory.symbol("step");

		// unique index for each independent sequence of transition relation
		int tindex = 0;
		// manual iteration over the results of determinize
		for (Iterator<List<INext>> seqit = list.iterator() ; seqit.hasNext() ; /*NOP*/ ) {
			List<INext> seq = seqit.next();

			ISymbol state = efactory.symbol("src");
			// The current state : state[step]
			IExpr cur = state;

			// do the translation as a Visit of INext
			NextTranslator translator = new NextTranslator(cur, et);

			// To hold all constraints corresponding to this transition
			List<IExpr> conds = new ArrayList<IExpr>();			

			for (INext statement : seq) {
				IExpr cond = statement.accept(translator);
				// the visit returns a new condition (Predicate case) or updates its state to reflect assignment
				if (cond != null)
					conds.add(cond);
			}

			// build up the full boolean function for the transition
			IExpr bodyExpr = efactory.fcn(efactory.symbol("and"), conds);
			if (conds.size() == 1) {
				bodyExpr = conds.get(0);
			} else if (conds.isEmpty()) {
				bodyExpr = efactory.symbol("true");
			}

			// enabling in a given state: enabledsrcXX ( int [] state ) : bool
			ISymbol enabsrcname = efactory.symbol(ENABLEDSRC+ tindex);
			C_define_fun enabsrctr = new org.smtlib.command.C_define_fun(
					enabsrcname,    // name
					Collections.singletonList(efactory.declaration(state, arraySort)), // param (int [] state) 
					Sort.Bool(), // return type
					bodyExpr); // actions : assertions over S[step] and S[step+1]
			script.commands().add(enabsrctr);


			// define a boolean function with single parameter (step) for each transition

			bodyExpr = efactory.fcn(efactory.symbol(ENABLEDSRC+tindex), accessStateAt(sstep));

			// enabling at a given time step : enabledXX ( int step ) : bool
			// implemented as : enabledSrcXX ( state [ step ] ); 
			ISymbol enabname = efactory.symbol(ENABLED+ tindex);
			C_define_fun enabtr = new org.smtlib.command.C_define_fun(
					enabname,    // name
					Collections.singletonList(efactory.declaration(sstep, ints)), // param (int step) 
					Sort.Bool(), // return type
					bodyExpr); // actions : assertions over S[step] and S[step+1]
			script.commands().add(enabtr);


			// declare the transition : trSrcXX (int [] src, int [] dst) : bool
			// implemented as : enabledSrcXX ( src ) AND dst = image(src)
			ISymbol fsrcname = efactory.symbol(TRANSSRC+ tindex);
			ISymbol src = efactory.symbol("src");
			ISymbol dst = efactory.symbol("dst");
			List<IDeclaration> args = new ArrayList<>();
			args.add(efactory.declaration(src, arraySort));
			args.add(efactory.declaration(dst, arraySort));

			// finish the update by asserting that the store result is equals to dst			
			bodyExpr = efactory.fcn(efactory.symbol("and"),
					// enabledSrcXX ( src ) 
					efactory.fcn(enabsrcname, src), 
					// dst = image(src)
					efactory.fcn(efactory.symbol("="), translator.getState(), dst));

			C_define_fun defsrctr = new org.smtlib.command.C_define_fun(
					fsrcname,    // name
					args, // param (int [] src, int [] dst) 
					Sort.Bool(), // return type
					bodyExpr); // actions : assertions over S[step] and S[step+1]
			script.commands().add(defsrctr);


			// declare the transition : trXX (int step) : bool
			// implemented as : trSrcXX ( state[step], state[step+1] ) 
			ISymbol fname = efactory.symbol(TRANSNAME+ tindex);

			// Enforce update of state a step+1
			IExpr snext = efactory.fcn(efactory.symbol("+"),sstep,efactory.numeral("1"));
			// The current state : state[step]
			IExpr scur = accessStateAt(sstep);
			IExpr next = accessStateAt(snext);
			// finish the update by asserting that the store result is equal to state at step+1			
			bodyExpr = efactory.fcn(fsrcname, scur, next); 

			C_define_fun deftr = new org.smtlib.command.C_define_fun(
					fname,    // name
					Collections.singletonList(efactory.declaration(sstep, ints)), // param (int step) 
					Sort.Bool(), // return type
					bodyExpr); // actions : assertions over S[step] and S[step+1]
			script.commands().add(deftr);
			// add it to the components of NEXT
			trs.add(efactory.fcn(fname, sstep));

			visitTransition(seq,tindex);

			tindex++;
		}

		// One function to hold them all, and in the darkness bind them 
		C_define_fun nextR = new org.smtlib.command.C_define_fun(
				efactory.symbol(NEXT),    // name
				Collections.singletonList(efactory.declaration(sstep, ints)), // param (int step) 
				Sort.Bool(), // return type
				efactory.fcn(efactory.symbol("or"), trs)); // actions : OR of all transitions declared
		script.commands().add(nextR);


	}


	/**
	 * Allow subclasses to introduce additional handling of each event/alternative execution of a transition.
	 * This allows to only determinize once and act on the fly on the produced stream.
	 * Design for extension by inheritance principle : body is empty here.
	 * @param seq a sequence of Assignments and Predicates
	 * @param tindex the index of this event
	 */
	protected void visitTransition(List<INext> seq, int tindex) {

	}


	@Override
	public void exit() {
		IResponse res = solver.exit();
		if (res.isError()) {
			Logger.getLogger("fr.lip6.move.gal").info("SMT solver already quit.");
		}
		solverStarted = false;
	}

	int retry = 0;
	@Override
	public Result checkSat() {
		IResponse res = solver.check_sat();
		if (res.isError()) {
			throw new RuntimeException("SMT solver raised an exception or timeout :" + conf.defaultPrinter.toString(res));
		}
		IPrinter printer = conf.defaultPrinter;
		//	System.out.println(printer.toString(script));
		String textReply = printer.toString(res);
		if ("unknown".equals(textReply) && retry==0) {
			retry++;
			Logger.getLogger("fr.lip6.move.gal").warning("SMT solver unexpectedly returned 'unknown' answer, retrying.");
			Result r = checkSat();
			retry--;
			return r;
		}
		//	System.out.println(printer.toString(res));
		if ("sat".equals(textReply)) {
			if (shouldShow) {
				printModel();
			}
			return Result.SAT;
		} else if ("unsat".equals(textReply)) {
			return Result.UNSAT;
		} else {
			throw new RuntimeException("SMT solver raised an error :" + textReply);
		}
	}

	private void printModel() {
		if (shouldShow) {
			ICommand getVals = new C_get_value(Collections.singletonList(accessStateAt(0))); 
			IResponse state = getVals.execute(solver);
			//		if (state.isOK()) {
			StringWriter w = new StringWriter();
			Printer printer = new Printer(w) {
				final IExpr zero = efactory.numeral(0);
				@Override
				public Void visit(ISeq e)
						throws org.smtlib.IVisitor.VisitorException {
					if (e.sexprs().size() == 2 && e.sexprs().get(1).equals(zero)) {
						return null;
					}
					try {
						w.append("(");
						for (ISexpr expr: e.sexprs()) { 
							expr.accept(this); 
						} 
						w.append(" )");
					} catch (IOException ex) { throw new IVisitor.VisitorException(ex); }
					return null;
				}
				@Override
				public Void visit(IValueResponse e)
						throws org.smtlib.IVisitor.VisitorException {
					try {
						w.append("(");
						for (IResponse.IPair<IExpr,IExpr> p : e.values()) {
							if (! p.second().equals(zero)) {
								w.append("(");
								p.first().accept(this);
								w.append(" ");
								p.second().accept(this);
								w.append(")");
							}
						}
						w.append(")");
					} catch (IOException ex) {
						throw new IVisitor.VisitorException(ex);
					}
					return null;
				}
			};
			try {
				state.accept(printer);
			} catch (VisitorException e1) {
				e1.printStackTrace();
			}
			Logger.getLogger("fr.lip6.move.gal").info("SAT in state (no zeros shown ) :" + w.toString() );// TODO Auto-generated method stub
		}


	}

	@Override
	public void setShowSatState(boolean shouldShow) {
		this.shouldShow = shouldShow;
	}

	@Override
	public int getDepth() {
		return depth;
	}

	@Override
	public void incrementDepth() {
		C_assert nexti = new C_assert(efactory.fcn(efactory.symbol(NEXT),efactory.numeral(depth)));
		//System.out.println(nexti);
		IResponse res = nexti.execute(solver);
		if (res.isError()) {
			throw new RuntimeException("Assertion failed : SMT solver produced unexpected response "+res);
		}

		depth++;

		if (withAllDiff) {
			IExpr cur = accessStateAt(depth);
			for (int i = 0 ; i < depth ; i++ ) {
				res = solver.assertExpr(
						efactory.fcn( efactory.symbol("not"), 
								efactory.fcn( efactory.symbol("="),
										cur, 
										accessStateAt(i))));
				if (res.isError()) {
					throw new RuntimeException("Assertion failed : SMT solver produced unexpected response "+res);
				}
			}
		}
	}

	@Override
	public Result verify(Property prop) {
		if (prop.getBody() instanceof SafetyProp) {
			SafetyProp sbody = (SafetyProp) prop.getBody();

			QualifiedExpressionTranslator qet = new QualifiedExpressionTranslator(conf);
			qet.setNb(nb);
			List<IExpr> alts = new ArrayList<>();
			for (int i=0; i <= depth ; i++) {
				IExpr state = accessStateAt(depth);
				IExpr sprop = qet.translateBool(sbody.getPredicate(), state);
				if (sbody instanceof InvariantProp) {
					sprop = efactory.fcn(efactory.symbol("not"), sprop);
				}
				alts.add(sprop);
			}
			IExpr tocheck;
			if (alts.size() == 1) {
				tocheck = alts.get(0);
			} else {
				tocheck= efactory.fcn(efactory.symbol("or"), alts);
			}
			return verifyAssertion(tocheck);
		} else {
			Logger.getLogger("fr.lip6.move.gal").warning("Only safety properties are handled in SMT solution currently. Cannot handle " + prop.getName());
			return Result.UNKNOWN;
		}
	}


	public Result verifyAssertion(IExpr sprop) {
		IResponse res = solver.push(1);
		if (res.isError()) {
			throw new RuntimeException("Assertion failed : SMT 'push' solver produced unexpected response "+res);
		}
		res = solver.assertExpr(sprop);
		if (res.isError()) {
			throw new RuntimeException("Assertion failed : SMT solver produced unexpected response "+res);
		}
		Result result = checkSat();
		if (result == Result.SAT) {
			onSat(solver);
		}
		res = solver.pop(1);
		if (res.isError()) {
			throw new RuntimeException("Assertion failed : SMT pop produced unexpected response "+res);
		}
		return result;
	}

	protected void onSat(ISolver solver) {
		// TODO
	}

	protected IExpr accessStateAt (int step) {
		return accessStateAt(efactory.numeral(step));
	}

	protected IExpr accessStateAt (IExpr step) {
		ISymbol select = efactory.symbol("select");		
		return efactory.fcn(select, efactory.symbol(STATE), step); 
	}

	@Override
	public void assertInitialState() {
		Script script = new Script();
		ISymbol select = efactory.symbol("select");

		int index = 0;		
		IExpr initial = accessStateAt(0); 
		for (int val : nb.getInitial()) {
			script.commands().add(
					new C_assert(
							efactory.fcn(efactory.symbol("="), 					
									// access initial [index]
									efactory.fcn(select, initial, efactory.numeral(index)),
									// it has value val
									efactory.numeral(val)
									)
							));
			index++;
		}
		IResponse res = script.execute(solver);
		if (res.isError()) {
			throw new RuntimeException("Script setting initial state failed : SMT solver produced unexpected response "+res);
		}
	}

}
