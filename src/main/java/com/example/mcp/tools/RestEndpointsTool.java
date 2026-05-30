package com.example.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Automatically scans all REST endpoints of the application, generates schemas,
 * and allows executing calls dynamically against the running local server.
 */
public class RestEndpointsTool {

    private final ApplicationContext context;
    private final ObjectMapper objectMapper;
    private Integer localServerPort;

    public RestEndpointsTool(ApplicationContext context, ObjectMapper objectMapper) {
        this.context = context;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public void setLocalServerPort(int port) {
        this.localServerPort = port;
    }

    /**
     * Scans and returns details of all REST endpoints in a Swagger-like format.
     */
    public List<Map<String, Object>> listRestEndpoints() {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        RequestMappingHandlerMapping mapping = getHandlerMapping();

        if (mapping == null) {
            return endpoints;
        }

        Map<RequestMappingInfo, HandlerMethod> handlerMethods = mapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod handlerMethod = entry.getValue();

            // Ignore our own internal MCP endpoints
            if (handlerMethod.getBeanType().getName().contains("com.example.mcp")) {
                continue;
            }

            // Ignore Spring Boot's default error controller to reduce noise
            if (handlerMethod.getBeanType().getName().contains("BasicErrorController")) {
                continue;
            }

            Set<String> paths = getPaths(mappingInfo);
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = mappingInfo.getMethodsCondition().getMethods();

            for (String path : paths) {
                // If HTTP methods condition is empty, it means all methods are supported.
                List<String> httpMethods = new ArrayList<>();
                if (methods.isEmpty()) {
                    httpMethods.addAll(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
                } else {
                    for (org.springframework.web.bind.annotation.RequestMethod method : methods) {
                        httpMethods.add(method.name());
                    }
                }

                for (String httpMethod : httpMethods) {
                    Map<String, Object> endpointDetails = new LinkedHashMap<>();
                    endpointDetails.put("path", path);
                    endpointDetails.put("method", httpMethod);
                    endpointDetails.put("handlerClass", handlerMethod.getBeanType().getSimpleName());
                    endpointDetails.put("handlerMethod", handlerMethod.getMethod().getName());

                    // Parse parameters
                    Map<String, Object> parameters = parseParameters(handlerMethod);
                    if (!parameters.isEmpty()) {
                        endpointDetails.put("parameters", parameters);
                    }

                    // Parse request body
                    Map<String, Object> requestBody = parseRequestBody(handlerMethod);
                    if (requestBody != null) {
                        endpointDetails.put("requestBody", requestBody);
                    }

                    // Parse response body
                    Map<String, Object> responseBody = parseResponseBody(handlerMethod);
                    if (responseBody != null) {
                        endpointDetails.put("responseBody", responseBody);
                    }

                    endpoints.add(endpointDetails);
                }
            }
        }

        return endpoints;
    }

    /**
     * Executes a REST endpoint locally.
     */
    public Map<String, Object> callRestEndpoint(String method, String path, Map<String, String> headers, Map<String, String> queryParams, String body) {
        Map<String, Object> responseMap = new LinkedHashMap<>();
        
        if (localServerPort == null || localServerPort <= 0) {
            // Check if we can extract from target environment directly
            try {
                org.springframework.core.env.Environment env = context.getEnvironment();
                String port = env.getProperty("local.server.port");
                if (port == null) {
                    port = env.getProperty("server.port");
                }
                if (port != null) {
                    localServerPort = Integer.parseInt(port);
                }
            } catch (Exception ignored) {}
            
            if (localServerPort == null || localServerPort <= 0) {
                // Default fallback
                localServerPort = 8080;
            }
        }

        try {
            // Build URI with Query Parameters
            StringBuilder urlBuilder = new StringBuilder("http://localhost:").append(localServerPort).append(path);
            if (queryParams != null && !queryParams.isEmpty()) {
                urlBuilder.append("?");
                boolean first = true;
                for (Map.Entry<String, String> q : queryParams.entrySet()) {
                    if (!first) {
                        urlBuilder.append("&");
                    }
                    urlBuilder.append(URLEncoder.encode(q.getKey(), StandardCharsets.UTF_8))
                              .append("=")
                              .append(URLEncoder.encode(q.getValue(), StandardCharsets.UTF_8));
                    first = false;
                }
            }

            HttpClient client = HttpClient.newBuilder().build();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()));

            // Set Body
            HttpRequest.BodyPublisher bodyPublisher;
            if (body != null && !body.trim().isEmpty()) {
                bodyPublisher = HttpRequest.BodyPublishers.ofString(body);
                requestBuilder.header("Content-Type", "application/json");
            } else {
                bodyPublisher = HttpRequest.BodyPublishers.noBody();
            }

            // Set HTTP Method
            requestBuilder.method(method.toUpperCase(), bodyPublisher);

            // Set custom headers
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            }

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            responseMap.put("status", response.statusCode());
            
