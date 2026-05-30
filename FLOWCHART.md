# Project Flowchart

```mermaid
flowchart TD
    A[Spring Boot App Starts] --> B[AutoConfiguration Loads]
    B --> C[McpAutoConfiguration]
    C --> D[McpServerManager Bean]
    C --> E[Register McpLogAppender]
    C --> F[Capture Web Server Port]

    B --> G{mcp.server.transport}
    G -->|sse| H[SSE Transport Configuration]
    G -->|stdio| I[Stdio Transport Configuration]

    H --> J[SessionOptionalSseTransportProvider]
    J --> K[Create MCP Server]
    K --> L[Register Standard Tools]
    K --> M[Discover REST Endpoints]
    M --> N[Register Dynamic HTTP Tools]
    J --> O[/sse endpoint]
    J --> P[/mcp/message endpoint]

    I --> Q[StdioServerTransportProvider]
    Q --> K
    Q --> R[Start Background MCP Stdio Thread]

    L --> S[get_application_properties]
    L --> T[get_project_readme]
    L --> U[get_project_structure]
    L --> V[get_spring_beans]
    L --> W[get_database_metadata]
    L --> X[get_app_health]
    L --> Y[get_recent_logs]
    L --> Z[list_rest_endpoints]
    L --> AA[call_rest_endpoint]
    L --> AB[command_code_login]
    L --> AC[command_code_status]

    N --> AD[http_* Dynamic Tools]

    AE[MCP Client] --> AF{Client Type}
    AF -->|VS Code / SSE| O
    AF -->|Antigravity / CLI| I

    X --> AG[Runtime Health Data]
    W --> AH[Database Metadata]
    Y --> AI[Buffered Log Records]
    Z --> AJ[Discovered Spring MVC Mappings]
```

## Reading Guide

- `McpAutoConfiguration` wires the starter into Spring Boot.
- `mcp.server.transport` decides whether the server runs over `sse` or `stdio`.
- `McpServerManager` builds the MCP server and registers all standard tools.
- `RestEndpointsTool` discovers controller routes and turns them into dynamic `http_*` tools.
- `get_app_health` exposes JVM and active profile info.
- `stdio` is the safest option for local MCP clients like Antigravity.

