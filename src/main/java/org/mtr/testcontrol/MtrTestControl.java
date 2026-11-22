package org.mtr.testcontrol;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MtrTestControl implements ModInitializer {

    public static final String MOD_ID = "mtr_test_control";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static TestControlHttpServer httpServer;

    private enum RootMode {
        STANDARD,
        AERO_DUAL
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            httpServer = new TestControlHttpServer(server);
            httpServer.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (httpServer != null) {
                httpServer.stop();
                httpServer = null;
            }
        });
        LOGGER.info("MTR Test Control loaded. Use /mtrtest help for controls.");
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerRoot(dispatcher, "mtrtest", "minecraft", RootMode.STANDARD);
        registerRoot(dispatcher, "ngntest", "neoguanniao", RootMode.STANDARD);
        registerRoot(dispatcher, "aerotest", "aeronautics", RootMode.AERO_DUAL);
    }

    private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher, String root, String defaultNamespace, RootMode mode) {
        var builder = Commands.literal(root)
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("help").executes(context -> help(context.getSource(), root, defaultNamespace, mode)))
                .then(Commands.literal("give")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(context -> TestOperations.give(context.getSource(), StringArgumentType.getString(context, "item"), 1, defaultNamespace))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> TestOperations.give(context.getSource(), StringArgumentType.getString(context, "item"), IntegerArgumentType.getInteger(context, "count"), defaultNamespace)))))
                .then(Commands.literal("giveall")
                        .executes(context -> giveAllForMode(context.getSource(), mode, defaultNamespace, 1))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(context -> TestOperations.giveAll(context.getSource(), StringArgumentType.getString(context, "namespace"), 1))))
                .then(Commands.literal("summon")
                        .then(Commands.argument("entity", StringArgumentType.string())
                                .executes(context -> TestOperations.summonNearby(context.getSource(), StringArgumentType.getString(context, "entity")))))
                .then(Commands.literal("place")
                        .then(Commands.argument("block", StringArgumentType.string())
                                .executes(context -> TestOperations.placeLookingAt(context.getSource(), StringArgumentType.getString(context, "block"), defaultNamespace))))
                .then(Commands.literal("placeat")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("block", StringArgumentType.string())
                                                        .executes(context -> TestOperations.placeAtCoords(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                StringArgumentType.getString(context, "block"),
                                                                defaultNamespace,
                                                                ""))
                                                        .then(Commands.argument("properties", StringArgumentType.greedyString())
                                                                .executes(context -> TestOperations.placeAtCoords(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "x"),
                                                                        IntegerArgumentType.getInteger(context, "y"),
                                                                        IntegerArgumentType.getInteger(context, "z"),
                                                                        StringArgumentType.getString(context, "block"),
                                                                        defaultNamespace,
                                                                        StringArgumentType.getString(context, "properties")))))))))
                .then(Commands.literal("inspect").executes(context -> TestOperations.inspectLookingAt(context.getSource())))
                .then(Commands.literal("list")
                        .executes(context -> listBlocksForMode(context.getSource(), mode, defaultNamespace))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(context -> TestOperations.listBlocks(context.getSource(), StringArgumentType.getString(context, "namespace")))))
                .then(Commands.literal("listitems")
                        .executes(context -> listItemsForMode(context.getSource(), mode, defaultNamespace))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(context -> TestOperations.listItems(context.getSource(), StringArgumentType.getString(context, "namespace")))))
                .then(Commands.literal("suite")
                        .executes(context -> suiteForMode(context.getSource(), mode, defaultNamespace, 4096))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(context -> suiteForNamespace(context.getSource(), mode, StringArgumentType.getString(context, "namespace"), 4096))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 4096))
                                        .executes(context -> suiteForNamespace(context.getSource(), mode, StringArgumentType.getString(context, "namespace"), IntegerArgumentType.getInteger(context, "limit"))))))
                .then(Commands.literal("variants")
                        .executes(context -> TestOperations.runVariantSuite(context.getSource(), "", 100000))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(context -> TestOperations.runVariantSuite(context.getSource(), StringArgumentType.getString(context, "namespace"), 100000))))
                .then(Commands.literal("redstone")
                        .executes(context -> TestOperations.runRedstoneSuite(context.getSource(), "", 100000))
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(context -> TestOperations.runRedstoneSuite(context.getSource(), StringArgumentType.getString(context, "namespace"), 100000))))
                .then(Commands.literal("clear")
                        .executes(context -> TestOperations.clearAroundPlayer(context.getSource(), 4))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(context -> TestOperations.clearAroundPlayer(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("tp")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> TestOperations.teleportPlayer(
                                                        context.getSource(),
                                                        DoubleArgumentType.getDouble(context, "x"),
                                                        DoubleArgumentType.getDouble(context, "y"),
                                                        DoubleArgumentType.getDouble(context, "z")))))))
                .then(Commands.literal("gamemode")
                        .then(Commands.literal("creative").executes(context -> TestOperations.setGameMode(context.getSource(), GameType.CREATIVE)))
                        .then(Commands.literal("survival").executes(context -> TestOperations.setGameMode(context.getSource(), GameType.SURVIVAL)))
                        .then(Commands.literal("spectator").executes(context -> TestOperations.setGameMode(context.getSource(), GameType.SPECTATOR)))
                        .then(Commands.literal("adventure").executes(context -> TestOperations.setGameMode(context.getSource(), GameType.ADVENTURE))))
                .then(Commands.literal("setstate")
                        .then(Commands.argument("properties", StringArgumentType.greedyString())
                                .executes(context -> TestOperations.setStateLooking(context.getSource(), StringArgumentType.getString(context, "properties")))))
                .then(Commands.literal("footprint")
                        .executes(context -> TestOperations.placeFootprint(context.getSource(), 5, 5, 4))
                        .then(Commands.argument("width", IntegerArgumentType.integer(1, 32))
                                .then(Commands.argument("depth", IntegerArgumentType.integer(1, 32))
                                        .executes(context -> TestOperations.placeFootprint(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "width"),
                                                IntegerArgumentType.getInteger(context, "depth"),
                                                4))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 16))
                                                .executes(context -> TestOperations.placeFootprint(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "width"),
                                                        IntegerArgumentType.getInteger(context, "depth"),
                                                        IntegerArgumentType.getInteger(context, "height")))))))
                .then(Commands.literal("wait")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 20 * 60 * 5))
                                .executes(context -> TestOperations.scheduleWait(context.getSource(), IntegerArgumentType.getInteger(context, "ticks")))))
                .then(Commands.literal("use").executes(context -> TestOperations.useOnLooking(context.getSource())))
                .then(Commands.literal("http").executes(context -> httpStatus(context.getSource())));

        dispatcher.register(builder);
    }

    private static int giveAllForMode(CommandSourceStack source, RootMode mode, String defaultNamespace, int count) {
        if (mode == RootMode.AERO_DUAL) {
            return TestOperations.giveAllNamespaces(source, SuiteResult.AERO_NAMESPACES, count);
        }
        return TestOperations.giveAll(source, defaultNamespace, count);
    }

    private static int listBlocksForMode(CommandSourceStack source, RootMode mode, String defaultNamespace) {
        if (mode == RootMode.AERO_DUAL) {
            return TestOperations.listBlocksNamespaces(source, SuiteResult.AERO_NAMESPACES);
        }
        return TestOperations.listBlocks(source, defaultNamespace.equals("minecraft") ? "" : defaultNamespace);
    }

    private static int listItemsForMode(CommandSourceStack source, RootMode mode, String defaultNamespace) {
        if (mode == RootMode.AERO_DUAL) {
            return TestOperations.listItemsNamespaces(source, SuiteResult.AERO_NAMESPACES);
        }
        return TestOperations.listItems(source, defaultNamespace.equals("minecraft") ? "" : defaultNamespace);
    }

    private static int suiteForMode(CommandSourceStack source, RootMode mode, String defaultNamespace, int limit) {
        if (mode == RootMode.AERO_DUAL) {
            return TestOperations.runAeronauticsFullSuite(source, limit);
        }
        return TestOperations.runRegistrySuite(source, defaultNamespace.equals("minecraft") ? "" : defaultNamespace, limit);
    }

    private static int suiteForNamespace(CommandSourceStack source, RootMode mode, String namespace, int limit) {
        if (mode == RootMode.AERO_DUAL || namespace.equals("aeronautics") || namespace.equals("simulated")) {
            return TestOperations.runEnhancedRegistrySuite(source, namespace, limit);
        }
        return TestOperations.runRegistrySuite(source, namespace, limit);
    }

    private static int help(CommandSourceStack source, String root, String defaultNamespace, RootMode mode) {
        String extra = mode == RootMode.AERO_DUAL ? " (aeronautics+simulated dual namespace)" : "";
        source.sendSuccess(() -> Component.literal("/" + root + " give|giveall|summon|place|placeat|inspect|list|listitems|suite|variants|redstone|clear|tp|gamemode|setstate|footprint|wait|use|http (default ns=" + defaultNamespace + ")" + extra), false);
        return 1;
    }

    private static int httpStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(httpServer == null ? "HTTP control is stopped" : "HTTP control: " + httpServer.endpoint()), false);
        return 1;
    }

    public static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
    }
}
