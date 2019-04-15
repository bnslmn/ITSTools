package fr.lip6.move.gal.gal2smt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.smtlib.ICommand;
import org.smtlib.IExpr;
import org.smtlib.IPrinter;
import org.smtlib.IExpr.IFactory;
import org.smtlib.IExpr.INumeral;
import org.smtlib.IExpr.ISymbol;
import org.smtlib.IResponse;
import org.smtlib.ISolver;
import org.smtlib.SMT;
import org.smtlib.Utils;
import org.smtlib.command.C_assert;
import org.smtlib.command.C_get_value;
import org.smtlib.impl.Script;
import org.smtlib.sexpr.ISexpr;
import org.smtlib.sexpr.ISexpr.ISeq;

import android.util.SparseIntArray;
import fr.lip6.move.gal.structural.InvariantCalculator;
import fr.lip6.move.gal.structural.StructuralReduction;
import fr.lip6.move.gal.util.MatrixCol;

public class DeadlockTester {

	/**
	 * Unsat answer means no deadlocks, SAT means nothing, as we are working with an overapprox.
	 * @param sr
	 * @param solverPath
	 * @param isSafe 
	 * @return
	 */
	public static String testDeadlocksWithSMT(StructuralReduction sr, String solverPath, boolean isSafe) {
		List<String> tnames = new ArrayList<>();
		MatrixCol sumMatrix = computeReducedFlow(sr, tnames);

		long time = System.currentTimeMillis();
		Set<SparseIntArray> invar = InvariantCalculator.computePInvariants(sumMatrix, sr.getPnames());		
		//InvariantCalculator.printInvariant(invar, sr.getPnames(), sr.getMarks());
		Logger.getLogger("fr.lip6.move.gal").info("Computed "+invar.size()+" place invariants in "+ (System.currentTimeMillis()-time) +" ms");

		org.smtlib.SMT smt = new SMT();

		ISolver solver = initSolver(solverPath, smt);

		{
			// STEP 1 : declare variables, assert net is dead.
			time = System.currentTimeMillis();
			Script varScript = declareVariables(sr.getPnames().size(), "s", isSafe, smt);
			execAndCheckResult(varScript, solver);
			Script scriptAssertDead = assertNetIsDead(sr, smt);
			execAndCheckResult(scriptAssertDead, solver);
		}

		// STEP 2 : declare and assert invariants 
		String textReply = assertInvariants(invar, sr, solver, smt,true);

		// are we finished ?
		if (textReply.equals("unsat")) {
			solver.exit();
			return textReply;
		}

		// STEP 3 : go heavy, use the state equation to refine our solution
		time = System.currentTimeMillis();
		Logger.getLogger("fr.lip6.move.gal").info("Adding state equation constraints to refine reachable states.");
		Script script = declareStateEquation(sumMatrix, sr, smt);
		
		execAndCheckResult(script, solver);
		textReply = checkSat(solver, smt);
		Logger.getLogger("fr.lip6.move.gal").info("Absence of deadlock check using state equation in "+ (System.currentTimeMillis()-time) +" ms returned " + textReply);

		IFactory ef2 = smt.smtConfig.exprFactory;

		if (textReply.equals("sat")) {			
			queryState(ef2, sr, solver);
			queryParikh(ef2, tnames, solver);
		}
		solver.exit();
		return textReply;
	}

