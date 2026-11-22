package org.mtr.testcontrol;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class TestOperations {

    private static volatile String lastSummary = "";
    private static volatile String lastInspectJson = "{}";

    private TestOperations() {
    }

    public static void setLastSummary(String summary) {
        lastSummary = summary;
    }

    public static int give(CommandSourceStack source, String id, int count) {
        return give(source, id, count, "minecraft");
    }

    public static int give(CommandSourceStack source, String id, int count, String defaultNamespace) {
        ServerPlayer player = player(source);
        Identifier location = parse(id, defaultNamespace);
        if (location == null) {
            return fail(source, "Invalid item id: " + id);
        }
        var item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return fail(source, "Unknown item: " + id);
        }
        if (player == null || !player.getInventory().add(new ItemStack(item, count))) {
            return fail(source, "No player or inventory space for " + id);
        }
        String given = location.toString();
        source.sendSuccess(() -> Component.literal("Gave " + count + " " + given + " to " + player.getGameProfile().name()), true);
        return count;
    }

    public static int giveAll(CommandSourceStack source, String namespace, int count) {
        return giveAllNamespaces(source, namespace.isEmpty() ? List.of() : List.of(namespace), count);
    }

    public static int giveAllNamespaces(CommandSourceStack source, Collection<String> namespaces, int count) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        int given = 0;
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            Identifier id = entry.getKey().identifier();
            if (!namespaces.isEmpty() && !namespaces.contains(id.getNamespace())) {
                continue;
            }
            var item = entry.getValue();
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            if (player.getInventory().add(new ItemStack(item, count))) {
                given++;
            }
        }
        int total = given;
        String label = namespaces.isEmpty() ? "all namespaces" : String.join(",", namespaces);
        source.sendSuccess(() -> Component.literal("Gave " + total + " item types from " + label), true);
        return total;
    }

    public static int listItems(CommandSourceStack source, String namespace) {
        return listItemsNamespaces(source, namespace.isEmpty() ? List.of() : List.of(namespace));
    }

    public static int listItemsNamespaces(CommandSourceStack source, Collection<String> namespaces) {
        List<String> ids = collectItemIds(namespaces);
        source.sendSuccess(() -> Component.literal("Registered items (" + ids.size() + "): " + String.join(", ", ids.subList(0, Math.min(ids.size(), 80))) + (ids.size() > 80 ? " ..." : "")), false);
        return ids.size();
    }

    public static int summonNearby(CommandSourceStack source, String id) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        Identifier location = parse(id, "neoguanniao");
        if (location == null) {
            return fail(source, "Invalid entity id: " + id);
        }
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(location).orElse(null);
        if (type == null) {
            return fail(source, "Unknown entity: " + id);
        }
        ServerLevel level = player.level();
        var entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (entity == null) {
            return fail(source, "Failed to create entity: " + location);
        }
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);
        entity.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0);
        if (!level.addFreshEntity(entity)) {
            return fail(source, "Failed to spawn entity: " + location);
        }
        source.sendSuccess(() -> Component.literal("Summoned " + location + " at " + pos.toShortString()), true);
        return 1;
    }

    public static int placeLookingAt(CommandSourceStack source, String id) {
        return placeLookingAt(source, id, "minecraft");
    }

    public static int placeLookingAt(CommandSourceStack source, String id, String defaultNamespace) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        Block block = block(id, defaultNamespace);
        if (block == Blocks.AIR) {
            return fail(source, "Unknown block: " + id);
        }
        BlockPos target = player.blockPosition().relative(player.getDirection());
        return placeAt(source, target, block.defaultBlockState(), true);
    }

    public static int placeAt(CommandSourceStack source, BlockPos target, BlockState state, boolean requireSurvival) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        ServerLevel level = player.level();
        if (requireSurvival && !state.canSurvive(level, target)) {
            return fail(source, "Block cannot survive at " + target.toShortString());
        }
        boolean changed = level.setBlock(target, state, 3);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        source.sendSuccess(() -> Component.literal((changed ? "Placed " : "Failed to place ") + blockId + " at " + target.toShortString()), true);
        return changed ? 1 : 0;
    }

    public static int placeAtCoords(CommandSourceStack source, int x, int y, int z, String id, String defaultNamespace, String propertySpec) {
        Block block = block(id, defaultNamespace);
        if (block == Blocks.AIR) {
            return fail(source, "Unknown block: " + id);
        }
        BlockState state = applyProperties(block.defaultBlockState(), propertySpec);
        return placeAt(source, new BlockPos(x, y, z), state, true);
    }

    public static int inspectLookingAt(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        return inspectAt(source, player.blockPosition().relative(player.getDirection()));
    }

    public static int inspectAt(CommandSourceStack source, BlockPos pos) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(pos);
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        BlockEntity entity = level.getBlockEntity(pos);
        String entitySummary = describeBlockEntity(level, entity);
        lastInspectJson = "{\"block\":" + quoteJson(id.toString()) + ",\"pos\":\"" + pos.toShortString() + "\",\"properties\":"
                + quoteJson(state.getValues().toString()) + ",\"blockEntity\":" + quoteJson(entitySummary) + "}";
        source.sendSuccess(() -> Component.literal("Block " + id + " at " + pos.toShortString() + ", properties=" + state.getValues() + ", blockEntity=" + entitySummary), false);
        return 1;
    }

    public static String lastInspectJson() {
        return lastInspectJson;
    }

    public static int listBlocks(CommandSourceStack source, String namespace) {
        return listBlocksNamespaces(source, namespace.isEmpty() ? List.of() : List.of(namespace));
    }

    public static int listBlocksNamespaces(CommandSourceStack source, Collection<String> namespaces) {
        List<String> ids = collectBlockIds(namespaces);
        source.sendSuccess(() -> Component.literal("Registered blocks (" + ids.size() + "): " + String.join(", ", ids.subList(0, Math.min(ids.size(), 80))) + (ids.size() > 80 ? " ..." : "")), false);
        return ids.size();
    }

    public static int runRegistrySuite(CommandSourceStack source, String namespace, int limit) {
        ServerLevel level = source.getLevel();
        BlockPos origin = level.getLevelData().getRespawnData().pos().above();
        int checked = 0;
        int passed = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            Identifier id = entry.getKey().identifier();
            if (!namespace.isEmpty() && !namespace.equals(id.getNamespace())) {
                continue;
            }
            if (checked >= limit) {
                break;
            }
            checked++;
            Block block = entry.getValue();
            if (block == Blocks.AIR || block.asItem() == net.minecraft.world.item.Items.AIR) {
                skipped++;
                continue;
            }
            BlockPos position = origin.offset((passed + skipped) % 16, 0, (passed + skipped) / 16);
            if (!runPlacementCheck(level, position, block, block.defaultBlockState(), failures, id)) {
                if (level.getBlockState(position).isAir() && level.getBlockState(position.below()).isAir()) {
                    skipped++;
                }
            } else {
                passed++;
            }
        }
        String summary = "Suite " + (namespace.isEmpty() ? "all namespaces" : namespace) + ": checked=" + checked + ", passed=" + passed + ", skipped=" + skipped + ", failed=" + failures.size() + (failures.isEmpty() ? "" : ", first failures=" + String.join(", ", failures.subList(0, Math.min(10, failures.size()))));
        source.sendSuccess(() -> Component.literal(summary), true);
        MtrTestControl.LOGGER.info(summary);
        lastSummary = summary;
        return failures.isEmpty() ? 1 : 0;
    }

    public static int runAeronauticsFullSuite(CommandSourceStack source, int limitPerNamespace) {
        SuiteResult result = new SuiteResult("aero-full", SuiteResult.AERO_NAMESPACES);
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        result.blocksListed = collectBlockIds(SuiteResult.AERO_NAMESPACES).size();
        result.itemsListed = collectItemIds(SuiteResult.AERO_NAMESPACES).size();
        int given = giveAllNamespaces(source, SuiteResult.AERO_NAMESPACES, 1);
        result.itemsGiven = given;
        result.itemsGiveFailed = Math.max(0, result.itemsListed - given);
        for (String namespace : SuiteResult.AERO_NAMESPACES) {
            runEnhancedRegistrySuite(source, namespace, limitPerNamespace, player, result);
        }
        source.sendSuccess(() -> Component.literal(result.summaryLine()), true);
        MtrTestControl.LOGGER.info(result.summaryLine());
        SuiteResult.publish(result);
        return result.commandResult();
    }

    public static int runEnhancedRegistrySuite(CommandSourceStack source, String namespace, int limit) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        SuiteResult result = new SuiteResult("enhanced", List.of(namespace));
        result.blocksListed = collectBlockIds(List.of(namespace)).size();
        result.itemsListed = collectItemIds(List.of(namespace)).size();
        result.itemsGiven = giveAllNamespaces(source, List.of(namespace), 1);
        result.itemsGiveFailed = Math.max(0, result.itemsListed - result.itemsGiven);
        runEnhancedRegistrySuite(source, namespace, limit, player, result);
        source.sendSuccess(() -> Component.literal(result.summaryLine()), true);
        MtrTestControl.LOGGER.info(result.summaryLine());
        SuiteResult.publish(result);
        return result.commandResult();
    }

    private static void runEnhancedRegistrySuite(CommandSourceStack source, String namespace, int limit, ServerPlayer player, SuiteResult result) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition().above(2).relative(player.getDirection(), 3);
        int index = 0;
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            Identifier id = entry.getKey().identifier();
            if (!namespace.equals(id.getNamespace())) {
                continue;
            }
            if (result.checked >= limit) {
                break;
            }
            result.checked++;
            Block block = entry.getValue();
            if (block == Blocks.AIR || block.asItem() == net.minecraft.world.item.Items.AIR) {
                result.skipped++;
                continue;
            }
            BlockPos position = origin.offset(index % 16, 0, index / 16);
            index++;
            BlockPos support = position.below();
            if (position.getY() >= level.getMaxY() - 1) {
                result.skipped++;
                continue;
            }
            clearVolume(level, position.offset(-1, -1, -1), position.offset(1, 2, 1));
            BlockState state = block.defaultBlockState();
            try {
                level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
                if (!state.canSurvive(level, position)) {
                    result.skipped++;
                    continue;
                }
                result.placed++;
                boolean placed = level.setBlock(position, state, 3);
                boolean correct = placed && level.getBlockState(position).getBlock() == block;
                if (correct) {
                    result.passed++;
                    inspectBlockEntity(level, position, id, result);
                } else {
                    result.failed++;
                    result.failures.add(id + " (placement mismatch)");
                }
            } catch (RuntimeException exception) {
                result.failed++;
                result.failures.add(id + " (" + exception.getClass().getSimpleName() + ")");
            } finally {
                clearVolume(level, position.offset(-1, -1, -1), position.offset(1, 2, 1));
            }
        }
    }

    private static void inspectBlockEntity(ServerLevel level, BlockPos position, Identifier id, SuiteResult result) {
        BlockEntity entity = level.getBlockEntity(position);
        if (entity == null) {
            return;
        }
        result.blockEntitiesInspected++;
        try {
            CompoundTag tag = entity.saveWithoutMetadata(level.registryAccess());
            if (tag == null) {
                result.blockEntityFailures++;
                result.failures.add(id + " (block entity save null)");
            }
        } catch (RuntimeException exception) {
            result.blockEntityFailures++;
            result.failures.add(id + " (block entity " + exception.getClass().getSimpleName() + ")");
        }
    }

    public static int runVariantSuite(CommandSourceStack source, String namespace, int limit) {
        ServerLevel level = source.getLevel();
        BlockPos origin = new BlockPos(128, Math.max(level.getMinY() + 2, 120), 128);
        int checked = 0;
        int tested = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            Identifier id = entry.getKey().identifier();
            if (!namespace.isEmpty() && !namespace.equals(id.getNamespace())) {
                continue;
            }
            for (BlockState state : entry.getValue().getStateDefinition().getPossibleStates()) {
                if (checked++ >= limit) {
                    break;
                }
                if (entry.getValue() == Blocks.AIR || entry.getValue().asItem() == net.minecraft.world.item.Items.AIR) {
                    skipped++;
                    continue;
                }
                BlockPos position = origin.offset(tested % 32, 0, tested / 32);
                BlockPos support = position.below();
                try {
                    level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
                    if (!state.canSurvive(level, position)) {
                        skipped++;
                        level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
                        continue;
                    }
                    tested++;
                    boolean placed = level.setBlock(position, state, 3);
                    BlockState actual = level.getBlockState(position);
                    if (!placed || actual.getBlock() != entry.getValue()) {
                        failures.add(id + " " + state.getValues() + " (placement mismatch)");
                    }
                } catch (Throwable throwable) {
                    failures.add(id + " " + state.getValues() + " (" + throwable.getClass().getSimpleName() + ")");
                } finally {
                    level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            if (checked >= limit) {
                break;
            }
        }
        String summary = "Variant suite " + (namespace.isEmpty() ? "all namespaces" : namespace) + ": states=" + checked + ", tested=" + tested + ", skipped=" + skipped + ", failed=" + failures.size() + (failures.isEmpty() ? "" : ", first failures=" + String.join(", ", failures.subList(0, Math.min(10, failures.size()))));
        source.sendSuccess(() -> Component.literal(summary), true);
        MtrTestControl.LOGGER.info(summary);
        lastSummary = summary;
        return failures.isEmpty() ? 1 : 0;
    }

    public static int runRedstoneSuite(CommandSourceStack source, String namespace, int limit) {
        ServerLevel level = source.getLevel();
        BlockPos origin = new BlockPos(128, Math.max(level.getMinY() + 2, 120), 128);
        int checked = 0;
        int tested = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            Identifier id = entry.getKey().identifier();
            if (!namespace.isEmpty() && !namespace.equals(id.getNamespace())) {
                continue;
            }
            Block block = entry.getValue();
            BlockState state = block.defaultBlockState();
            if (!state.isSignalSource()) {
                continue;
            }
            if (checked++ >= limit) {
                break;
            }
            BlockPos position = origin.offset(tested % 32, 0, tested / 32);
            BlockPos support = position.below();
            try {
                level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
                if (!state.canSurvive(level, position)) {
                    skipped++;
                    continue;
                }
                tested++;
                level.setBlock(position, state, 3);
                for (var direction : Direction.values()) {
                    int signal = level.getBlockState(position).getSignal(level, position, direction);
                    if (signal < 0 || signal > 15) {
                        failures.add(id + " " + direction + " signal=" + signal);
                    }
                }
                Property<?> property = block.getStateDefinition().getProperty("powered");
                if (property instanceof IntegerProperty integerProperty) {
                    int maximum = integerProperty.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                    BlockState poweredState = state.setValue(integerProperty, maximum);
                    level.setBlock(position, poweredState, 3);
                    level.scheduleTick(position, block, 10);
                    int poweredSignal = level.getBlockState(position).getSignal(level, position, Direction.UP);
                    if (poweredSignal < 0 || poweredSignal > 15) {
                        failures.add(id + " powered signal=" + poweredSignal);
                    }
                }
            } catch (Throwable throwable) {
                failures.add(id + " (" + throwable.getClass().getSimpleName() + ")");
            } finally {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        String summary = "Redstone suite " + (namespace.isEmpty() ? "all namespaces" : namespace) + ": sources=" + checked + ", tested=" + tested + ", skipped=" + skipped + ", failed=" + failures.size() + (failures.isEmpty() ? "" : ", first failures=" + String.join(", ", failures.subList(0, Math.min(10, failures.size()))));
        source.sendSuccess(() -> Component.literal(summary), true);
        MtrTestControl.LOGGER.info(summary);
        lastSummary = summary;
        return failures.isEmpty() ? 1 : 0;
    }

    public static int clearAroundPlayer(CommandSourceStack source, int radius) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        BlockPos center = player.blockPosition();
        BlockPos from = center.offset(-radius, -radius, -radius);
        BlockPos to = center.offset(radius, radius, radius);
        int cleared = clearVolume(player.level(), from, to);
        source.sendSuccess(() -> Component.literal("Cleared " + cleared + " blocks around " + center.toShortString()), true);
        return cleared;
    }

    public static int teleportPlayer(CommandSourceStack source, double x, double y, double z) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        player.teleportTo(x, y, z);
        source.sendSuccess(() -> Component.literal("Teleported to " + x + " " + y + " " + z), true);
        return 1;
    }

    public static int setGameMode(CommandSourceStack source, GameType gameType) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        player.setGameMode(gameType);
        source.sendSuccess(() -> Component.literal("Gamemode set to " + gameType.getName()), true);
        return 1;
    }

    public static int setStateLooking(CommandSourceStack source, String propertySpec) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        BlockPos pos = player.blockPosition().relative(player.getDirection());
        ServerLevel level = player.level();
        BlockState state = applyProperties(level.getBlockState(pos), propertySpec);
        level.setBlock(pos, state, 3);
        source.sendSuccess(() -> Component.literal("Set state at " + pos.toShortString() + " to " + state.getValues()), true);
        return 1;
    }

    public static int placeFootprint(CommandSourceStack source, int width, int depth, int height) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        ServerLevel level = player.level();
        BlockPos corner = player.blockPosition().relative(player.getDirection(), 2);
        int halfW = width / 2;
        int halfD = depth / 2;
        BlockPos from = corner.offset(-halfW, 0, -halfD);
        BlockPos to = corner.offset(halfW, height, halfD);
        clearVolume(level, from, to);
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int z = from.getZ(); z <= to.getZ(); z++) {
                level.setBlock(new BlockPos(x, from.getY(), z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        source.sendSuccess(() -> Component.literal("Footprint " + width + "x" + depth + "x" + height + " at " + corner.toShortString()), true);
        return 1;
    }

    public static int scheduleWait(CommandSourceStack source, int ticks) {
        MinecraftServer server = source.getServer();
        long target = server.getTickCount() + ticks;
        source.sendSuccess(() -> Component.literal("Waiting " + ticks + " ticks (complete at tick " + target + ")"), false);
        Runnable waiter = new Runnable() {
            @Override
            public void run() {
                if (server.getTickCount() >= target) {
                    source.sendSuccess(() -> Component.literal("Wait complete after " + ticks + " ticks"), false);
                } else {
                    server.execute(this);
                }
            }
        };
        server.execute(waiter);
        return 1;
    }

    public static int useOnLooking(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) {
            return fail(source, "No online player is available");
        }
        BlockPos pos = player.blockPosition().relative(player.getDirection());
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = Direction.UP;
        BlockHitResult hit = new BlockHitResult(center, face, pos, false);
        ServerLevel level = player.level();
        var interaction = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        source.sendSuccess(() -> Component.literal("useItemOn -> " + interaction), true);
        return 1;
    }

    public static String lastSummary() {
        return lastSummary;
    }

    public static long currentTick(MinecraftServer server) {
        return server.getTickCount();
    }

    private static boolean runPlacementCheck(ServerLevel level, BlockPos position, Block block, BlockState state, List<String> failures, Identifier id) {
        BlockPos support = position.below();
        if (position.getY() >= level.getMaxY() || !level.getBlockState(position).isAir() || !level.getBlockState(support).isAir()) {
            return false;
        }
        try {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            if (!state.canSurvive(level, position)) {
                level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
                return false;
            }
            boolean placed = level.setBlock(position, state, 3);
            boolean correct = placed && level.getBlockState(position).getBlock() == block;
            if (!correct) {
                failures.add(id + " (placement mismatch)");
            }
            level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
            return correct;
        } catch (RuntimeException exception) {
            failures.add(id + " (" + exception.getClass().getSimpleName() + ")");
            level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
            return false;
        }
    }

    private static int clearVolume(ServerLevel level, BlockPos from, BlockPos to) {
        int cleared = 0;
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                cleared++;
            }
        }
        return cleared;
    }

    private static List<String> collectBlockIds(Collection<String> namespaces) {
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.BLOCK.entrySet().forEach(entry -> {
            Identifier id = entry.getKey().identifier();
            if (namespaces.isEmpty() || namespaces.contains(id.getNamespace())) {
                ids.add(id.toString());
            }
        });
        return ids;
    }

    private static List<String> collectItemIds(Collection<String> namespaces) {
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.ITEM.entrySet().forEach(entry -> {
            Identifier id = entry.getKey().identifier();
            if (namespaces.isEmpty() || namespaces.contains(id.getNamespace())) {
                ids.add(id.toString());
            }
        });
        return ids;
    }

    private static BlockState applyProperties(BlockState state, String propertySpec) {
        if (propertySpec == null || propertySpec.isBlank()) {
            return state;
        }
        for (String token : propertySpec.split(",")) {
            String[] pair = token.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            Property<?> property = state.getBlock().getStateDefinition().getProperty(pair[0].trim());
            if (property == null) {
                continue;
            }
            state = setPropertyValue(state, property, pair[1].trim());
        }
        return state;
    }

    private static BlockState setPropertyValue(BlockState state, Property<?> property, String value) {
        for (Comparable<?> candidate : property.getPossibleValues()) {
            if (candidate.toString().equalsIgnoreCase(value)) {
                return withProperty(state, property, candidate);
            }
        }
        return property.getValue(value).map(parsed -> withProperty(state, property, parsed)).orElse(state);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<?> property, Comparable<?> value) {
        return state.setValue((Property<T>) property, (T) value);
    }

    private static String describeBlockEntity(ServerLevel level, BlockEntity entity) {
        if (entity == null) {
            return "none";
        }
        try {
            CompoundTag tag = entity.saveWithoutMetadata(level.registryAccess());
            String snippet = tag == null ? "{}" : tag.toString();
            if (snippet.length() > 400) {
                snippet = snippet.substring(0, 400) + "...";
            }
            return entity.getClass().getSimpleName() + " " + snippet;
        } catch (RuntimeException exception) {
            return entity.getClass().getSimpleName() + " (save failed: " + exception.getClass().getSimpleName() + ")";
        }
    }

    private static ServerPlayer player(CommandSourceStack source) {
        return source.getPlayer();
    }

    private static Identifier parse(String id, String defaultNamespace) {
        try {
            return Identifier.parse(id.contains(":") ? id : defaultNamespace + ":" + id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Block block(String id, String defaultNamespace) {
        Identifier location = parse(id, defaultNamespace);
        return location == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getOptional(location).orElse(Blocks.AIR);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private static String quoteJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
