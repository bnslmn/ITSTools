/*
 * generated by Xtext
 */
package fr.lip6.move;

//import org.eclipse.xtext.debug.IStratumBreakpointSupport;
import org.eclipse.xtext.conversion.IValueConverterService;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.naming.IQualifiedNameProvider;
import org.eclipse.xtext.scoping.IScopeProvider;




import org.eclipse.xtext.serializer.tokens.ICrossReferenceSerializer;

import com.google.inject.Binder;

import fr.lip6.move.formatting.NegativeIntegerSupportingConverter;
import fr.lip6.move.scoping.GALCrossReferenceSerializer;
//import fr.lip6.move.debug.GalStratumBreakpointSupport;
import fr.lip6.move.scoping.GalNameConverter;
import fr.lip6.move.scoping.GalScopeProvider;
import fr.lip6.move.scoping.ITSQualifiedNameProvider;

/**
 * Use this class to register components to be used at runtime / without the
 * Equinox extension registry.
 */
public class GalRuntimeModule extends fr.lip6.move.AbstractGalRuntimeModule {

//	@Override
//	public Class<? extends IStratumBreakpointSupport> bindIStratumBreakpointSupport() {
//		return GalStratumBreakpointSupport.class;
//	}
	
	
	@Override
	public Class<? extends IScopeProvider> bindIScopeProvider() {
		return GalScopeProvider.class;
	}
	
	@Override
	public void configureSerializerIScopeProvider(Binder binder) {
		binder.bind(org.eclipse.xtext.scoping.IScopeProvider.class).annotatedWith(org.eclipse.xtext.serializer.tokens.SerializerScopeProviderBinding.class).to(GalScopeProvider.class);
	}
	
	@Override
	public void configureLinkingIScopeProvider(Binder binder) {
		binder.bind(org.eclipse.xtext.scoping.IScopeProvider.class).annotatedWith(org.eclipse.xtext.linking.LinkingScopeProviderBinding.class).to(GalScopeProvider.class);
//		binder.bind(org.eclipse.xtext.scoping.IScopeProvider.class).annotatedWith(org.eclipse.xtext.linking.LinkingScopeProviderBinding.class).to(org.eclipse.xtext.xbase.scoping.batch.IBatchScopeProvider.class);
	}
	
	@Override
	public Class<? extends IQualifiedNameConverter> bindIQualifiedNameConverter() {
		return GalNameConverter.class;
	}
	
	@Override
	public Class<? extends IQualifiedNameProvider> bindIQualifiedNameProvider() {
		return ITSQualifiedNameProvider.class;
	}
	
	@Override
	public Class<? extends IValueConverterService> bindIValueConverterService() {
		return NegativeIntegerSupportingConverter.class;
	}
	
	public Class<? extends ICrossReferenceSerializer> bindICrossRefererenceSerializer() {
		return GALCrossReferenceSerializer.class;
	}
	
}