	private static void execAndCheckResult(Script script, ISolver solver) {
		IResponse res = script.execute(solver);
		if (res.isError()) {
			throw new RuntimeException("SMT solver raised an error when submitting script. Raised " + res.toString());
		}
	}
	
	
	public static List<Integer> testImplicitWithSMT(StructuralReduction sr, String solverPath, boolean isSafe, boolean withStateEquation) {
		List<String> tnames = new ArrayList<>();
		MatrixCol sumMatrix = computeReducedFlow(sr, tnames);

		long time = System.currentTimeMillis();
		Set<SparseIntArray> invar = InvariantCalculator.computePInvariants(sumMatrix, sr.getPnames());		
		//InvariantCalculator.printInvariant(invar, sr.getPnames(), sr.getMarks());
		Logger.getLogger("fr.lip6.move.gal").info("Computed "+invar.size()+" place invariants in "+ (System.currentTimeMillis()-time) +" ms");

		org.smtlib.SMT smt = new SMT();

		ISolver solver = initSolver(solverPath, smt);

		{
			// STEP 1 : declare variables
			time = System.currentTimeMillis();
			Script varScript = declareVariables(sr.getPnames().size(), "s", isSafe, smt);
			execAndCheckResult(varScript, solver);			
		}
		
		// STEP 2 : declare and assert invariants 
		String textReply = assertInvariants(invar, sr, solver, smt,false);
		
		// are we finished ?
		if (withStateEquation) {
			// STEP 3 : go heavy, use the state equation to refine our solution
			time = System.currentTimeMillis();
			Script script = declareStateEquation(sumMatrix, sr, smt);

			execAndCheckResult(script, solver);
			textReply = checkSat(solver, smt);
			//Logger.getLogger("fr.lip6.move.gal").info("Implicit Places using state equation in "+ (System.currentTimeMillis()-time) +" ms returned " + textReply);
		}
		
		MatrixCol tFlowPT = sr.getFlowPT().transpose();
		List<Integer> implicitPlaces =new ArrayList<>();
		for (int placeid = 0; placeid < sr.getPnames().size(); placeid++) {
			// assert implicit
			Script pimplicit = assertPimplict (placeid,tFlowPT,sr,smt);
			if (pimplicit.commands().isEmpty()) {
				continue;
			}
			solver.push(1);
			execAndCheckResult(pimplicit, solver);
			
			textReply = checkSat(solver, smt);
						
			// are we finished ?
			if (textReply.equals("unsat")) {
				Logger.getLogger("fr.lip6.move.gal").fine("Place "+sr.getPnames().get(placeid) + " with index "+placeid+ " is implicit.");
				implicitPlaces.add(placeid);
			}
			
			solver.pop(1);
			Logger.getLogger("fr.lip6.move.gal").fine("Place "+sr.getPnames().get(placeid) + " with index "+placeid+ " gave us " + textReply + " in " + (System.currentTimeMillis()-time) +" ms");
		}
		Logger.getLogger("fr.lip6.move.gal").info("Implicit Places using invariants "+ (withStateEquation?"and state equation ":"")+ "in "+ (System.currentTimeMillis()-time) +" ms returned " + implicitPlaces);
		
		solver.exit();
		return implicitPlaces;
	}

	private static Script assertPimplict(int placeid, MatrixCol tFlowPT, StructuralReduction sr, SMT smt) {
		
		IFactory ef = smt.smtConfig.exprFactory;
		// for each transition that takes from P				
		SparseIntArray eatP = tFlowPT.getColumn(placeid);
		List<IExpr> orConds = new ArrayList<>();
		for (int i=0; i < eatP.size() ; i++) {
			int tid = eatP.keyAt(i);
			int value = eatP.valueAt(i);
			
			// assert that "t is enabled, disregarding the fact it needs P marked with >= value"
			SparseIntArray preT = sr.getFlowPT().getColumn(tid);
			List<IExpr> conds = new ArrayList<>();
			for (int j=0; j < preT.size() ; j++) {
				int pfrom = preT.keyAt(j);
				int pval = preT.valueAt(j);
				if (pfrom == placeid) {
					continue;
				}
				// M(pfrom) >= pval
				conds.add(
						ef.fcn(ef.symbol(">="), 
								ef.symbol("s"+pfrom),
								// >= pval
								ef.numeral(pval)));
			}
			
					
			// P is not marked enough to enable T
			IExpr notMarked = ef.fcn(ef.symbol("<"), 
					ef.symbol("s"+placeid),
					// < value
					ef.numeral(value));
			
			conds.add(notMarked);
			// build up the full AND of constraints
			IExpr tenab = ef.fcn(ef.symbol("and"), conds);
			if (conds.size() == 1) {
				tenab = conds.get(0);
			} else if (conds.isEmpty()) {
				tenab = ef.symbol("true");
			}
			orConds.add(tenab);
		}
		
		Script script = new Script();
		IExpr res;
		if (orConds.isEmpty()) {
			return script;
		} else if (orConds.size() == 1) {
			res = orConds.get(0);
		} else {
			res = ef.fcn(ef.symbol("or"), orConds);
		}
		// t is enabled without P => P lacks tokens
		// If this assertion is sat, P is not implicit
		// if we get unsat, P is implicit w.r.t. this transition, it passes one implicitness test.
		script.add(new C_assert(res));

		return script;
	}



