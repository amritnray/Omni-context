package com.example.mcp;

import com.example.mcp.logging.McpLogAppender;
import com.example.mcp.tools.DeveloperOnboardingTools;
import com.example.mcp.tools.PropertiesTool;
import com.example.mcp.tools.ReadmeTool;
import com.example.mcp.tools.RestEndpointsTool;
import com.example.mcp.tools.CommandCodeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.util.*;

public class McpServerManager {

    private static final Logger logger = LoggerFactory.getLogger(McpServerManager.class);

    private final ApplicationContext context;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    private McpSyncServer mcpServer;
    private RestEndpointsTool restEndpointsTool;
    private PropertiesTool propertiesTool;
    private ReadmeTool readmeTool;
    private DeveloperOnboardingTools onboardingTools;
    private CommandCodeTool commandCodeTool;

    public McpServerManager(ApplicationContext context, Environment environment, ObjectMapper objectMapper) {
        this.context = context;
        this.environment = environment;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public synchronized void startServer(McpServerTransportProvider transport) {
        if (mcpServer != null) {
            logger.warn("MCP Server is already running.");
            return;
        }

        try {
            logger.info("Initializing MCP Server...");

            this.restEndpointsTool = new RestEndpointsTool(context, objectMapper);
            this.propertiesTool = new PropertiesTool(environment);
            this.readmeTool = new ReadmeTool();
            this.onboardingTools = new DeveloperOnboardingTools(context, environment);
            this.commandCodeTool = new CommandCodeTool();

            this.mcpServer = McpServer.sync(transport)
                    .serverInfo("AutoMCP-Starter", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                            .tools(true)
                            .logging()
                            .build())
                    .build();

            registerStandardTools();
            registerDynamicRestTools();

            McpLogAppender.setLogListener(notification -> {
                try {
                    if (mcpServer != null) {
                        mcpServer.loggingNotification(notification);
                    }
                } catch (Exception ignored) {}
            });

            logger.info("MCP Server successfully started.");
        } catch (Exception e) {
            logger.error("Failed to start MCP Server", e);
        }
    }

    public synchronized void stopServer() {
        if (mcpServer != null) {
            try {
                McpLogAppender.setLogListener(null);
                mcpServer.close();
                logger.info("MCP Server stopped.");
            } catch (Exception e) {
                logger.error("Error stopping MCP Server", e);
            } finally {
                mcpServer = null;
            }
        }
    }

    public RestEndpointsTool getRestEndpointsTool() {
        return restEndpointsTool;
    }

    private void registerStandardTools() {
        registerSyncTool(
            "get_application_properties",
            "AutoMCP-Starter tool that lists Spring active properties with passwords, secrets, and keys automatically masked.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                Map<String, Object> props = propertiesTool.getMaskedProperties();
                return new CallToolResult(List.of(new TextContent(toJson(props))), false);
            }
        );

        registerSyncTool(
            "get_project_readme",
            "AutoMCP-Starter tool that exposes the target application root README.md for architecture and data-flow analysis.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                String readme = readmeTool.getReadmeContent();
                return new CallToolResult(List.of(new TextContent(readme)), false);
            }
        );

