package com.structurizr.dsl;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

class PluginDslContext extends DslContext {

    private final String fullyQualifiedClassName;
    private final File dslFile;
    private final Map<String,String> parameters = new HashMap<>();

    PluginDslContext(String fullyQualifiedClassName, File dslFile) {
        this.fullyQualifiedClassName = fullyQualifiedClassName;
        this.dslFile = dslFile;
    }

    void addParameter(String name, String value) {
        parameters.put(name, value);
    }

    @Override
    void end() {
        try {
            Class pluginClass = loadClass(fullyQualifiedClassName, dslFile);
            StructurizrDslPlugin plugin = (StructurizrDslPlugin)pluginClass.getDeclaredConstructor().newInstance();
            StructurizrDslPluginContext pluginContext = new StructurizrDslPluginContext(getWorkspace(), parameters);
            plugin.run(pluginContext);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error running plugin " + fullyQualifiedClassName + ", caused by " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

}