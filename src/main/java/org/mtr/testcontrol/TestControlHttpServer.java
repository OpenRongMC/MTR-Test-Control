package org.mtr.testcontrol;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class TestControlHttpServer {

    private final MinecraftServer server;
    private final String token;
    private HttpServer httpServer;

    public TestControlHttpServer(MinecraftServer server) {
        this.server = server;
        this.token = createToken();
    }

    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/health", this::health);
            httpServer.createContext("/state", this::state);
            httpServer.createContext("/blocks", this::blocks);
            httpServer.createContext("/items", this::items);
            httpServer.createContext("/command", this::command);
            httpServer.createContext("/suite", this::suite);
            httpServer.createContext("/summary", this::summary);
            httpServer.createContext("/inspect", this::inspect);
            httpServer.createContext("/wait", this::waitTicks);
            httpServer.createContext("/giveall", this::giveAll);
            httpServer.setExecutor(null);
            httpServer.start();
            MtrTestControl.LOGGER.info("Local test control listening at {} with token {}", endpoint(), token);
        } catch (IOException exception) {
            MtrTestControl.LOGGER.error("Unable to start local test control", exception);
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    public String endpoint() {
        return httpServer == null ? "stopped" : "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        respond(exchange, 200, "{\"ok\":true,\"mod\":\"mtr_test_control\",\"endpoint\":" + quote(endpoint()) + "}");
    }

    private void state(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        ServerPlayer player = MtrTestControl.player(server);
        if (player == null) {
            respond(exchange, 200, "{\"ok\":true,\"player\":null}");
            return;
        }
        BlockPos pos = player.blockPosition();
        String dimension = player.level().dimension().identifier().toString();
        respond(exchange, 200, "{\"ok\":true,\"player\":" + quote(player.getGameProfile().name()) + ",\"dimension\":" + quote(dimension) + ",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + ",\"tick\":" + server.getTickCount() + "}");
    }

    private void blocks(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        respond(exchange, 200, listRegistry("blocks", query));
    }

    private void items(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        respond(exchange, 200, listRegistry("items", query));
    }

    private String listRegistry(String kind, Map<String, String> query) {
        String namespace = query.getOrDefault("namespace", "");
        int limit = integer(query.get("limit"), 1000, 1, 5000);
        StringBuilder result = new StringBuilder("{\"ok\":true,\"" + kind + "\":[");
        int count = 0;
        var registry = kind.equals("items") ? BuiltInRegistries.ITEM : BuiltInRegistries.BLOCK;
        for (var entry : registry.entrySet()) {
            String id = entry.getKey().identifier().toString();
            if (!namespace.isEmpty() && !id.startsWith(namespace + ":")) {
                continue;
            }
            if (count++ >= limit) {
                break;
            }
            if (count > 1) {
                result.append(',');
            }
            result.append(quote(id));
        }
        result.append("],\"count\":").append(count).append('}');
        return result.toString();
    }

    private void command(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"POST required\"}");
            return;
        }
        Map<String, String> query = query(exchange);
        String command = query.get("command");
        if (command == null || command.isBlank()) {
            command = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        final String requestedCommand = command;
        runOnServer(() -> {
            server.getCommands().performPrefixedCommand(commandSource(), requestedCommand);
            return 1;
        });
        respond(exchange, 200, "{\"ok\":true,\"accepted\":true}");
    }

    private void suite(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"POST required\"}");
            return;
        }
        Map<String, String> query = query(exchange);
        String namespace = query.getOrDefault("namespace", "");
        int limit = integer(query.get("limit"), 4096, 1, 4096);
        String kind = query.getOrDefault("kind", "registry");
        int result = runOnServer(() -> switch (kind) {
            case "aero", "aero-full" -> TestOperations.runAeronauticsFullSuite(commandSource(), limit);
            case "enhanced" -> namespace.isEmpty()
                    ? TestOperations.runAeronauticsFullSuite(commandSource(), limit)
                    : TestOperations.runEnhancedRegistrySuite(commandSource(), namespace, limit);
            case "variants" -> TestOperations.runVariantSuite(commandSource(), namespace, limit);
            case "redstone" -> TestOperations.runRedstoneSuite(commandSource(), namespace, limit);
            default -> namespace.isEmpty()
                    ? TestOperations.runRegistrySuite(commandSource(), "", limit)
                    : (namespace.equals("aeronautics") || namespace.equals("simulated")
                    ? TestOperations.runEnhancedRegistrySuite(commandSource(), namespace, limit)
                    : TestOperations.runRegistrySuite(commandSource(), namespace, limit));
        });
        SuiteResult suite = SuiteResult.last();
        JsonObject body = suite.toJson();
        body.addProperty("httpOk", true);
        body.addProperty("commandResult", result);
        body.addProperty("kind", kind);
        body.addProperty("limit", limit);
        respond(exchange, 200, body.toString());
    }

    private void summary(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        respond(exchange, 200, SuiteResult.last().toJson().toString());
    }

    private void inspect(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange);
        int result = runOnServer(() -> {
            ServerPlayer player = MtrTestControl.player(server);
            if (player == null) {
                return 0;
            }
            if (query.containsKey("x") && query.containsKey("y") && query.containsKey("z")) {
                return TestOperations.inspectAt(commandSource(), new BlockPos(
                        integer(query.get("x"), 0, -30000000, 30000000),
                        integer(query.get("y"), 0, -512, 512),
                        integer(query.get("z"), 0, -30000000, 30000000)));
            }
            return TestOperations.inspectLookingAt(commandSource());
        });
        respond(exchange, 200, "{\"ok\":" + (result > 0) + ",\"inspect\":" + TestOperations.lastInspectJson() + "}");
    }

    private void waitTicks(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"POST required\"}");
            return;
        }
        Map<String, String> query = query(exchange);
        int ticks = integer(query.get("ticks"), 20, 1, 20 * 60 * 5);
        long start = server.getTickCount();
        long target = start + ticks;
        while (server.getTickCount() < target) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for ticks", exception);
            }
        }
        respond(exchange, 200, "{\"ok\":true,\"ticks\":" + ticks + ",\"startTick\":" + start + ",\"endTick\":" + server.getTickCount() + "}");
    }

    private void giveAll(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"POST required\"}");
            return;
        }
        Map<String, String> query = query(exchange);
        String namespace = query.getOrDefault("namespace", "");
        int count = integer(query.get("count"), 1, 1, 64);
        int given = runOnServer(() -> {
            if (namespace.equals("aero") || namespace.equals("aero-full")) {
                return TestOperations.giveAllNamespaces(commandSource(), SuiteResult.AERO_NAMESPACES, count);
            }
            if (namespace.contains(",")) {
                return TestOperations.giveAllNamespaces(commandSource(), List.of(namespace.split(",")), count);
            }
            return TestOperations.giveAll(commandSource(), namespace, count);
        });
        respond(exchange, 200, "{\"ok\":true,\"given\":" + given + ",\"namespace\":" + quote(namespace) + "}");
    }

    private CommandSourceStack commandSource() {
        ServerPlayer player = MtrTestControl.player(server);
        return (player == null ? server.createCommandSourceStack() : player.createCommandSourceStack()).withPermission(LevelBasedPermissionSet.OWNER);
    }

    private int runOnServer(java.util.function.IntSupplier operation) throws IOException {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(operation.getAsInt());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        try {
            return result.get(120, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IOException("Timed out waiting for the Minecraft server thread", exception);
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String provided = exchange.getRequestHeaders().getFirst("X-MTR-Test-Token");
        if (!token.equals(provided)) {
            respond(exchange, 401, "{\"ok\":false,\"error\":\"invalid token\"}");
            return false;
        }
        return true;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String item : raw.split("&")) {
            String[] pair = item.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static int integer(String value, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value)));
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static String createToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte value : bytes) {
            token.append(String.format("%02x", value));
        }
        return token.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
