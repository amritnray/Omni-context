package com.example.mcp.logging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;

import java.io.IOException;

public class McpLoggingLevelDeserializer extends JsonDeserializer<LoggingLevel> {

    @Override
    public LoggingLevel deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.getCurrentToken();
        String levelStr = null;

        if (token == JsonToken.START_OBJECT) {
            JsonNode node = p.readValueAsTree();
            JsonNode levelNode = node.get("level");
            if (levelNode != null) {
                levelStr = levelNode.asText();
            }
        } else if (token == JsonToken.VALUE_STRING) {
            levelStr = p.getValueAsString();
        }

        if (levelStr != null) {
            levelStr = levelStr.trim().toUpperCase();
            if (levelStr.equals("WARN") || levelStr.equals("WARNING")) {
                return LoggingLevel.WARNING;
            }
            try {
                return LoggingLevel.valueOf(levelStr);
            } catch (IllegalArgumentException e) {
                return LoggingLevel.INFO;
            }
        }

        return LoggingLevel.INFO;
    }
}
