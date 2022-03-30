package com.structurizr.dsl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

class ExternalScriptDslContext extends ScriptDslContext {

    private final File dslFile;
    private final String filename;

    ExternalScriptDslContext(File dslFile, String filename) {
        this.dslFile = dslFile;
        this.filename = filename;
    }

    @Override
    void end() {
        try {
            File scriptFile = new File(dslFile.getParent(), filename);
            if (!scriptFile.exists()) {
                throw new RuntimeException("Script file " + scriptFile.getCanonicalPath() + " does not exist");
            }

            String fileExtension = filename.substring(filename.lastIndexOf('.') + 1);
            List<String> lines = Files.readAllLines(scriptFile.toPath(), StandardCharsets.UTF_8);

            run(this, fileExtension, lines);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error running script at " + filename + ", caused by " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

}
