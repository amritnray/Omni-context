package com.example.mcp.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

/**
 * Antigravity and other stdio MCP clients require stdout to contain only MCP
 * protocol messages. Any banner or log line printed before the MCP handshake
 * will break initialization, so we redirect startup output away from stdout as
 * early as possible when stdio transport is enabled.
 */
public class McpStdioEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String TRANSPORT_PROPERTY = "mcp.server.transport";
    private static final String STDIO_VALUE = "stdio";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String transport = environment.getProperty(TRANSPORT_PROPERTY, "");
        if (!STDIO_VALUE.equalsIgnoreCase(transport)) {
            return;
        }

        // Keep stdout clean for the MCP protocol stream.
        System.setOut(System.err);

        MutablePropertySources propertySources = environment.getPropertySources();
        if (propertySources != null) {
            // No-op placeholder to make the intent explicit and keep the hook
            // ready for future transport-specific defaults.
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
