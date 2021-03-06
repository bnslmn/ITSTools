/*
 * generated by Xtext 2.11.0
 */
package fr.lip6.move.divine.tests

import com.google.inject.Inject
import fr.lip6.move.divine.divine.DivineSpecification
import org.eclipse.xtext.testing.InjectWith
import org.eclipse.xtext.testing.XtextRunner
import org.eclipse.xtext.testing.util.ParseHelper
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(XtextRunner)
@InjectWith(DivineInjectorProvider)
class DivineParsingTest {
	@Inject
	ParseHelper<DivineSpecification> parseHelper
	
	@Test
	def void loadModel() {
		val result = parseHelper.parse('''
			Hello Xtext!
		''')
		Assert.assertNotNull(result)
		Assert.assertTrue(result.eResource.errors.isEmpty)
	}
}