	/** Create a script that constrains state variables to satisfy the Petri net state equation.
	 * 
	 * In practice we do not use all transitions, only significant representatives. 
	 * We add a variable for each transition giving it's firing count.
	 * We add an assertion for each place forcing it to satisfy the state equation from M0.
	 * 
	 * @param sumMatrix reduced combined flow matrix as computed in computeReducedFlow()
	 * @param sr the Petri net (to grab initial marking from)
	 * @param smt for solver factories
	 * @return a Script that contains appropriate declarations and assertions implementing the state equation.
	 */
	private static Script declareStateEquation(MatrixCol sumMatrix, StructuralReduction sr, org.smtlib.SMT smt) {
		
		
		
		// declare a set of variables for holding Parikh count of the transition
		Script script = declareVariables(sumMatrix.getColumnCount(), "t", false, smt);

		IFactory ef = smt.smtConfig.exprFactory;
		// we work with one constraint for each place => use transposed
		MatrixCol mat = sumMatrix.transpose();
		for (int varindex = 0 ; varindex < mat.getColumnCount() ; varindex++) {

			SparseIntArray line = mat.getColumn(varindex);
			// assert : x = m0.x + X0*C(t0,x) + ...+ XN*C(Tn,x)
			List<IExpr> exprs = new ArrayList<IExpr>();

			// m0.x
			exprs.add(ef.numeral(sr.getMarks().get(varindex)));

			//  Xi*C(ti,x)
			for (int i = 0 ; i < line.size() ; i++) {
				int val = line.valueAt(i);
				int trindex = line.keyAt(i);
				IExpr nbtok ;
				if (val > 0) 
					nbtok = ef.numeral(val);
				else if (val < 0)
					nbtok = ef.fcn(ef.symbol("-"), ef.numeral(-val));
				else 
					continue;
				exprs.add(ef.fcn(ef.symbol("*"), 
						ef.symbol("t"+trindex),
						nbtok));
			}

			script.add(
					new C_assert(
							ef.fcn(ef.symbol("="), 
									ef.symbol("s"+varindex),
									// = m0.x + X0*C(t0,x) + ...+ XN*C(Tn,x)
									ef.fcn(ef.symbol("+"), exprs))));
		}
		return script;
	}


