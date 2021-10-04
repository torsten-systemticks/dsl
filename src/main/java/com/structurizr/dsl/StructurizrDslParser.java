package com.structurizr.dsl;

import com.structurizr.Workspace;
import com.structurizr.model.*;
import com.structurizr.util.StringUtils;
import com.structurizr.view.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main DSL parser class - forms the API for using the parser.
 */
public final class StructurizrDslParser extends StructurizrDslTokens {

    private static final Pattern EMPTY_LINE_PATTERN = Pattern.compile("^\\s*");

    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*?(//|#).*$");
    private static final String MULTI_LINE_COMMENT_START_TOKEN = "/*";
    private static final String MULTI_LINE_COMMENT_END_TOKEN = "*/";

    private static final Pattern STRING_SUBSTITUTION_PATTERN = Pattern.compile("(\\$\\{[a-zA-Z0-9-_.]+?})");

    private IdentifierScope identifierScope = IdentifierScope.Flat;
    private Stack<DslContext> contextStack;
    private IdentifiersRegister identifersRegister;
    private Map<String, Constant> constants;

    private List<String> dslSourceLines = new ArrayList<>();
    private Workspace workspace;
    private boolean extendingWorkspace = false;

    private boolean restricted = false;

    private StructurizrDslParserListener parserListener;
    
    /**
     * Creates a new instance of the parser.
     */
    public StructurizrDslParser(StructurizrDslParserListener _parserListener) {
        contextStack = new Stack<>();
        identifersRegister = new IdentifiersRegister();
        constants = new HashMap<>();
        parserListener = _parserListener;
    }

    public StructurizrDslParser() {
        contextStack = new Stack<>();
        identifersRegister = new IdentifiersRegister();
        constants = new HashMap<>();
        parserListener = new StructurizrDslParserListener() {};
    }

    public IdentifierScope getIdentifierScope() {
        return identifierScope;
    }

    public void setIdentifierScope(IdentifierScope identifierScope) {
        if (identifierScope == null) {
            identifierScope = IdentifierScope.Flat;
        }

        this.identifierScope = identifierScope;
        this.identifersRegister.setIdentifierScope(identifierScope);
    }

    /**
     * Sets whether to run this parser in restricted mode (this stops !include, !docs, !adrs from working).
     *
     * @param restricted        true for restricted mode, false otherwise
     */
    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }

    /**
     * Gets the workspace that has been created by parsing the Structurizr DSL.
     *
     * @return  a Workspace instance
     */
    public Workspace getWorkspace() {
        if (workspace != null) {
            DslUtils.setDsl(workspace, getParsedDsl());
        }

        return workspace;
    }

    private String getParsedDsl() {
        StringBuilder buf = new StringBuilder();

        for (String line : dslSourceLines) {
            buf.append(line);
            buf.append(System.lineSeparator());
        }

        return buf.toString();
    }

    void parse(DslParserContext context, File path) throws StructurizrDslParserException {
        parse(path);

        context.copyFrom(identifersRegister);
    }

