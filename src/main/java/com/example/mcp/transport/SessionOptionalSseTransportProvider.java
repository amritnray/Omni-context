package com.example.mcp.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A custom SSE server transport provider that makes sessionId optional on the
 * message endpoint. When sessionId is missing, it falls back to the first (or only)
 * active session. This avoids the "Session ID missing in message endpoint" error
 * for clients that don't follow the SSE handshake flow properly.
 *
 * <p>This replaces the SDK's {@code WebMvcSseServerTransportProvider} which strictly
 * requires sessionId on every POST request.
 */
public class SessionOptionalSseTransportProvider implements McpServerTransportProvider {

    private static final Logger logger = LoggerFactory.getLogger(SessionOptionalSseTransportProvider.class);

    public static final String MESSAGE_EVENT_TYPE = "message";
    public static final String ENDPOINT_EVENT_TYPE = "endpoint";

    private final ObjectMapper objectMapper;
    private final String messageEndpoint;
    private final String sseEndpoint;
    private final String baseUrl;
    private final RouterFunction<ServerResponse> routerFunction;

    private McpServerSession.Factory sessionFactory;
    private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean isClosing;

    public SessionOptionalSseTransportProvider(ObjectMapper objectMapper, String messageEndpoint) {
        this(objectMapper, messageEndpoint, "/sse");
    }

    public SessionOptionalSseTransportProvider(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
        this(objectMapper, "", messageEndpoint, sseEndpoint);
    }

    public SessionOptionalSseTransportProvider(ObjectMapper objectMapper, String baseUrl,
                                                String messageEndpoint, String sseEndpoint) {
        if (objectMapper == null) throw new IllegalArgumentException("ObjectMapper must not be null");
        if (baseUrl == null) throw new IllegalArgumentException("Base URL must not be null");
        if (messageEndpoint == null) throw new IllegalArgumentException("Message endpoint must not be null");
        if (sseEndpoint == null) throw new IllegalArgumentException("SSE endpoint must not be null");

        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.messageEndpoint = messageEndpoint;
        this.sseEndpoint = sseEndpoint;

        this.routerFunction = RouterFunctions.route()
                .GET(this.sseEndpoint, this::handleSseConnection)
                .POST(this.messageEndpoint, this::handleMessage)
                .build();
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());
        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendNotification(method, params)
                        .doOnError(e -> logger.error("Failed to send message to session {}: {}",
                                session.getId(), e.getMessage()))
                        .onErrorComplete())
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Flux.fromIterable(sessions.values())
                .doFirst(() -> {
                    this.isClosing = true;
                    logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
                })
                .flatMap(McpServerSession::closeGracefully)
                .then()
                .doOnSuccess(v -> logger.debug("Graceful shutdown completed"));
    }

    public RouterFunction<ServerResponse> getRouterFunction() {
        return this.routerFunction;
    }

    private ServerResponse handleSseConnection(ServerRequest request) {
        if (isClosing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Server is shutting down");
        }

        String sessionId = UUID.randomUUID().toString();
        logger.debug("Creating new SSE connection for session: {}", sessionId);

        try {
            return ServerResponse.sse(sseBuilder -> {
                sseBuilder.onComplete(() -> {
                    logger.debug("SSE connection completed for session: {}", sessionId);
                    sessions.remove(sessionId);
                });
                sseBuilder.onTimeout(() -> {
                    logger.debug("SSE connection timed out for session: {}", sessionId);
                    sessions.remove(sessionId);
                });

                // Create transport and session
                WebMvcMcpSessionTransport transport = new WebMvcMcpSessionTransport(sessionId, sseBuilder);
                McpServerSession session = sessionFactory.create(transport);
                sessions.put(sessionId, session);

                try {
                    // Send the endpoint event telling the client where to send messages
                    sseBuilder.id(sessionId);
                    sseBuilder.event(ENDPOINT_EVENT_TYPE);
                    sseBuilder.data(baseUrl + messageEndpoint + "?sessionId=" + sessionId);
                } catch (Exception e) {
                    logger.error("Failed to send initial endpoint event: {}", e.getMessage());
                    sseBuilder.error(e);
                }
            }, Duration.ZERO);
        } catch (Exception e) {
            logger.error("Failed to send initial endpoint event to session {}: {}", sessionId, e.getMessage());
            sessions.remove(sessionId);
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ServerResponse handleMessage(ServerRequest request) {
        if (isClosing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Server is shutting down");
        }

        // --- SESSION ID IS NOW OPTIONAL ---
        String sessionId = request.param("sessionId").orElse(null);
        McpServerSession session = null;

        if (sessionId != null && !sessionId.isEmpty()) {
            // Normal path: sessionId provided, look it up
            session = sessions.get(sessionId);
            if (session == null) {
                return ServerResponse.status(HttpStatus.NOT_FOUND)
                        .body(new McpError("Session not found: " + sessionId));
            }
        } else {
            // Fallback path: sessionId missing, pick the first (or only) active session
            if (sessions.isEmpty()) {
                logger.warn("No session ID provided and no active sessions available. "
                        + "Client should first connect to the SSE endpoint.");
                return ServerResponse.badRequest()
                        .body(new McpError("No active sessions. Please connect to " + sseEndpoint + " first."));
            }
            Map.Entry<String, McpServerSession> firstEntry = sessions.entrySet().iterator().next();
            session = firstEntry.getValue();
            logger.debug("No sessionId provided, falling back to session: {}", firstEntry.getKey());
        }

        try {
            String body = request.body(String.class);
            logger.debug("Received message on /mcp/message endpoint ({} bytes)", body.length());
            
            long startTime = System.currentTimeMillis();
            JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);
            
            logger.debug("Deserialized message, handling...");
            session.handle(message).block();
            
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Message handled successfully in {}ms", duration);
            
            if (duration > 30000) {
                logger.warn("Message handling took {}ms which exceeds 30 seconds", duration);
            }
            
            return ServerResponse.ok().build();
        } catch (IllegalArgumentException | java.io.IOException e) {
            logger.error("Failed to deserialize message: {}", e.getMessage());
            return ServerResponse.badRequest()
                    .body(new McpError("Invalid message format"));
        } catch (Exception e) {
            logger.error("Error handling message: {}", e.getMessage(), e);
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new McpError(e.getMessage()));
        }
    }

    /**
     * Inner transport that sends SSE events to a specific client session.
     */
    private class WebMvcMcpSessionTransport implements McpServerTransport {

        private final String sessionId;
        private final ServerResponse.SseBuilder sseBuilder;

        WebMvcMcpSessionTransport(String sessionId, ServerResponse.SseBuilder sseBuilder) {
            this.sessionId = sessionId;
            this.sseBuilder = sseBuilder;
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    String jsonText = objectMapper.writeValueAsString(message);
                    sseBuilder.id(sessionId);
                    sseBuilder.event(MESSAGE_EVENT_TYPE);
                    sseBuilder.data(jsonText);
                } catch (Exception e) {
                    logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
                    throw new RuntimeException("Failed to send SSE message", e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                try {
                    sseBuilder.complete();
                } catch (Exception e) {
                    logger.debug("Error completing SSE connection for session {}: {}", sessionId, e.getMessage());
                }
                sessions.remove(sessionId);
            });
        }

        @Override
        public void close() {
            try {
                sseBuilder.complete();
            } catch (Exception e) {
                logger.debug("Error completing SSE connection for session {}: {}", sessionId, e.getMessage());
            }
            sessions.remove(sessionId);
        }
    }
}