	/**
	 * Feeds the invariants to the solver as a set of constraints over the state variables.
	 * The feeding is done in two steps, first only semi flows, then the full generalized flows.
	 * 
	 * The goal is to get "unsat" result, so more constraints means more likely to be unsat, 
	 * but it's not clear that we need all constraints to be unsat (the contradiction could come early).
	 * We declare invariants in parts, if we get "unsat" return it immediately, otherwise add more invariants,
	 * check sat again and return the solver's response whether "sat" or "unsat". 
	 * 
	 * @param invar the invariants to declare, as obtained from the InvariantCalculator module.
	 * @param sr the Petri net
	 * @param solver we expect the solver to already know about variables
	 * @param smt access to smt factories
	 * @return "unsat" is what we hope for, could also return "sat" and maybe "unknown". 
	 */
	private static String assertInvariants(Set<SparseIntArray> invar, StructuralReduction sr, ISolver solver,
			org.smtlib.SMT smt, boolean verbose) {

		long time = System.currentTimeMillis();
		Script invpos = new Script();
		Script invneg = new Script();
		declareInvariants(invar,sr,invpos,invneg,smt);

		String textReply = "sat";
		// add the positive only for now
		if (!invpos.commands().isEmpty()) {
			execAndCheckResult(invpos, solver);		
			textReply = checkSat(solver, smt);
			if (verbose) Logger.getLogger("fr.lip6.move.gal").info("Absence of deadlock check using  "+invpos.commands().size()+" positive place invariants in "+ (System.currentTimeMillis()-time) +" ms returned " + textReply);
		}

		if (textReply.equals("sat") && ! invneg.commands().isEmpty()) {
			time = System.currentTimeMillis();
			if (verbose) Logger.getLogger("fr.lip6.move.gal").fine("Adding "+invneg.commands().size()+" place invariants with negative coefficients.");
			execAndCheckResult(invneg, solver);
			textReply = checkSat(solver, smt);
			if (verbose)  Logger.getLogger("fr.lip6.move.gal").info("Absence of deadlock check using  "+invpos.commands().size()+" positive and " + invneg.commands().size() +" generalized place invariants in "+ (System.currentTimeMillis()-time) +" ms returned " + textReply);
		}
		return textReply;
	}


	private static String checkSat(ISolver solver, org.smtlib.SMT smt) {
		IResponse res = solver.check_sat();
		IPrinter printer = smt.smtConfig.defaultPrinter;
		String textReply = printer.toString(res);
		return textReply;
	}


	/**
	 * Declares the invariants represented by invar, in two scripts according to whether they are pure positive 
	 * (semi flows) or general flows.
	 * @param invar the invariants we need to build constraints for
	 * @param sr the Petri net
	 * @param invpos positive invariants asserted here
	 * @param invneg general invariants asserted here
	 * @param smt solver access
	 */
	private static void declareInvariants(Set<SparseIntArray> invar, StructuralReduction sr, Script invpos,
			Script invneg, SMT smt) {
		// splitting posneg from pure positive
		IFactory efactory = smt.smtConfig.exprFactory;
		for (SparseIntArray invariant : invar) {
			boolean hasNeg = false;
			for (int i=0; i < invariant.size() ; i++) {
				if (invariant.valueAt(i) < 0) {
					hasNeg = true;
					break;
				}
			}			
			if (! hasNeg) {
				addInvariant(sr, efactory, invpos, invariant);
			} else {
				addInvariant(sr, efactory, invneg, invariant);
			}
		}
	}


	/**
	 * Builds a script that tests for deadlocks.
	 * i.e. that no transition is enabled.
	 * Algorithm consists in 
	 * AND over all transitions t 
	 *   OR of any input place not being marked enough to fire t.
	 *   
	 * We avoid having duplicate conditions asserted, but there is no implication test currently.
	 * @param sr the net we work with
	 * @param smt solver access
	 * @return a script which asserts that the system is deadlocked.
	 */
	private static Script assertNetIsDead(StructuralReduction sr, org.smtlib.SMT smt) {
		Script scriptAssertDead = new Script();
		// deliberate block to help gc.
		{
			IFactory ef = smt.smtConfig.exprFactory;
			Set<SparseIntArray> preconds = new HashSet<>();
			for (int i = 0; i < sr.getFlowPT().getColumnCount() ; i++)
				preconds.add(sr.getFlowPT().getColumn(i));
			for (SparseIntArray arr : preconds) {
				List<IExpr> conds = new ArrayList<>();
				// one of the preconditions of the transition is not marked enough to fire
				for (int  i=0; i < arr.size() ; i++) {
					conds.add( ef.fcn(ef.symbol("<"), ef.symbol("s"+arr.keyAt(i)), ef.numeral(arr.valueAt(i))));
				}
				// any of these is true => t is not fireable								
				IExpr res;
				if (conds.size() == 1) {
					res = conds.get(0);
				} else {
					res = ef.fcn(ef.symbol("or"), conds);
				}
				// add that t is not fireable
				scriptAssertDead.add(new C_assert(res));
			}			
		}
		return scriptAssertDead;
	}