    /**
     * Parses the specified Structurizr DSL file(s), adding the parsed content to the workspace.
     * If "path" represents a single file, that single file will be parsed.
     * If "path" represents a directory, all files in that directory (recursively) will be parsed.
     *
     * @param path      a File object representing a file or directory
     * @throws StructurizrDslParserException when something goes wrong
     */
    public void parse(File path) throws StructurizrDslParserException {
        if (path == null) {
            throw new StructurizrDslParserException("A file must be specified");
        }

        if (!path.exists()) {
            throw new StructurizrDslParserException("The file at " + path.getAbsolutePath() + " does not exist");
        }

        List<File> files = FileUtils.findFiles(path);
        try {
            for (File file : files) {
                parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8), file);
            }
        } catch (IOException e) {
            throw new StructurizrDslParserException(e.getMessage());
        }
    }

    void parse(DslParserContext context, String dsl) throws StructurizrDslParserException {
        parse(dsl);

        context.copyFrom(identifersRegister);
    }

    /**
     * Parses the specified Structurizr DSL fragment, adding the parsed content to the workspace.
     *
     * @param dsl       a DSL fragment
     * @throws StructurizrDslParserException when something goes wrong
     */
    public void parse(String dsl) throws StructurizrDslParserException {
        if (StringUtils.isNullOrEmpty(dsl)) {
            throw new StructurizrDslParserException("A DSL fragment must be specified");
        }

        List<String> lines = Arrays.asList(dsl.split("\\r?\\n"));
        parse(lines, new File("."));
    }

    void parse(List<String> lines, File file) throws StructurizrDslParserException {
        int lineNumber = 1;
        for (String line : lines) {
            boolean includeInDslSourceLines = true;

            try {
                if (EMPTY_LINE_PATTERN.matcher(line).matches()) {
                    // do nothing
                } else if (COMMENT_PATTERN.matcher(line).matches()) {
                    // do nothing
                } else if (inContext(InlineScriptDslContext.class)) {
                    if (DslContext.CONTEXT_END_TOKEN.equals(line.trim())) {
                        endContext();
                    } else {
                        getContext(InlineScriptDslContext.class).addLine(line);
                    }
                } else {
                    List<String> listOfTokens = new Tokenizer().tokenize(line);
                    listOfTokens = listOfTokens.stream().map(this::substituteStrings).collect(Collectors.toList());

                    Tokens tokens = new Tokens(listOfTokens);

                    String identifier = null;
                    if (tokens.size() > 3 && ASSIGNMENT_OPERATOR_TOKEN.equals(tokens.get(1))) {
                        identifier = tokens.get(0);
                        identifersRegister.validateIdentifierName(identifier);

                        tokens = new Tokens(listOfTokens.subList(2, listOfTokens.size()));
                    }

                    String firstToken = tokens.get(0);

                    if (line.trim().startsWith(MULTI_LINE_COMMENT_START_TOKEN) && line.trim().endsWith(MULTI_LINE_COMMENT_END_TOKEN)) {
                        // do nothing
                    } else if (firstToken.startsWith(MULTI_LINE_COMMENT_START_TOKEN)) {
                        startContext(new CommentDslContext());

                    } else if (inContext(CommentDslContext.class) && line.trim().endsWith(MULTI_LINE_COMMENT_END_TOKEN)) {
                        endContext();

                    } else if (inContext(CommentDslContext.class)) {
                        // do nothing

                    } else if (DslContext.CONTEXT_END_TOKEN.equals(tokens.get(0))) {
                        parserListener.onEndContext(file, lineNumber, contextStack.peek().getClass().getSimpleName());
                        endContext();

                    } else if (tokens.size() > 2 && RELATIONSHIP_TOKEN.equals(tokens.get(1)) && (inContext(ModelDslContext.class) || inContext(EnterpriseDslContext.class) || inContext(CustomElementDslContext.class) || inContext(PersonDslContext.class) || inContext(SoftwareSystemDslContext.class) || inContext(ContainerDslContext.class) || inContext(ComponentDslContext.class) || inContext(DeploymentEnvironmentDslContext.class) || inContext(DeploymentNodeDslContext.class) || inContext(InfrastructureNodeDslContext.class) || inContext(SoftwareSystemInstanceDslContext.class) || inContext(ContainerInstanceDslContext.class))) {
                        Relationship relationship = new ExplicitRelationshipParser().parse(getContext(), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new RelationshipDslContext(relationship));
                        }

                        registerIdentifier(identifier, relationship);
                        parserListener.onParsedRelationShip(file, lineNumber, identifier, relationship);

                    } else if (tokens.size() >= 2 && RELATIONSHIP_TOKEN.equals(tokens.get(0)) && (inContext(CustomElementDslContext.class) || inContext(PersonDslContext.class) || inContext(SoftwareSystemDslContext.class) || inContext(ContainerDslContext.class) || inContext(ComponentDslContext.class) || inContext(DeploymentNodeDslContext.class) || inContext(InfrastructureNodeDslContext.class) || inContext(SoftwareSystemInstanceDslContext.class) || inContext(ContainerInstanceDslContext.class))) {
                        Relationship relationship = new ImplicitRelationshipParser().parse(getContext(ModelItemDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new RelationshipDslContext(relationship));
                        }

                        registerIdentifier(identifier, relationship);
                        parserListener.onParsedRelationShip(file, lineNumber, identifier, relationship);

                    } else if (REF_TOKEN.equalsIgnoreCase(firstToken) && (inContext(ModelDslContext.class))) {
                        Element element = new RefParser().parse(getContext(), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            if (element instanceof Person) {
                                startContext(new PersonDslContext((Person)element));
                            } else if (element instanceof SoftwareSystem) {
                                startContext(new SoftwareSystemDslContext((SoftwareSystem)element));
                            } else if (element instanceof Container) {
                                startContext(new ContainerDslContext((Container) element));
                            } else if (element instanceof Component) {
                                startContext(new ComponentDslContext((Component)element));
                            } else if (element instanceof DeploymentNode) {
                                startContext(new DeploymentNodeDslContext((DeploymentNode)element));
                            } else if (element instanceof InfrastructureNode) {
                                startContext(new InfrastructureNodeDslContext((InfrastructureNode)element));
                            } else if (element instanceof SoftwareSystemInstance) {
                                startContext(new SoftwareSystemInstanceDslContext((SoftwareSystemInstance)element));
                            } else if (element instanceof ContainerInstance) {
                                startContext(new ContainerInstanceDslContext((ContainerInstance)element));
                            }
                        }

                        if (!StringUtils.isNullOrEmpty(identifier)) {
                            registerIdentifier(identifier, element);
                        }

                    } else if (CUSTOM_ELEMENT_TOKEN.equalsIgnoreCase(firstToken) && (inContext(ModelDslContext.class))) {
                        CustomElement customElement = new CustomElementParser().parse(getContext(GroupableDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new CustomElementDslContext(customElement));
                        }

                        registerIdentifier(identifier, customElement);
                        
                        parserListener.onParsedModelElement(file, lineNumber, identifier, customElement);


                    } else if (PERSON_TOKEN.equalsIgnoreCase(firstToken) && (inContext(ModelDslContext.class) || inContext(EnterpriseDslContext.class))) {
                        Person person = new PersonParser().parse(getContext(GroupableDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new PersonDslContext(person));
                        }

                        registerIdentifier(identifier, person);
                        
                        parserListener.onParsedModelElement(file, lineNumber, identifier, person);

                    } else if (SOFTWARE_SYSTEM_TOKEN.equalsIgnoreCase(firstToken) && (inContext(ModelDslContext.class) || inContext(EnterpriseDslContext.class))) {
                        SoftwareSystem softwareSystem = new SoftwareSystemParser().parse(getContext(GroupableDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new SoftwareSystemDslContext(softwareSystem));
                        }

                        registerIdentifier(identifier, softwareSystem);

                        parserListener.onParsedModelElement(file, lineNumber, identifier, softwareSystem);

                    } else if (CONTAINER_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class)) {
                        Container container = new ContainerParser().parse(getContext(SoftwareSystemDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new ContainerDslContext(container));
                        }

                        registerIdentifier(identifier, container);
                        
                        parserListener.onParsedModelElement(file, lineNumber, identifier, container);

                    } else if (COMPONENT_TOKEN.equalsIgnoreCase(firstToken) && inContext(ContainerDslContext.class)) {
                        Component component = new ComponentParser().parse(getContext(ContainerDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new ComponentDslContext(component));
                        }

                        registerIdentifier(identifier, component);
                        
                        parserListener.onParsedModelElement(file, lineNumber, identifier, component);

                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelDslContext.class) && !getContext(ModelDslContext.class).hasGroup()) {
                        ElementGroup group = new GroupParser().parse(tokens.withoutContextStartToken());

                        startContext(new ModelDslContext(group));
                        registerIdentifier(identifier, group);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, group);

                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(EnterpriseDslContext.class) && !getContext(EnterpriseDslContext.class).hasGroup()) {
                        ElementGroup group = new GroupParser().parse(tokens.withoutContextStartToken());

                        startContext(new EnterpriseDslContext(group));
                        registerIdentifier(identifier, group);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, group);

                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class) && !getContext(SoftwareSystemDslContext.class).hasGroup()) {
                        ElementGroup group = new GroupParser().parse(tokens.withoutContextStartToken());

                        SoftwareSystem softwareSystem = getContext(SoftwareSystemDslContext.class).getSoftwareSystem();
                        group.setParent(softwareSystem);
                        startContext(new SoftwareSystemDslContext(softwareSystem, group));
                        registerIdentifier(identifier, group);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, group);

                    } else if (GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(ContainerDslContext.class) && !getContext(ContainerDslContext.class).hasGroup()) {
                        ElementGroup group = new GroupParser().parse(tokens.withoutContextStartToken());

                        Container container = getContext(ContainerDslContext.class).getContainer();
                        group.setParent(container);
                        startContext(new ContainerDslContext(container, group));
                        registerIdentifier(identifier, group);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, group);

                    } else if (TAGS_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelItemDslContext.class)) {
                        new ModelItemParser().parseTags(getContext(ModelItemDslContext.class), tokens);

                    } else if (URL_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelItemDslContext.class)) {
                        new ModelItemParser().parseUrl(getContext(ModelItemDslContext.class), tokens);

                    } else if (PROPERTIES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelItemDslContext.class)) {
                        startContext(new ModelItemPropertiesDslContext(getContext(ModelItemDslContext.class).getModelItem()));

                    } else if (inContext(ModelItemPropertiesDslContext.class)) {
                        new ModelItemParser().parseProperty(getContext(ModelItemPropertiesDslContext.class), tokens);

                    } else if (PERSPECTIVES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelItemDslContext.class)) {
                        startContext(new ModelItemPerspectivesDslContext(getContext(ModelItemDslContext.class).getModelItem()));

                    } else if (inContext(ModelItemPerspectivesDslContext.class)) {
                        new ModelItemParser().parsePerspective(getContext(ModelItemPerspectivesDslContext.class), tokens);

                    } else if (WORKSPACE_TOKEN.equalsIgnoreCase(firstToken) && contextStack.empty()) {
                        DslParserContext dslParserContext = new DslParserContext(file, restricted);
                        dslParserContext.setIdentifierRegister(identifersRegister);

                        workspace = new WorkspaceParser().parse(dslParserContext, tokens.withoutContextStartToken());
                        extendingWorkspace = !workspace.getModel().isEmpty();
                        startContext(new WorkspaceDslContext());
                    } else if (IMPLIED_RELATIONSHIPS_TOKEN.equalsIgnoreCase(firstToken) || IMPLIED_RELATIONSHIPS_TOKEN.substring(1).equalsIgnoreCase(firstToken)) {
                        new ImpliedRelationshipsParser().parse(getContext(), tokens);

                    } else if (NAME_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        new WorkspaceParser().parseName(getContext(), tokens);

                    } else if (DESCRIPTION_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        new WorkspaceParser().parseDescription(getContext(), tokens);

                    } else if (MODEL_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        startContext(new ModelDslContext());

                    } else if (VIEWS_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        startContext(new ViewsDslContext());

                    } else if (BRANDING_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        startContext(new BrandingDslContext(file));

                    } else if (BRANDING_LOGO_TOKEN.equalsIgnoreCase(firstToken) && inContext(BrandingDslContext.class)) {
                        new BrandingParser().parseLogo(getContext(BrandingDslContext.class), tokens, restricted);

                    } else if (BRANDING_FONT_TOKEN.equalsIgnoreCase(firstToken) && inContext(BrandingDslContext.class)) {
                        new BrandingParser().parseFont(getContext(BrandingDslContext.class), tokens);

                    } else if (STYLES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        startContext(new StylesDslContext());

                    } else if (ELEMENT_STYLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(StylesDslContext.class)) {
                        ElementStyle elementStyle = new ElementStyleParser().parseElementStyle(getContext(), tokens.withoutContextStartToken());
                        startContext(new ElementStyleDslContext(elementStyle, file));

                    } else if (ELEMENT_STYLE_BACKGROUND_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseBackground(getContext(ElementStyleDslContext.class), tokens);
                        parserListener.onParsedColor(file, lineNumber);
                        
                    } else if ((ELEMENT_STYLE_COLOUR_TOKEN.equalsIgnoreCase(firstToken) || ELEMENT_STYLE_COLOR_TOKEN.equalsIgnoreCase(firstToken)) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseColour(getContext(ElementStyleDslContext.class), tokens);
                        parserListener.onParsedColor(file, lineNumber);

                    } else if (ELEMENT_STYLE_STROKE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseStroke(getContext(ElementStyleDslContext.class), tokens);
                        parserListener.onParsedColor(file, lineNumber);

                    } else if (ELEMENT_STYLE_SHAPE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseShape(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_BORDER_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseBorder(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_OPACITY_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseOpacity(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_WIDTH_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseWidth(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_HEIGHT_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseHeight(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_FONT_SIZE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseFontSize(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_METADATA_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseMetadata(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_DESCRIPTION_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseDescription(getContext(ElementStyleDslContext.class), tokens);

                    } else if (ELEMENT_STYLE_ICON_TOKEN.equalsIgnoreCase(firstToken) && inContext(ElementStyleDslContext.class)) {
                        new ElementStyleParser().parseIcon(getContext(ElementStyleDslContext.class), tokens, restricted);

                    } else if (RELATIONSHIP_STYLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(StylesDslContext.class)) {
                        RelationshipStyle relationshipStyle = new RelationshipStyleParser().parseRelationshipStyle(getContext(), tokens.withoutContextStartToken());
                        startContext(new RelationshipStyleDslContext(relationshipStyle));

                    } else if (RELATIONSHIP_STYLE_THICKNESS_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseThickness(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if ((RELATIONSHIP_STYLE_COLOUR_TOKEN.equalsIgnoreCase(firstToken) || RELATIONSHIP_STYLE_COLOR_TOKEN.equalsIgnoreCase(firstToken)) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseColour(getContext(RelationshipStyleDslContext.class), tokens);
                        parserListener.onParsedColor(file, lineNumber);

                    } else if (RELATIONSHIP_STYLE_DASHED_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseDashed(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_OPACITY_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseOpacity(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_WIDTH_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseWidth(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_FONT_SIZE_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseFontSize(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_POSITION_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parsePosition(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (RELATIONSHIP_STYLE_ROUTING_TOKEN.equalsIgnoreCase(firstToken) && inContext(RelationshipStyleDslContext.class)) {
                        new RelationshipStyleParser().parseRouting(getContext(RelationshipStyleDslContext.class), tokens);

                    } else if (ENTERPRISE_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelDslContext.class)) {
                        new EnterpriseParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new EnterpriseDslContext());

                    } else if (DEPLOYMENT_ENVIRONMENT_TOKEN.equalsIgnoreCase(firstToken) && inContext(ModelDslContext.class)) {
                        String environment = new DeploymentEnvironmentParser().parse(tokens.withoutContextStartToken());
                        startContext(new DeploymentEnvironmentDslContext(environment));

                        registerIdentifier(identifier, new DeploymentEnvironment(environment));

                    } else if (DEPLOYMENT_GROUP_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentEnvironmentDslContext.class)) {
                        String group = new DeploymentGroupParser().parse(tokens.withoutContextStartToken());

                        registerIdentifier(identifier, new DeploymentGroup(group));

                    } else if (DEPLOYMENT_NODE_TOKEN.equalsIgnoreCase(firstToken) && (inContext(DeploymentEnvironmentDslContext.class) || inContext(DeploymentNodeDslContext.class))) {
                        DeploymentNode deploymentNode = new DeploymentNodeParser().parse(getContext(), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new DeploymentNodeDslContext(deploymentNode));
                        }

                        registerIdentifier(identifier, deploymentNode);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, deploymentNode);
                        
                    } else if (INFRASTRUCTURE_NODE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentNodeDslContext.class)) {
                        InfrastructureNode infrastructureNode = new InfrastructureNodeParser().parse(getContext(DeploymentNodeDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new InfrastructureNodeDslContext(infrastructureNode));
                        }

                        registerIdentifier(identifier, infrastructureNode);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, infrastructureNode);

                    } else if (SOFTWARE_SYSTEM_INSTANCE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentNodeDslContext.class)) {
                        SoftwareSystemInstance softwareSystemInstance = new SoftwareSystemInstanceParser().parse(getContext(DeploymentNodeDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new SoftwareSystemInstanceDslContext(softwareSystemInstance));
                        }

                        registerIdentifier(identifier, softwareSystemInstance);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, softwareSystemInstance);
                        
                    } else if (CONTAINER_INSTANCE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentNodeDslContext.class)) {
                        ContainerInstance containerInstance = new ContainerInstanceParser().parse(getContext(DeploymentNodeDslContext.class), tokens.withoutContextStartToken());

                        if (shouldStartContext(tokens)) {
                            startContext(new ContainerInstanceDslContext(containerInstance));
                        }

                        registerIdentifier(identifier, containerInstance);
                        parserListener.onParsedModelElement(file, lineNumber, identifier, containerInstance);

                    } else if (HEALTH_CHECK_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticStructureElementInstanceDslContext.class)) {
                        new HealthCheckParser().parse(getContext(StaticStructureElementInstanceDslContext.class), tokens.withoutContextStartToken());
                    } else if (CUSTOM_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        CustomView view = new CustomViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new CustomViewDslContext(view));
                        parserListener.onParsedView(file, lineNumber, view);

                    } else if (SYSTEM_LANDSCAPE_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        SystemLandscapeView view = new SystemLandscapeViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new SystemLandscapeViewDslContext(view));
                        parserListener.onParsedView(file, lineNumber, view);

                    } else if (SYSTEM_CONTEXT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        SystemContextView view = new SystemContextViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new SystemContextViewDslContext(view));
                        parserListener.onParsedView(file, lineNumber, view);

                    } else if (CONTAINER_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        ContainerView view = new ContainerViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new ContainerViewDslContext(view));
                        parserListener.onParsedView(file, lineNumber, view);

                    } else if (COMPONENT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        ComponentView view = new ComponentViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new ComponentViewDslContext(view));
                        parserListener.onParsedView(file, lineNumber, view);

                    } else if (DYNAMIC_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        DynamicView view = new DynamicViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new DynamicViewDslContext(view));
                        parserListener.onParsedView(file, lineNumber, view);

                    } else if (DEPLOYMENT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        DeploymentView view = new DeploymentViewParser().parse(getContext(), tokens.withoutContextStartToken());
                        startContext(new DeploymentViewDslContext(view));
                        parserListener.onParsedView(file, lineNumber, view);

                    } else if (FILTERED_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        new FilteredViewParser().parse(getContext(), tokens);

                    } else if (tokens.size() > 2 && RELATIONSHIP_TOKEN.equals(tokens.get(1)) && inContext(DynamicViewDslContext.class)) {
                        Relationship relationship = new DynamicViewContentParser().parseRelationship(getContext(DynamicViewDslContext.class), tokens);
                        parserListener.onParsedRelationShip(file, lineNumber, identifier, relationship);

                    } else if (DslContext.CONTEXT_START_TOKEN.equalsIgnoreCase(firstToken) && inContext(DynamicViewDslContext.class)) {
                        startContext(new DynamicViewParallelSequenceDslContext(getContext(DynamicViewDslContext.class)));

                    } else if (INCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(CustomViewDslContext.class)) {
                        new CustomViewContentParser().parseInclude(getContext(CustomViewDslContext.class), tokens);

                    } else if (EXCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(CustomViewDslContext.class)) {
                        new CustomViewContentParser().parseExclude(getContext(CustomViewDslContext.class), tokens);

                    } else if (ANIMATION_STEP_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(CustomViewDslContext.class)) {
                        new CustomViewAnimationStepParser().parse(getContext(CustomViewDslContext.class), tokens);

                    } else if (ANIMATION_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(CustomViewDslContext.class)) {
                        startContext(new CustomViewAnimationDslContext(getContext(CustomViewDslContext.class).getCustomView()));

                    } else if (inContext(CustomViewAnimationDslContext.class)) {
                        new CustomViewAnimationStepParser().parse(getContext(CustomViewAnimationDslContext.class), tokens);

                    } else if (AUTOLAYOUT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(CustomViewDslContext.class)) {
                        new AutoLayoutParser().parse(getContext(CustomViewDslContext.class), tokens);

                    } else if (INCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new StaticViewContentParser().parseInclude(getContext(StaticViewDslContext.class), tokens);

                    } else if (EXCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new StaticViewContentParser().parseExclude(getContext(StaticViewDslContext.class), tokens);

                    } else if (ANIMATION_STEP_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new StaticViewAnimationStepParser().parse(getContext(StaticViewDslContext.class), tokens);

                    } else if (ANIMATION_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        startContext(new StaticViewAnimationDslContext(getContext(StaticViewDslContext.class).getView()));

                    } else if (inContext(StaticViewAnimationDslContext.class)) {
                        new StaticViewAnimationStepParser().parse(getContext(StaticViewAnimationDslContext.class), tokens);

                    } else if (INCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new DeploymentViewContentParser().parseInclude(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (EXCLUDE_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new DeploymentViewContentParser().parseExclude(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (ANIMATION_STEP_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new DeploymentViewAnimationStepParser().parse(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (ANIMATION_IN_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        startContext(new DeploymentViewAnimationDslContext(getContext(DeploymentViewDslContext.class).getView()));

                    } else if (inContext(DeploymentViewAnimationDslContext.class)) {
                        new DeploymentViewAnimationStepParser().parse(getContext(DeploymentViewAnimationDslContext.class), tokens);

                    } else if (AUTOLAYOUT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new AutoLayoutParser().parse(getContext(StaticViewDslContext.class), tokens);

                    } else if (AUTOLAYOUT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DynamicViewDslContext.class)) {
                        new AutoLayoutParser().parse(getContext(DynamicViewDslContext.class), tokens);

                    } else if (AUTOLAYOUT_VIEW_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new AutoLayoutParser().parse(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (VIEW_TITLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(StaticViewDslContext.class)) {
                        new ViewParser().parseTitle(getContext(StaticViewDslContext.class), tokens);

                    } else if (VIEW_TITLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DynamicViewDslContext.class)) {
                        new ViewParser().parseTitle(getContext(DynamicViewDslContext.class), tokens);

                    } else if (VIEW_TITLE_TOKEN.equalsIgnoreCase(firstToken) && inContext(DeploymentViewDslContext.class)) {
                        new ViewParser().parseTitle(getContext(DeploymentViewDslContext.class), tokens);

                    } else if (THEME_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        new ThemeParser().parseTheme(getContext(), tokens);

                    } else if (THEMES_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        new ThemeParser().parseThemes(getContext(), tokens);

                    } else if (TERMINOLOGY_TOKEN.equalsIgnoreCase(firstToken) && inContext(ViewsDslContext.class)) {
                        startContext(new TerminologyDslContext());

                    } else if (ENTERPRISE_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseEnterprise(getContext(), tokens);

                    } else if (PERSON_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parsePerson(getContext(), tokens);

                    } else if (SOFTWARE_SYSTEM_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseSoftwareSystem(getContext(), tokens);

                    } else if (CONTAINER_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseContainer(getContext(), tokens);

                    } else if (COMPONENT_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseComponent(getContext(), tokens);

                    } else if (DEPLOYMENT_NODE_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseDeploymentNode(getContext(), tokens);

                    } else if (INFRASTRUCTURE_NODE_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseInfrastructureNode(getContext(), tokens);

                    } else if (TERMINOLOGY_RELATIONSHIP_TOKEN.equalsIgnoreCase(firstToken) && inContext(TerminologyDslContext.class)) {
                        new TerminologyParser().parseRelationship(getContext(), tokens);

                    } else if (CONFIGURATION_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        startContext(new ConfigurationDslContext());

                    } else if (USERS_TOKEN.equalsIgnoreCase(firstToken) && inContext(ConfigurationDslContext.class)) {
                        startContext(new UsersDslContext());

                    } else if (inContext(UsersDslContext.class)) {
                        new UserRoleParser().parse(getContext(), tokens);

                    } else if (INCLUDE_FILE_TOKEN.equalsIgnoreCase(firstToken)) {
                        if (!restricted || tokens.get(1).startsWith("https://")) {
                            IncludedDslContext context = new IncludedDslContext(file);
                            new IncludeParser().parse(context, tokens);
                            parserListener.onInclude(file, context.getFile());
                            parse(context.getLines(), context.getFile());
                            includeInDslSourceLines = false;
                        }

                    } else if (DOCS_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        if (!restricted) {
                            new DocsParser().parse(getContext(WorkspaceDslContext.class), file, tokens);
                        }

                    } else if (DOCS_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class)) {
                        if (!restricted) {
                            new DocsParser().parse(getContext(SoftwareSystemDslContext.class), file, tokens);
                        }

                    } else if (ADRS_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        if (!restricted) {
                            new AdrsParser().parse(getContext(WorkspaceDslContext.class), file, tokens);
                        }

                    } else if (ADRS_TOKEN.equalsIgnoreCase(firstToken) && inContext(SoftwareSystemDslContext.class)) {
                        if (!restricted) {
                            new AdrsParser().parse(getContext(SoftwareSystemDslContext.class), file, tokens);
                        }

                    } else if (CONSTANT_TOKEN.equalsIgnoreCase(firstToken)) {
                        Constant constant = new ConstantParser().parse(getContext(), tokens);
                        constants.put(constant.getName(), constant);

                    } else if (IDENTIFIERS_TOKEN.equalsIgnoreCase(firstToken) && inContext(WorkspaceDslContext.class)) {
                        setIdentifierScope(new IdentifierScopeParser().parse(getContext(), tokens));

                    } else if (PLUGIN_TOKEN.equalsIgnoreCase(firstToken)) {
                        if (!restricted) {
                            String fullyQualifiedClassName = new PluginParser().parse(getContext(), tokens.withoutContextStartToken());
                            startContext(new PluginDslContext(fullyQualifiedClassName, file.getParentFile()));
                            if (!shouldStartContext(tokens)) {
                                // run the plugin immediately, without looking for parameters
                                endContext();
                            }
                        } else {
                            throw new StructurizrDslParserException("Plugins are not available");
                        }

                    } else if (inContext(PluginDslContext.class)) {
                        new PluginParser().parseParameter(getContext(PluginDslContext.class), tokens);

                    } else if (SCRIPT_TOKEN.equalsIgnoreCase(firstToken)) {
                        if (!restricted) {
                            if (shouldStartContext(tokens)) {
                                // assume this is an inline script
                                String language = new ScriptParser().parseInline(tokens.withoutContextStartToken());
                                startContext(new InlineScriptDslContext(language));
                            } else {
                                String filename = new ScriptParser().parseExternal(tokens);
                                startContext(new ExternalScriptDslContext(file.getParentFile(), filename));
                                endContext();
                            }
                        } else {
                            throw new StructurizrDslParserException("Scripts are not available");
                        }

                    } else {
                        throw new StructurizrDslParserException("Unexpected tokens");
                    }
                }

                if (includeInDslSourceLines) {
                    dslSourceLines.add(line);
                }

                lineNumber++;
            } catch (Exception e) {
                e.printStackTrace();

                if (e.getMessage() != null) {
                    throw new StructurizrDslParserException(e.getMessage(), lineNumber, line);
                } else {
                    throw new StructurizrDslParserException(e.getClass().getSimpleName(), lineNumber, line);
                }
            }
        }
    }

    private String substituteStrings(String token) {
        Matcher m = STRING_SUBSTITUTION_PATTERN.matcher(token);
        while (m.find()) {
            String before = m.group(0);
            String after = null;
            String name = before.substring(2, before.length()-1);
            if (constants.containsKey(name)) {
                after = constants.get(name).getValue();
            } else {
                if (!restricted) {
                    String environmentVariable = System.getenv().get(name);
                    if (environmentVariable != null) {
                        after = environmentVariable;
                    }
                }
            }

            if (after != null) {
                token = token.replace(before, after);
            }
        }

        return token;
    }

    private boolean shouldStartContext(Tokens tokens) {
        return DslContext.CONTEXT_START_TOKEN.equalsIgnoreCase(tokens.get(tokens.size()-1));
    }

    private void startContext(DslContext context) {
        context.setWorkspace(workspace);
        context.setIdentifierRegister(identifersRegister);
        context.setExtendingWorkspace(extendingWorkspace);
        contextStack.push(context);
    }

    private DslContext getContext() {
        if (!contextStack.empty()) {
            return contextStack.peek();
        } else {
            return null;
        }
    }

    private <T> T getContext(Class<T> clazz) throws StructurizrDslParserException {
        if (inContext(clazz)) {
            return (T)contextStack.peek();
        } else {
            throw new StructurizrDslParserException("Expected " + clazz.getName() + " but got " + contextStack.peek().getClass().getName());
        }
    }

    private void endContext() throws StructurizrDslParserException {
        if (!contextStack.empty()) {
            DslContext context = contextStack.pop();            
            context.end();
        } else {
            throw new StructurizrDslParserException("Unexpected end of context");
        }
    }

    private void registerIdentifier(String identifier, Element element) {
        identifersRegister.register(identifier, element);
    }

    private void registerIdentifier(String identifier, Relationship relationship) {
        identifersRegister.register(identifier, relationship);
    }

    private boolean inContext(Class clazz) {
        if (contextStack.empty()) {
            return false;
        }

        return clazz.isAssignableFrom(contextStack.peek().getClass());
    }

}