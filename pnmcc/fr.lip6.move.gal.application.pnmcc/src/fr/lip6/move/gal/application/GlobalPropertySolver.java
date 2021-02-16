package fr.lip6.move.gal.application;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fr.lip6.move.gal.structural.DeadlockFound;
import fr.lip6.move.gal.structural.NoDeadlockExists;
import fr.lip6.move.gal.structural.Property;
import fr.lip6.move.gal.structural.PropertyType;
import fr.lip6.move.gal.structural.SparsePetriNet;
import fr.lip6.move.gal.structural.expr.Expression;
import fr.lip6.move.gal.structural.expr.Op;

public class GlobalPropertySolver {

	private String solverPath;

	public GlobalPropertySolver(String solverPath) {
		this.solverPath = solverPath;
	}

	public boolean solveProperty(String examination, MccTranslator reader) {

		// initialize a shared container to detect help detect termination in portfolio case
		Map<String,Boolean> doneProps = new ConcurrentHashMap<>();

		boolean isSafe = false;
		// load "known" stuff about the model
		if (reader.isSafeNet()) {
			// NUPN implies one safe
			isSafe = true;
		}
		
		
		// build properties
		SparsePetriNet spn = reader.getSPN();
		for (int tid=0; tid < spn.getTransitionCount() ; tid++) {
			Expression prop = Expression.nop(Op.ENABLED, Collections.singletonList(Expression.trans(tid)));
			Property p = new Property(prop ,PropertyType.INVARIANT,"enabled"+tid);
			spn.getProperties().add(p );
		}
		System.out.println(spn);
		
		
		// vire les prop triviales, utile ?
		ReachabilitySolver.checkInInitial(reader, doneProps);
		if (!spn.getProperties().isEmpty()) {
			try {
				ReachabilitySolver.applyReductions(reader, doneProps, solverPath, isSafe);
			} catch (NoDeadlockExists e) {
				e.printStackTrace();
			} catch (DeadlockFound e) {
				e.printStackTrace();
			}
		}	
		
		return false;
	}

	
	
}
