package com.example.mcp.autoconfigure;

import com.example.mcp.McpServerManager;
import com.example.mcp.logging.McpLogAppender;
import com.example.mcp.logging.McpLoggingLevelDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import com.example.mcp.transport.SessionOptionalSseTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

import java.util.Iterator;

@AutoConfiguration
@ConditionalOnProperty(prefix = "mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(McpAutoConfiguration.class);

    private final ApplicationContext context;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public McpAutoConfiguration(ApplicationContext context, Environment environment, ObjectMapper objectMapper) {
        this.context = context;
        this.environment = environment;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        SimpleModule mcpModule = new SimpleModule("McpCompatibilityModule");
        mcpModule.addDeserializer(LoggingLevel.class, new McpLoggingLevelDeserializer());
        this.objectMapper.registerModule(mcpModule);
        
        logger.info("McpAutoConfiguration initialized");
    }

    @Bean
    public McpServerManager mcpServerManager() {
        logger.info("Creating McpServerManager bean...");
        registerMcpLogAppender();
        McpServerManager manager = new McpServerManager(context, environment, objectMapper);
        logger.info("McpServerManager bean created successfully");
        return manager;
    }

    @Bean
    public ApplicationListener<WebServerInitializedEvent> webServerInitializedListener(McpServerManager manager) {
        return event -> {
            int port = event.getWebServer().getPort();
            logger.info("Captured dynamic running server port: {}", port);
            if (manager.getRestEndpointsTool() != null) {
                manager.getRestEndpointsTool().setLocalServerPort(port);
                logger.info("Set local server port to {} for REST endpoint calls", port);
            }
        };
    }

    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "mcp.server", name = "transport", havingValue = "sse", matchIfMissing = true)
    public static class SseTransportConfiguration {

        @Bean
        public SessionOptionalSseTransportProvider sessionOptionalSseTransportProvider(ObjectMapper mapper) {
            String sseEndpoint = "/sse";
            String messageEndpoint = "/mcp/message";
            logger.info("Configuring SessionOptionalSseTransportProvider - SSE: {}, Message: {}", sseEndpoint, messageEndpoint);
            return new SessionOptionalSseTransportProvider(mapper, messageEndpoint, sseEndpoint);
        }

        @Bean
        public RouterFunction<ServerResponse> mcpRouterFunction(
                SessionOptionalSseTransportProvider transportProvider,
                McpServerManager serverManager) {

            logger.info("Registering Spring WebMVC RouterFunction for MCP SSE (session-optional)");
            serverManager.startServer(transportProvider);
            return transportProvider.getRouterFunction();
        }

        @Bean
        public WebMvcConfigurer mcpWebMvcConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                    logger.info("Setting default async request timeout to 12 hours for MCP SSE stability");
                    configurer.setDefaultTimeout(43200000L);
                }
            };
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "mcp.server", name = "transport", havingValue = "stdio")
    public static class StdioTransportConfiguration {

        @Bean
        public ApplicationListener<ContextRefreshedEvent> stdioServerStarter(McpServerManager serverManager, ObjectMapper objectMapper) {
            return event -> {
                logger.info("Configuring MCP StdioServerTransportProvider...");
                redirectConsoleLogsToSystemErr();
                new Thread(() -> {
                    try {
                        logger.info("Launching MCP Stdio Server background thread...");
                        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(objectMapper));
                        serverManager.startServer(transport);
                    } catch (Exception e) {
                        logger.error("Failed to start Stdio Server Transport", e);
                    }
                }, "mcp-stdio-runner").start();
            };
        }

        private void redirectConsoleLogsToSystemErr() {
            try {
                ch.qos.logback.classic.LoggerContext ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
                logger.info("Redirecting standard Logback ConsoleAppenders to System.err...");

                for (ch.qos.logback.classic.Logger log : ctx.getLoggerList()) {
                    Iterator<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> appenders = log.iteratorForAppenders();
                    while (appenders.hasNext()) {
                        ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender = appenders.next();
                        if (appender instanceof ch.qos.logback.core.ConsoleAppender) {
                            @SuppressWarnings("unchecked")
                            ch.qos.logback.core.ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> consoleAppender =
                                    (ch.qos.logback.core.ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>) appender;
                            consoleAppender.setTarget("System.err");
                            consoleAppender.start();
                        }
                    }
                }
                logger.info("Standard Console logging successfully redirected to System.err.");
            } catch (Exception e) {
                logger.error("Failed to redirect ConsoleAppenders programmatically", e);
            }
        }
    }

    private void registerMcpLogAppender() {
        try {
            ch.qos.logback.classic.LoggerContext ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

            if (rootLogger.getAppender("McpLogAppender") != null) {
                logger.debug("McpLogAppender already registered, skipping");
                return;
            }

            McpLogAppender appender = new McpLogAppender();
            appender.setContext(ctx);
            appender.setName("McpLogAppender");
            appender.start();

            rootLogger.addAppender(appender);
            logger.info("Successfully registered McpLogAppender to Logback context.");
        } catch (Exception e) {
            logger.error("Failed to register McpLogAppender to Logback context", e);
        }
    }
}
