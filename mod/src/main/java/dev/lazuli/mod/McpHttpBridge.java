package dev.lazuli.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Local-only HTTP transport used by the stdio MCP adapter. */
final class McpHttpBridge {
    private static final Gson JSON = new Gson();
    private final Function<JsonObject, JsonObject> handler;
    private HttpServer server;
    private int port = -1;

    McpHttpBridge(Function<JsonObject, JsonObject> handler) {
        this.handler = handler;
    }

    synchronized void start(int requestedPort) throws IOException {
        if (server != null && port == requestedPort) return;
        stop();
        HttpServer next = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        next.createContext("/call", this::call);
        next.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "lazuli-mcp-http");
            thread.setDaemon(true);
            return thread;
        }));
        next.start();
        server = next;
        port = requestedPort;
    }

    synchronized void stop() {
        if (server != null) server.stop(0);
        server = null;
        port = -1;
    }

    private void call(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, error("POST is required"));
            return;
        }
        try (InputStream input = exchange.getRequestBody()) {
            JsonObject request = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            send(exchange, 200, handler.apply(request));
        } catch (Exception e) {
            send(exchange, 400, error(e.getMessage() == null ? "Invalid request" : e.getMessage()));
        }
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static JsonObject success(Object result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("result", result);
        return JSON.toJsonTree(body).getAsJsonObject();
    }

    static JsonObject error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message == null ? "Unknown error" : message);
        return JSON.toJsonTree(body).getAsJsonObject();
    }
}