	/**
	 * Computes a combined flow matrix, stored with column = transition, while removing any duplicates (e.g. due to test arcs or plain redundancy).
	 * Updates tnames that is supposed to initially be empty to set the names of the transitions that were kept.
	 * This is so we can reinterpret appropriately the Parikh vectors f so desired.
	 * @param sr our Petri net
	 * @param tnames empty list that will contain the transition names after call.
	 * @return a (reduced, less columns than usual) flow matrix
	 */
	private static MatrixCol computeReducedFlow(StructuralReduction sr, List<String> tnames) {
		MatrixCol sumMatrix = new MatrixCol(sr.getPnames().size(), 0);
		{
			int discarded=0;
			Set<SparseIntArray> seen = new HashSet<>();
			for (int i=0 ; i < sr.getFlowPT().getColumnCount() ; i++) {
				SparseIntArray combined = SparseIntArray.sumProd(-1, sr.getFlowPT().getColumn(i), 1, sr.getFlowTP().getColumn(i));
				if (seen.add(combined)) {
					sumMatrix.appendColumn(combined);
					tnames.add(sr.getTnames().get(i));
				} else
					discarded++;
			}
			if (discarded >0) {
				Logger.getLogger("fr.lip6.move.gal").info("Flow matrix only has "+sumMatrix.getColumnCount() +" transitions (discarded "+ discarded +" similar events)");
			}
		}
		return sumMatrix;
	}


	/**
	 * Creates and returns a script declaring N natural integer variables, with names prefix0 to prefixN-1. *
	 * If isSafe is true the upper bound is set to 1 (so they are 0 or 1 ~ boolean variables in effect).
	 * @param nbvars the number of variables to add  in the script
	 * @param prefix the prefix used in building variable names
	 * @param isSafe do we have an upper bound of 1 on these variables (lower bound 0 is always applied)
	 * @param smt access to the smt factories
	 * @return a script containing declaration + constraints on a set of variables.
	 */
	private static Script declareVariables(int nbvars, String prefix, boolean isSafe, org.smtlib.SMT smt) {
		Script script = new Script();
		IFactory ef = smt.smtConfig.exprFactory;
		// For integer LIA
		// smt.smtConfig.sortFactory.createSortExpression(ef.symbol("Int"));
		org.smtlib.ISort.IApplication ints2 = smt.smtConfig.sortFactory.createSortExpression(ef.symbol("Real"));
		for (int i =0 ; i < nbvars ; i++) {
			ISymbol si = ef.symbol(prefix+i);
			script.add(new org.smtlib.command.C_declare_fun(
					si,
					Collections.emptyList(),
					ints2								
					));
			script.add(new C_assert(ef.fcn(ef.symbol(">="), si, ef.numeral(0))));
			if (isSafe) {
				script.add(new C_assert(ef.fcn(ef.symbol("<="), si, ef.numeral(1))));
			}
			script.add(new C_assert(ef.fcn(ef.symbol("or"), ef.fcn(ef.symbol("="), si, ef.numeral(0)), ef.fcn(ef.symbol(">="), si, ef.numeral(1)))));
		}
		return script;
	}


	/**
	 * Start an instance of a Z3 solver, with timeout at 3000, logic QF_LIA, with produce models.
	 * @param solverPath path to Z3 exe
	 * @param smt the smt instance to configure/setup
	 * @return a started solver or throws a RuntimeEx
	 */
	private static ISolver initSolver(String solverPath, org.smtlib.SMT smt) {
		smt.smtConfig.executable = solverPath;
		smt.smtConfig.timeout = 3000;
		Solver engine = Solver.Z3;
		ISolver solver = engine .getSolver(smt.smtConfig);		
		// start the solver
		IResponse err = solver.start();
		if (err.isError()) {
			throw new RuntimeException("Could not start solver "+ engine+" from path "+ solverPath + " raised error :"+err);
		}
		err = solver.set_option(smt.smtConfig.exprFactory.keyword(Utils.PRODUCE_MODELS), smt.smtConfig.exprFactory.symbol("true"));
		if (err.isError()) {
			throw new RuntimeException("Could not set :produce-models option :" + err);
		}
		err = solver.set_logic("QF_LRA", null);
		if (err.isError()) {
			throw new RuntimeException("Could not set logic to QF_LRA" + err);
		}
		return solver;
	}