            Map<String, List<String>> responseHeaders = new TreeMap<>();
            response.headers().map().forEach(responseHeaders::put);
            responseMap.put("headers", responseHeaders);

            String responseBody = response.body();
            try {
                // Attempt to parse response body as JSON
                Object jsonBody = objectMapper.readValue(responseBody, Object.class);
                responseMap.put("body", jsonBody);
            } catch (Exception e) {
                responseMap.put("body", responseBody);
            }

        } catch (Exception e) {
            responseMap.put("error", "Failed to execute call");
            responseMap.put("details", e.getMessage() != null ? e.getMessage() : e.toString());
        }

        return responseMap;
    }

    private RequestMappingHandlerMapping getHandlerMapping() {
        try {
            return context.getBean(RequestMappingHandlerMapping.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Set<String> getPaths(RequestMappingInfo mappingInfo) {
        Set<String> paths = new LinkedHashSet<>();
        
        // Try direct paths (Spring Boot 3+)
        try {
            Set<String> directPaths = mappingInfo.getDirectPaths();
            if (directPaths != null && !directPaths.isEmpty()) {
                return directPaths;
            }
        } catch (Throwable ignored) {}

        // Fallback to pattern mapping
        try {
            if (mappingInfo.getPatternsCondition() != null) {
                paths.addAll(mappingInfo.getPatternsCondition().getPatterns());
            }
        } catch (Throwable ignored) {}
        
        // In case nothing is captured, put default empty or root
        if (paths.isEmpty()) {
            paths.add("/");
        }

        return paths;
    }

    private Map<String, Object> parseParameters(HandlerMethod handlerMethod) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Parameter[] params = handlerMethod.getMethod().getParameters();
        MethodParameter[] methodParameters = handlerMethod.getMethodParameters();

        for (int i = 0; i < params.length; i++) {
            Parameter parameter = params[i];
            MethodParameter methodParam = methodParameters[i];

            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);

            if (requestParam != null) {
                String paramName = getRequestParamName(requestParam, parameter);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("in", "query");
                details.put("type", getSimpleTypeName(parameter.getType()));
                details.put("required", requestParam.required());
                if (!org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue())) {
                    details.put("defaultValue", requestParam.defaultValue());
                }
                parameters.put(paramName, details);
            } else if (pathVariable != null) {
                String paramName = getPathVariableName(pathVariable, parameter);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("in", "path");
                details.put("type", getSimpleTypeName(parameter.getType()));
                details.put("required", true); // Path variables are always required
                parameters.put(paramName, details);
            }
        }

        return parameters;
    }

    private String getRequestParamName(RequestParam anno, Parameter param) {
        if (!anno.value().isEmpty()) return anno.value();
        if (!anno.name().isEmpty()) return anno.name();
        return param.getName();
    }

    private String getPathVariableName(PathVariable anno, Parameter param) {
        if (!anno.value().isEmpty()) return anno.value();
        if (!anno.name().isEmpty()) return anno.name();
        return param.getName();
    }

    private Map<String, Object> parseRequestBody(HandlerMethod handlerMethod) {
        for (MethodParameter methodParam : handlerMethod.getMethodParameters()) {
            if (methodParam.hasParameterAnnotation(RequestBody.class)) {
                Class<?> paramType = methodParam.getParameterType();
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", paramType.getSimpleName());
                schema.put("schema", generateJsonSchema(paramType, new HashSet<>()));
                return schema;
            }
        }
        return null;
    }

    private Map<String, Object> parseResponseBody(HandlerMethod handlerMethod) {
        Class<?> returnType = handlerMethod.getReturnType().getParameterType();
        if (returnType.equals(Void.TYPE) || returnType.equals(Void.class)) {
            return null;
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", returnType.getSimpleName());
        
        // If it is a generic type, e.g. List<Employee>, we want to extract generic arguments
        Type genericReturnType = handlerMethod.getReturnType().getGenericParameterType();
        schema.put("schema", generateJsonSchema(genericReturnType, new HashSet<>()));
        return schema;
    }

    private String getSimpleTypeName(Class<?> clazz) {
        if (clazz.equals(String.class) || clazz.equals(Character.class) || clazz.equals(char.class)) {
            return "string";
        } else if (clazz.equals(Integer.class) || clazz.equals(int.class) || clazz.equals(Long.class) || clazz.equals(long.class) || clazz.equals(Short.class) || clazz.equals(short.class) || clazz.equals(Byte.class) || clazz.equals(byte.class)) {
            return "integer";
        } else if (clazz.equals(Double.class) || clazz.equals(double.class) || clazz.equals(Float.class) || clazz.equals(float.class)) {
            return "number";
        } else if (clazz.equals(Boolean.class) || clazz.equals(boolean.class)) {
            return "boolean";
        } else if (Collection.class.isAssignableFrom(clazz) || clazz.isArray()) {
            return "array";
        } else {
            return "object";
        }
    }

    private Map<String, Object> generateJsonSchema(Type type, Set<Type> visited) {
        Map<String, Object> schema = new LinkedHashMap<>();

        if (type == null) {
            schema.put("type", "null");
            return schema;
        }

        if (visited.contains(type)) {
            schema.put("type", "object");
            schema.put("description", "Circular dependency reference to " + type.getTypeName());
            return schema;
        }

        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;

            if (clazz.equals(String.class) || clazz.equals(Character.class) || clazz.equals(char.class)) {
                schema.put("type", "string");
                return schema;
            } else if (clazz.equals(Integer.class) || clazz.equals(int.class) || clazz.equals(Long.class) || clazz.equals(long.class) || clazz.equals(Short.class) || clazz.equals(short.class) || clazz.equals(Byte.class) || clazz.equals(byte.class)) {
                schema.put("type", "integer");
                return schema;
            } else if (clazz.equals(Double.class) || clazz.equals(double.class) || clazz.equals(Float.class) || clazz.equals(float.class)) {
                schema.put("type", "number");
                return schema;
            } else if (clazz.equals(Boolean.class) || clazz.equals(boolean.class)) {
                schema.put("type", "boolean");
                return schema;
            } else if (clazz.isArray()) {
                schema.put("type", "array");
                schema.put("items", generateJsonSchema(clazz.getComponentType(), visited));
                return schema;
            } else if (Collection.class.isAssignableFrom(clazz)) {
                schema.put("type", "array");
                schema.put("items", Map.of("type", "string")); // Generic fallback
                return schema;
            } else if (Map.class.isAssignableFrom(clazz)) {
                schema.put("type", "object");
                schema.put("additionalProperties", true);
                return schema;
            } else {
                // Custom Java Bean / Entity
                visited.add(clazz);
                schema.put("type", "object");
                Map<String, Object> properties = new LinkedHashMap<>();
                
                // Inspect fields recursively
                for (Field field : clazz.getDeclaredFields()) {
                    // Ignore compiler-generated fields or static fields
                    if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    properties.put(field.getName(), generateJsonSchema(field.getGenericType(), new HashSet<>(visited)));
                }
                
                schema.put("properties", properties);
                visited.remove(clazz);
                return schema;
            }
        } else if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type rawType = paramType.getRawType();

            if (rawType instanceof Class) {
                Class<?> rawClass = (Class<?>) rawType;
                if (Collection.class.isAssignableFrom(rawClass)) {
                    schema.put("type", "array");
                    Type[] actualArgs = paramType.getActualTypeArguments();
                    if (actualArgs.length > 0) {
                        schema.put("items", generateJsonSchema(actualArgs[0], visited));
                    } else {
                        schema.put("items", Map.of("type", "string"));
                    }
                    return schema;
                } else if (Map.class.isAssignableFrom(rawClass)) {
                    schema.put("type", "object");
                    schema.put("additionalProperties", true);
                    return schema;
                }
            }
            // Generic class fallback
            return generateJsonSchema(rawType, visited);
        }

        schema.put("type", "object");
        return schema;
    }
}
