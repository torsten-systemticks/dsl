package com.structurizr.dsl;

import com.structurizr.model.Element;
import com.structurizr.model.Relationship;
import com.structurizr.view.View;

public interface StructurizrDslParserListener {

	default void onParsedView(int lineNumber, View view) { }
	
	default void onParsedRelationShip(int lineNumber, String identifier, Relationship relationship) { }
	
	default void onParsedModelElement(int lineNumber, String identifier, Element item) { }
	
}
