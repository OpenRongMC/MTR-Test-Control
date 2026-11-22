package org.mtr.testcontrol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class ClientTestControl implements ClientModInitializer {

    private final String token = createToken();
    private HttpServer httpServer;
    private long ticks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ticks++);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (httpServer != null) {
                httpServer.stop(0);
            }
        });
    }

    private void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/state", exchange -> handle(exchange, "GET", request -> state()));
            httpServer.createContext("/models", exchange -> handle(exchange, "GET", request -> models()));
            httpServer.createContext("/held_item", exchange -> handle(exchange, "GET", request -> heldItem()));
            httpServer.createContext("/runtime", exchange -> handle(exchange, "GET", request -> runtime()));
            httpServer.createContext("/action", exchange -> handle(exchange, "POST", this::action));
            httpServer.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mtr-client-test-http");
                thread.setDaemon(true);
                return thread;
            }));
            httpServer.start();
            MtrTestControl.LOGGER.info("Client test control listening at http://127.0.0.1:{} with token {}", httpServer.getAddress().getPort(), token);
        } catch (IOException exception) {
            MtrTestControl.LOGGER.error("Unable to start client test control", exception);
        }
    }

    private void handle(HttpExchange exchange, String method, Function<JsonObject, JsonObject> operation) throws IOException {
        if (!token.equals(exchange.getRequestHeaders().getFirst("X-MTR-Test-Token"))) {
            respond(exchange, 401, error("Invalid token"));
            return;
        }
        if (!method.equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Expected " + method));
            return;
        }
        final JsonObject request;
        try {
            byte[] body = exchange.getRequestBody().readNBytes(16385);
            if (body.length > 16384) {
                respond(exchange, 413, error("Request is too large"));
                return;
            }
            request = method.equals("POST") ? JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        } catch (RuntimeException exception) {
            respond(exchange, 400, error("Invalid JSON request"));
            return;
        }
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            if (!result.isCancelled()) {
                try {
                    result.complete(operation.apply(request));
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            }
        });
        try {
            respond(exchange, 200, result.get(30, TimeUnit.SECONDS));
        } catch (Exception exception) {
            result.cancel(false);
            respond(exchange, 500, error(exception.toString()));
        }
    }

    private JsonObject state() {
        Minecraft client = Minecraft.getInstance();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("ticks", ticks);
        result.addProperty("fps", client.getFps());
        result.addProperty("windowActive", client.isWindowActive());
        result.addProperty("windowIconified", (org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(client.getWindow().handle()) & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED) != 0);
        result.addProperty("world", client.level == null ? null : client.level.dimension().identifier().toString());
        result.addProperty("screen", client.gui.screen() == null ? null : client.gui.screen().getClass().getName());
        result.addProperty("overlay", client.gui.overlay() == null ? null : client.gui.overlay().getClass().getName());
        if (client.player != null) {
            result.addProperty("x", client.player.getX());
            result.addProperty("y", client.player.getY());
            result.addProperty("z", client.player.getZ());
            result.addProperty("yaw", client.player.getYRot());
            result.addProperty("pitch", client.player.getXRot());
            result.addProperty("hand", BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()).toString());
        }
        JsonArray widgets = new JsonArray();
        if (client.gui.screen() != null) {
            result.addProperty("title", client.gui.screen().getTitle().getString());
            result.addProperty("width", client.gui.screen().width);
            result.addProperty("height", client.gui.screen().height);
            for (var child : client.gui.screen().children()) {
                if (child instanceof AbstractWidget widget) {
                    JsonObject value = new JsonObject();
                    value.addProperty("type", widget.getClass().getName());
                    value.addProperty("text", widget.getMessage().getString());
                    value.addProperty("x", widget.getX());
                    value.addProperty("y", widget.getY());
                    value.addProperty("width", widget.getWidth());
                    value.addProperty("height", widget.getHeight());
                    value.addProperty("visible", widget.visible);
                    value.addProperty("active", widget.active);
                    if (widget instanceof EditBox field) {
                        value.addProperty("value", field.getValue());
                    }
                    if (widget instanceof Checkbox checkbox) {
                        value.addProperty("checked", checkbox.selected());
                    }
                    widgets.add(value);
                }
            }
        }
        result.add("widgets", widgets);
        if (client.gui.screen() instanceof AbstractContainerScreen<?> container) {
            JsonArray slots = new JsonArray();
            for (var slot : container.getMenu().slots) {
                slots.add(BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
            }
            result.add("slots", slots);
        }
        JsonArray localEntities = new JsonArray();
        if (client.level != null) {
            for (var entity : client.level.entitiesForRendering()) {
                if (entity.getId() < 0) {
                    JsonObject value = new JsonObject();
                    value.addProperty("id", entity.getId());
                    value.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    localEntities.add(value);
                }
            }
        }
        result.add("localEntities", localEntities);
        return result;
    }

    private JsonObject heldItem() {
        Minecraft client = Minecraft.getInstance();
        var stack = java.util.Objects.requireNonNull(client.player).getMainHandItem();
        var renderState = new net.minecraft.client.renderer.item.TrackingItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(renderState, stack, net.minecraft.world.item.ItemDisplayContext.GUI, client.level, client.player, 0);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.addProperty("empty", renderState.isEmpty());
        result.addProperty("modelIdentity", renderState.getModelIdentity().toString());
        result.addProperty("bounds", renderState.getModelBoundingBox().toString());
        var particle = renderState.pickParticleMaterial(net.minecraft.util.RandomSource.create(0));
        result.addProperty("particle", particle == null ? null : particle.sprite().contents().name().toString());
        var customData = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY);
        result.add("customData", net.minecraft.nbt.NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE, customData.copyTag()));
        return result;
    }

    private JsonObject runtime() {
        Minecraft client = Minecraft.getInstance();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("vehicles", mappedCollectionSize("vehicles"));
        result.addProperty("lifts", mappedCollectionSize("lifts"));
        result.addProperty("rails", mappedCollectionSize("rails"));
        int renderingEntities = 0;
        if (client.level != null) {
            for (var ignored : client.level.entitiesForRendering()) {
                renderingEntities++;
            }
        }
        result.addProperty("renderingEntities", renderingEntities);
        return result;
    }

    private static int mappedCollectionSize(String fieldName) {
        try {
            Object data = Class.forName("org.mtr.mod.client.MinecraftClientData").getMethod("getInstance").invoke(null);
            Object collection = data.getClass().getField(fieldName).get(data);
            return collection instanceof java.util.Collection<?> values ? values.size() : 0;
        } catch (ReflectiveOperationException exception) {
            return 0;
        }
    }

    private JsonObject models() {
        Minecraft client = Minecraft.getInstance();
        JsonObject result = new JsonObject();
        JsonArray missing = new JsonArray();
        int checked = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft")) {
                continue;
            }
            for (var blockState : block.getStateDefinition().getPossibleStates()) {
                if (blockState.getRenderShape() == RenderShape.MODEL) {
                    checked++;
                    if (client.getModelManager().getBlockStateModelSet().get(blockState) == client.getModelManager().getBlockStateModelSet().missingModel()) {
                        missing.add(blockState.toString());
                    }
                }
            }
        }
        result.addProperty("ok", missing.isEmpty());
        result.addProperty("checked", checked);
        result.add("missing", missing);
        return result;
    }

    private JsonObject action(JsonObject request) {
        Minecraft client = Minecraft.getInstance();
        String action = request.get("action").getAsString();
        JsonObject result = new JsonObject();
        switch (action) {
            case "focus" -> {
                org.lwjgl.sdl.SDLVideo.SDL_RestoreWindow(client.getWindow().handle());
                org.lwjgl.sdl.SDLVideo.SDL_ShowWindow(client.getWindow().handle());
                org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(client.getWindow().handle());
            }
            case "quit" -> client.stop();
            case "test_options" -> {
                client.options.pauseOnLostFocus = false;
                client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                client.options.framerateLimit().set(60);
            }
            case "screenshot" -> {
                String name = request.get("name").getAsString();
                if (!name.matches("[a-zA-Z0-9_-]{1,80}\\.png")) {
                    throw new IllegalArgumentException("Expected a simple PNG filename");
                }
                Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> MtrTestControl.LOGGER.info("{}", message.getString()));
                result.addProperty("path", client.gameDirectory.toPath().resolve("screenshots").resolve(name).toString());
            }
            case "close" -> {
                if (client.gui.screen() != null) {
                    client.gui.screen().onClose();
                }
            }
            case "command" -> {
                // 发送命令（不带斜杠）。与聊天框同路径：connection.sendCommand 会被
                // fabric-command-api-v2 的 ClientPacketListenerMixin 拦截，
                // 客户端注册的命令（如 nmvbjd 的本地命令）本地执行，其余照常发包给服务器。
                var player = java.util.Objects.requireNonNull(client.player, "No player");
                String command = request.get("command").getAsString();
                if (command.startsWith("/")) command = command.substring(1);
                player.connection.sendCommand(command);
                result.addProperty("sent", command);
            }
            case "inventory" -> client.gui.setScreen(new InventoryScreen(java.util.Objects.requireNonNull(client.player)));
            case "creative_inventory" -> {
                var player = java.util.Objects.requireNonNull(client.player);
                FeatureFlagSet flags = client.level == null ? FeatureFlagSet.of() : client.level.enabledFeatures();
                client.gui.setScreen(new CreativeModeInventoryScreen(player, flags, false));
            }
            case "select_tab" -> {
                String name = request.get("tab").getAsString().toLowerCase();
                var tab = CreativeModeTabs.tabs().stream()
                        .filter(candidate -> candidate.getDisplayName().getString().toLowerCase().contains(name))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No creative tab matching " + name));
                try {
                    var field = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                    field.setAccessible(true);
                    field.set(null, tab);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Unable to select creative tab " + name, exception);
                }
            }
            case "look" -> {
                java.util.Objects.requireNonNull(client.player).setYRot(request.get("yaw").getAsFloat());
                client.player.setXRot(request.get("pitch").getAsFloat());
            }
            case "use" -> {
                java.util.Objects.requireNonNull(client.player);
                BlockPos position = new BlockPos(request.get("x").getAsInt(), request.get("y").getAsInt(), request.get("z").getAsInt());
                Vec3 center = Vec3.atCenterOf(position);
                if (client.player.getEyePosition().distanceTo(center) > client.player.blockInteractionRange() + 1) {
                    throw new IllegalArgumentException("Block is out of reach");
                }
                Direction face = Direction.valueOf(request.has("face") ? request.get("face").getAsString().toUpperCase(java.util.Locale.ROOT) : "UP");
                BlockHitResult hit = new BlockHitResult(center.relative(face, 0.5), face, position, false);
                client.hitResult = hit;
                result.addProperty("result", java.util.Objects.requireNonNull(client.gameMode).useItemOn(client.player, InteractionHand.MAIN_HAND, hit).toString());
            }
            case "use_item" -> result.addProperty("result", java.util.Objects.requireNonNull(client.gameMode).useItem(java.util.Objects.requireNonNull(client.player), InteractionHand.MAIN_HAND).toString());
            case "click" -> {
                var screen = java.util.Objects.requireNonNull(client.gui.screen(), "No screen is open");
                MouseButtonEvent event = new MouseButtonEvent(request.get("x").getAsDouble(), request.get("y").getAsDouble(), new MouseButtonInfo(0, 0));
                result.addProperty("handled", screen.mouseClicked(event, false));
                screen.mouseReleased(event);
            }
            case "key" -> result.addProperty("handled", java.util.Objects.requireNonNull(client.gui.screen(), "No screen is open").keyPressed(new KeyEvent(request.get("key").getAsInt(), 0, request.has("modifiers") ? request.get("modifiers").getAsInt() : 0)));
            case "scroll" -> result.addProperty("handled", java.util.Objects.requireNonNull(client.gui.screen(), "No screen is open").mouseScrolled(request.get("x").getAsDouble(), request.get("y").getAsDouble(), 0, request.get("amount").getAsDouble()));
            case "text" -> request.get("text").getAsString().codePoints().forEach(codepoint -> java.util.Objects.requireNonNull(client.gui.screen(), "No screen is open").charTyped(new CharacterEvent(codepoint)));
            case "reload" -> client.reloadResourcePacks();
            case "disconnect" -> client.disconnectWithSavingScreen();
            case "connect_test_server" -> {
                if (client.level != null) {
                    throw new IllegalStateException("Disconnect before connecting to the isolated test server");
                }
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString("127.0.0.1:25565"), new ServerData("MTR isolated test", "127.0.0.1:25565", ServerData.Type.OTHER), false, null);
            }
            case "connect" -> {
                // 连接任意地址服务器（如布吉岛代理 192.168.0.38:25565）。
                // 协议版本由 ViaFabricPlus 按服务器条目的强制版本配置决定。
                if (client.level != null) {
                    throw new IllegalStateException("Disconnect before connecting");
                }
                String address = request.get("address").getAsString();
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address), new ServerData(address, address, ServerData.Type.OTHER), false, null);
                result.addProperty("address", address);
            }
            case "hotbar_slot" -> {
                // 走原版按键路径切换热栏（0-8），会正常同步给服务器
                int slot = request.get("slot").getAsInt();
                client.options.keyHotbarSlots[slot].consumeClick();
                result.addProperty("slot", slot);
            }
            case "camera" -> {
                // 切换视角：first / third_front / third_back；不带值则在 第一人称/第三人称背面 间切换
                var options = client.options;
                net.minecraft.client.CameraType target;
                if (request.has("type")) {
                    target = switch (request.get("type").getAsString()) {
                        case "first" -> net.minecraft.client.CameraType.FIRST_PERSON;
                        case "third_front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
                        default -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
                    };
                } else {
                    target = options.getCameraType().isFirstPerson()
                            ? net.minecraft.client.CameraType.THIRD_PERSON_BACK
                            : net.minecraft.client.CameraType.FIRST_PERSON;
                }
                options.setCameraType(target);
                result.addProperty("camera", target.name());
            }
            case "stop" -> client.stop();
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
        result.addProperty("ok", true);
        return result;
    }

    private static String createToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static void respond(HttpExchange exchange, int status, JsonObject result) throws IOException {
        byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
