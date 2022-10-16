package com.structurizr.dsl;

import java.io.File;

import com.structurizr.model.Element;
import com.structurizr.model.Relationship;
import com.structurizr.view.View;

public interface StructurizrDslParserListener {

	default void onParsedView(File file, int lineNumber, View view) { }
	
	default void onParsedRelationShip(File file, int lineNumber, String identifier, Relationship relationship) { }
	
	default void onParsedModelElement(File flle, int lineNumber, String identifier, Element item) { }
	
	default void onParsedColor(File file, int linenumber) { }
	
	default void onEndContext(File file, int linenumber, String context) { }

	default void onInclude(File hostFile, int linenumber, File referencedFile, String path) { }

    default void onParsedModel(File dslFile, int lineNumber) { }

    default void onParsedWorkspace(File dslFile, int lineNumber) { }

    default void onParsedStyles(File dslFile, int lineNumber) { }

    default void onParsedViews(File dslFile, int lineNumber) { }

    default void onParsedElementStyle(File dslFile, int lineNumber) { }

    default void onParsedRelationShipStyle(File dslFile, int lineNumber) { } ;
}