        registerSyncTool(
            "get_project_structure",
            "AutoMCP-Starter tool that lists the repository structure while excluding common build and IDE folders.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                String structure = onboardingTools.getProjectStructure();
                return new CallToolResult(List.of(new TextContent(structure)), false);
            }
        );

        registerSyncTool(
            "get_spring_beans",
            "AutoMCP-Starter tool that lists custom Spring beans active in the context with class types and dependencies.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                Map<String, Object> beans = onboardingTools.getSpringBeans();
                return new CallToolResult(List.of(new TextContent(toJson(beans))), false);
            }
        );

        registerSyncTool(
            "get_database_metadata",
            "AutoMCP-Starter tool that inspects the active DataSource and extracts database tables, columns, nullability, and types.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                Map<String, Object> metadata = onboardingTools.getDatabaseMetadata();
                return new CallToolResult(List.of(new TextContent(toJson(metadata))), false);
            }
        );

        registerSyncTool(
            "get_app_health",
            "AutoMCP-Starter tool that exposes JVM metrics and current Spring profile information.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                Map<String, Object> health = onboardingTools.getAppHealth();
                return new CallToolResult(List.of(new TextContent(toJson(health))), false);
            }
        );

        registerSyncTool(
            "get_recent_logs",
            "AutoMCP-Starter tool that retrieves the last 200 buffered log records from the running target application.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                List<String> logs = getRecentLogsFromAppender();
                String content = String.join("\n", logs);
                return new CallToolResult(List.of(new TextContent(content.isEmpty() ? "No logs buffered yet." : content)), false);
            }
        );

        registerSyncTool(
            "list_rest_endpoints",
            "AutoMCP-Starter tool that lists Spring controller REST endpoints with paths, methods, parameters, and request/response schemas.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                List<Map<String, Object>> list = restEndpointsTool.listRestEndpoints();
                return new CallToolResult(List.of(new TextContent(toJson(list))), false);
            }
        );

        registerSyncTool(
            "call_rest_endpoint",
            "AutoMCP-Starter tool that invokes REST endpoints of the running target application locally. Required args: method, path. Optional args: headers, queryParams, body.",
            "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"method\": { \"type\": \"string\", \"description\": \"HTTP Method (GET, POST, etc.)\" },\n" +
            "    \"path\": { \"type\": \"string\", \"description\": \"URL path (e.g. /api/query)\" },\n" +
            "    \"headers\": { \"type\": \"object\", \"description\": \"Optional request headers\" },\n" +
            "    \"queryParams\": { \"type\": \"object\", \"description\": \"Optional query parameters\" },\n" +
            "    \"body\": { \"type\": \"string\", \"description\": \"Optional string/JSON request body\" }\n" +
            "  },\n" +
            "  \"required\": [\"method\", \"path\"]\n" +
            "}",
            (exchange, args) -> {
                String method = (String) args.get("method");
                String path = (String) args.get("path");

                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) args.get("headers");

                @SuppressWarnings("unchecked")
                Map<String, String> queryParams = (Map<String, String>) args.get("queryParams");

                String body = (String) args.get("body");

                Map<String, Object> result = restEndpointsTool.callRestEndpoint(method, path, headers, queryParams, body);
                return new CallToolResult(List.of(new TextContent(toJson(result))), false);
            }
        );

        registerSyncTool(
            "command_code_login",
            "Authenticates the environment with Command Code CLI. Accepts an optional 'apiKey' parameter. If omitted, starts interactive browser login and returns the URL.",
            "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"apiKey\": { \"type\": \"string\", \"description\": \"Optional. Manual Command Code API Key\" }\n" +
            "  }\n" +
            "}",
            (exchange, args) -> {
                String apiKey = (String) args.get("apiKey");
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    Map<String, Object> res = commandCodeTool.saveApiKey(apiKey);
                    return new CallToolResult(List.of(new TextContent(toJson(res))), false);
                } else {
                    Map<String, Object> res = commandCodeTool.startCliLogin();
                    return new CallToolResult(List.of(new TextContent(toJson(res))), false);
                }
            }
        );

        registerSyncTool(
            "command_code_status",
            "Checks if the local system is currently authenticated with the Command Code CLI.",
            "{\"type\":\"object\",\"properties\":{}}",
            (exchange, args) -> {
                Map<String, Object> res = commandCodeTool.getStatus();
                return new CallToolResult(List.of(new TextContent(toJson(res))), false);
            }
        );
    }

    private void registerDynamicRestTools() {
        try {
            Set<String> registeredToolNames = new HashSet<>();
            List<Map<String, Object>> endpoints = restEndpointsTool.listRestEndpoints();
            for (Map<String, Object> endpoint : endpoints) {
                String path = (String) endpoint.get("path");
                String method = (String) endpoint.get("method");

                String cleanPath = path.replaceAll("[^a-zA-Z0-9]", "_")
                                       .replaceAll("_+", "_")
                                       .replaceAll("^_|_$", "");
                String toolName = "http_" + method.toLowerCase() + "_" + (cleanPath.isEmpty() ? "root" : cleanPath);

                if (registeredToolNames.contains(toolName)) {
                    logger.debug("Skipping duplicate dynamic tool mapping: {}", toolName);
                    continue;
                }
                registeredToolNames.add(toolName);

                String description = String.format("Directly calls local %s %s. Class: %s, Method: %s.",
                        method, path, endpoint.get("handlerClass"), endpoint.get("handlerMethod"));

                Map<String, Object> inputSchema = new LinkedHashMap<>();
                inputSchema.put("type", "object");
                Map<String, Object> properties = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();

                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> parameters = (Map<String, Map<String, Object>>) endpoint.get("parameters");
                if (parameters != null) {
                    for (Map.Entry<String, Map<String, Object>> param : parameters.entrySet()) {
                        String name = param.getKey();
                        Map<String, Object> details = param.getValue();

                        Map<String, Object> propDetails = new LinkedHashMap<>();
                        propDetails.put("type", details.get("type"));
                        propDetails.put("description", String.format("Request parameter (%s)", details.get("in")));

                        properties.put(name, propDetails);
                        if (Boolean.TRUE.equals(details.get("required"))) {
                            required.add(name);
                        }
                    }
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> requestBody = (Map<String, Object>) endpoint.get("requestBody");
                if (requestBody != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> schemaMap = (Map<String, Object>) requestBody.get("schema");
                    properties.put("requestBody", Map.of(
                        "type", "object",
                        "description", "JSON body payload corresponding to type: " + requestBody.get("type"),
                        "properties", schemaMap != null ? schemaMap.getOrDefault("properties", new HashMap<>()) : new HashMap<>()
                    ));
                }

                inputSchema.put("properties", properties);
                if (!required.isEmpty()) {
                    inputSchema.put("required", required);
                }

                String finalToolName = toolName;
                String finalDescription = description;
                String finalPath = path;
                Map<String, Map<String, Object>> finalParameters = parameters;

                registerSyncTool(
                    finalToolName,
                    finalDescription,
                    toJson(inputSchema),
                    (exchange, args) -> {
                        Map<String, String> qParams = new HashMap<>();
                        Map<String, String> pathParams = new HashMap<>();
                        String bodyPayload = null;

                        if (finalParameters != null) {
                            for (Map.Entry<String, Map<String, Object>> param : finalParameters.entrySet()) {
                                String name = param.getKey();
                                Map<String, Object> details = param.getValue();
                                Object val = args.get(name);

                                if (val != null) {
                                    if ("query".equals(details.get("in"))) {
                                        qParams.put(name, val.toString());
                                    } else if ("path".equals(details.get("in"))) {
                                        pathParams.put(name, val.toString());
                                    }
                                }
                            }
                        }

                        if (args.containsKey("requestBody")) {
                            bodyPayload = toJson(args.get("requestBody"));
                        }

                        String activePath = finalPath;
                        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                            activePath = activePath.replace("{" + entry.getKey() + "}", entry.getValue());
                        }

                        Map<String, Object> result = restEndpointsTool.callRestEndpoint(method, activePath, null, qParams, bodyPayload);
                        return new CallToolResult(List.of(new TextContent(toJson(result))), false);
                    }
                );
            }
        } catch (Exception e) {
            logger.error("Failed to register dynamic REST tools", e);
        }
    }

    private void registerSyncTool(String name, String description, String jsonSchema,
            java.util.function.BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, Map<String, Object>, CallToolResult> handler) {
        Tool tool = new Tool(name, description, jsonSchema);
        mcpServer.addTool(new SyncToolSpecification(tool, handler));
    }

    private List<String> getRecentLogsFromAppender() {
        try {
            ch.qos.logback.classic.LoggerContext ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

            Iterator<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> iterator = rootLogger.iteratorForAppenders();
            while (iterator.hasNext()) {
                ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender = iterator.next();
                if (appender instanceof McpLogAppender) {
                    return ((McpLogAppender) appender).getRecentLogs();
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"Serialization failed: " + e.getMessage() + "\"}";
        }
    }
}