	private static void addInvariant(StructuralReduction sr, IFactory efactory, Script script,
			SparseIntArray invariant) {
		int sum = 0;
		// assert : cte = m0 * x0 + ... + m_n*x_n
		// build sum up
		List<IExpr> toadd = new ArrayList<>();
		List<IExpr> torem = new ArrayList<>();
		for (int i = 0 ; i < invariant.size() ; i++) {
			int v = invariant.keyAt(i);
			int val = invariant.valueAt(i);
			if (val != 0) {
				IExpr ss = efactory.symbol("s"+v);
				if (val != 1 && val != -1) {
					ss = efactory.fcn(efactory.symbol("*"), efactory.numeral( Math.abs(val)), ss );
				}
				if (val > 0) 
					toadd.add(ss);
				else
					torem.add(ss);
				sum += sr.getMarks().get(v) * val;
			}
		}
		IExpr sumE ;
		if (toadd.isEmpty()) {
			sumE = efactory.numeral(0);
		} else if (toadd.size() == 1) {
			sumE = toadd.get(0);
		} else {
			sumE = efactory.fcn(efactory.symbol("+"), toadd);
		}

		IExpr sumR  = efactory.numeral(sum);
		if (! torem.isEmpty()) {
			torem.add(sumR);
			sumR = efactory.fcn(efactory.symbol("+"), torem);
		}
		IExpr invarexpr = efactory.fcn(efactory.symbol("="), sumR, sumE);
		script.add(new C_assert(invarexpr));
	}

	private static SparseIntArray extractState(IResponse state) {
		SparseIntArray res= new SparseIntArray();
		if (state instanceof ISeq) {
			ISeq seq = (ISeq) state;

			for (ISexpr sexpr : seq.sexprs()) {
				if (sexpr instanceof ISeq) {
					ISeq pair = (ISeq) sexpr;
					if (pair.sexprs().size() == 2) {
						if (pair.sexprs().get(0) instanceof ISymbol && pair.sexprs().get(1) instanceof INumeral) {
							int varindex = Integer.parseInt( ((ISymbol) pair.sexprs().get(0)).value().substring(1) );
							int varvalue = ((INumeral) pair.sexprs().get(1)).intValue();
							res.append(varindex, varvalue);
						}
					}
				}
			}
		}
		return res;
	}

	private static void queryState(IFactory efactory, StructuralReduction sr, ISolver solver) {
		List<IExpr> places = new ArrayList<>();
		for (int i =0 ; i < sr.getPnames().size() ; i++) {
			ISymbol si = efactory.symbol("s"+i);
			places.add(si);
		}
		ICommand getVals = new C_get_value(places);
		IResponse state = getVals.execute(solver);		
		SparseIntArray s = extractState(state);
		Logger.getLogger("fr.lip6.move.gal").info("Deadlock seems possible (SAT) in state :" + s);
	}

	private static void queryParikh(IFactory efactory, List<String> pnames, ISolver solver) {
		List<IExpr> places = new ArrayList<>();
		for (int i =0 ; i < pnames.size() ; i++) {
			ISymbol si = efactory.symbol("t"+i);
			places.add(si);
		}
		ICommand getVals = new C_get_value(places);
		IResponse state = getVals.execute(solver);		
		SparseIntArray s = extractState(state);
		StringBuilder sb = new StringBuilder();
		for (int  i=0 ; i < s.size() ; i++) {
			int ti = s.keyAt(i);
			int vi = s.valueAt(i);
			if (i >0) {
				sb.append(", ");
			}
			sb.append(pnames.get(ti)).append("=").append(vi);
		}
		Logger.getLogger("fr.lip6.move.gal").info("Deadlock seems possible with Parikh count :" + sb.toString());
	}

}
