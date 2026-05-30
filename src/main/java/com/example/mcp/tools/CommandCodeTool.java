package com.example.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool for managing authentication and integration with the Command Code CLI.
 */
public class CommandCodeTool {

    private static final String AUTH_DIR = ".commandcode";
    private static final String AUTH_FILE = "auth.json";

    private final ObjectMapper objectMapper;

    public CommandCodeTool() {
        this.objectMapper = new ObjectMapper();
    }

    public CommandCodeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Resolves the path to the Command Code auth.json file.
     */
    private Path getAuthFilePath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, AUTH_DIR, AUTH_FILE);
    }

    /**
     * Saves the provided API key to the ~/.commandcode/auth.json file.
     */
    public Map<String, Object> saveApiKey(String apiKey) {
        Map<String, Object> result = new HashMap<>();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "API Key cannot be empty.");
            return result;
        }

        try {
            Path filePath = getAuthFilePath();
            Files.createDirectories(filePath.getParent());

            // Write as standard JSON: {"apiKey": "YOUR_KEY"}
            String jsonContent = String.format("{\n  \"apiKey\": \"%s\"\n}", apiKey.trim());
            Files.writeString(filePath, jsonContent, StandardCharsets.UTF_8);

            result.put("success", true);
            result.put("message", "API Key successfully saved to " + filePath.toAbsolutePath());
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "Failed to save API Key: " + e.getMessage());
        }

        return result;
    }

    /**
     * Returns the current login status and the masked API key if authenticated.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        Path filePath = getAuthFilePath();

        if (!Files.exists(filePath)) {
            status.put("authenticated", false);
            status.put("message", "Not authenticated. No credentials file found at " + filePath.toAbsolutePath());
            return status;
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> authData = objectMapper.readValue(content, Map.class);
            String apiKey = (String) authData.get("apiKey");

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                status.put("authenticated", true);
                status.put("apiKeyMasked", maskApiKey(apiKey));
                status.put("message", "Successfully authenticated using saved API Key.");
            } else {
                status.put("authenticated", false);
                status.put("message", "Credentials file found, but API key format is invalid.");
            }
        } catch (Exception e) {
            status.put("authenticated", false);
            status.put("message", "Failed to read credentials file: " + e.getMessage());
        }

        return status;
    }

    /**
     * Spawns command-code.cmd login in the background, intercepts the login URL, and returns it.
     */
    public Map<String, Object> startCliLogin() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Run command-code.cmd login
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "command-code.cmd login");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read the output in a non-blocking way to search for login URL
            InputStream is = process.getInputStream();
            StringBuilder output = new StringBuilder();
            long endTime = System.currentTimeMillis() + 4000; // wait up to 4 seconds
            byte[] buffer = new byte[1024];

            while (System.currentTimeMillis() < endTime) {
                if (is.available() > 0) {
                    int read = is.read(buffer);
                    if (read > 0) {
                        String part = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        output.append(part);
                    }
                } else {
                    Thread.sleep(100);
                }
            }

            // Always destroy the login process so it doesn't leak or hang indefinitely
            process.destroyForcibly();

            String fullOutput = output.toString();
            String loginUrl = extractUrl(fullOutput);

            if (loginUrl != null) {
                result.put("success", true);
                result.put("loginUrl", loginUrl);
                result.put("message", "Login URL successfully captured.");
            } else {
                result.put("success", false);
                result.put("message", "Could not find a login URL in CLI output.");
                result.put("cliOutput", fullOutput);
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to launch Command Code login: " + e.getMessage());
        }

        return result;
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) {
            return "********";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    private String extractUrl(String text) {
        Pattern pattern = Pattern.compile("https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
