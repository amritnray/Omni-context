# AutoMCP-Starter

Zero-configuration MCP integration for Spring Boot applications.

This library keeps the existing project name, `spring-boot-mcp-starter`, while positioning the starter as the AutoMCP-Starter approach: drop it into a service, enable Spring auto-configuration, and expose application context, REST endpoint discovery, logs, health, properties, beans, and database metadata to MCP clients.

Project flowchart: [FLOWCHART.md](D:\Omni-context\FLOWCHART.md)

## What It Provides

- Automatic MCP server bootstrap through Spring Boot auto-configuration.
- SSE transport by default, with stdio transport available through configuration.
- Runtime REST endpoint discovery from Spring MVC handler mappings.
- Local REST endpoint invocation through MCP tools.
- Application introspection tools for properties, README content, project structure, Spring beans, database metadata, JVM health, and recent logs.
- Secret masking for sensitive application properties.
- Dynamic MCP tool generation for discovered REST endpoints.

## Starter Positioning

AutoMCP-Starter is designed for enterprise Java teams that want intelligent application observability without writing service-specific MCP glue code.

The intent is:

- Add the dependency.
- Start the Spring Boot application.
- Connect an MCP client.
- Ask the application about its runtime shape and call exposed endpoints through standardized tools.

## Configuration

The starter is enabled by default.

```properties
mcp.server.enabled=true
mcp.server.transport=sse
```

Use stdio transport when integrating with local CLI-based MCP clients:

```properties
mcp.server.transport=stdio
```

### Antigravity

Google Antigravity expects either:

* a `command`-based local MCP server over `stdio`, or
* a remote `serverUrl` that speaks Streamable HTTP.

This starter currently exposes `stdio` and the legacy SSE path, so the most reliable Antigravity setup is to launch the app in `stdio` mode.

Example Antigravity config:

```json
{
  "mcpServers": {
    "simple-java-app": {
      "command": "java",
      "args": [
        "-Dspring.main.banner-mode=off",
        "-Dlogging.level.root=OFF",
        "-jar",
        "D:\\SimpleJavaApp\\build\\libs\\SimpleJavaApp-0.0.1-SNAPSHOT.jar",
        "--mcp.server.transport=stdio"
      ]
    }
  }
}
```

Important:

* JVM flags go before `-jar`.
* Spring Boot app flags go after `-jar` and use `--key=value`.
* If the process prints the Spring banner or startup logs to stdout, Antigravity will try to parse that text as MCP JSON and fail with errors like `invalid character '.' looking for beginning of value`.

If you point Antigravity at a `serverUrl`, it will try to use Streamable HTTP. That will not match this starter's current SSE transport, which is why you may see `session not found` or `initialize` failures in Antigravity while VS Code still works.

## Default MCP Tools

- `get_application_properties`
- `get_project_readme`
- `get_project_structure`
- `get_spring_beans`
- `get_database_metadata`
- `get_app_health`
- `get_recent_logs`
- `list_rest_endpoints`
- `call_rest_endpoint`
- `command_code_login`
- `command_code_status`

## Runtime REST Tools

At startup, the starter scans Spring MVC controller mappings and creates direct MCP tools for discovered REST endpoints. Internal MCP endpoints and Spring Boot's default error controller are excluded from the generated list.

## Project Name

The Gradle project name remains:

```text
spring-boot-mcp-starter
```

AutoMCP-Starter is the product and architecture positioning for the starter, not a rename of the repository or Gradle project.
