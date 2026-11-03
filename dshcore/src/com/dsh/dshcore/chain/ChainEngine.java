package com.dsh.dshcore.chain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Clean-room chain mining engine. Triggered from ServerPlayerGameModeMixin after a real single
 * block break; the remaining chain is destroyed over subsequent server ticks (delay per block).
 * All code and class names are original (AGPL mod is not linked or copied).
 */
public final class ChainEngine {
    private static final Logger LOG = LoggerFactory.getLogger("dshcore/chain");
    private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<ServerLevel, ArrayDeque<Job>> JOBS = new IdentityHashMap<>();
    private static final Map<UUID, Integer> COOLDOWN_TICKS = new HashMap<>();
    private static volatile ChainConfig config = new ChainConfig();

    private ChainEngine() {
    }

    public static ChainConfig config() {
        return config;
    }

    public static void loadConfig() {
        config = ChainConfig.load();
        config.save();
        LOG.info("[dshcore] chain config loaded: enabled={}, pattern={}, groups={}, patterns={}",
                config.settings.enabled, config.settings.pattern, config.groups.size(), config.patterns.size());
    }

    /** Called from the mixin after a player really broke a block. */
    public static void onBlockBroken(ServerPlayer player, ServerLevel level, BlockPos origin, BlockState originState) {
        if (INTERNAL.get()) return;
        if (player == null || level == null || origin == null || originState == null) return;
        ChainSettings s = config.settings;
        if (!s.enabled || originState.isAir()) return;
        if (s.mustSneak && !player.isShiftKeyDown()) return;
        UUID id = player.getUUID();
        Integer cd = COOLDOWN_TICKS.get(id);
        if (cd != null && cd > 0) return;
        if (s.permissionRestricted && !player.hasPermissions(2)) return;
        ItemStack tool = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (s.needCorrectTool && !tool.isCorrectToolForDrops(originState)) return;
        BlockGroup group = findGroup(originState);
        if (group == null) return;
        ChainPattern pattern = selectedPattern();
        List<BlockPos> chain = collect(level, origin, originState, group, pattern, s, player);
        if (chain.isEmpty()) return;
        Job job = new Job(player, level, group, chain);
        ArrayDeque<Job> queue = JOBS.computeIfAbsent(level, k -> new ArrayDeque<>());
        queue.add(job);
        if (s.cooldown > 0) COOLDOWN_TICKS.put(id, s.cooldown * 20);
        if (s.debug) LOG.info("[dshcore] chain queued: {} blocks, player={}, group={}", chain.size(), player.getName().getString(), group.name);
    }

    public static void tick() {
        if (JOBS.isEmpty() && COOLDOWN_TICKS.isEmpty()) return;
        List<ServerLevel> empty = new ArrayList<>();
        for (Map.Entry<ServerLevel, ArrayDeque<Job>> entry : JOBS.entrySet()) {
            ArrayDeque<Job> queue = entry.getValue();
            for (Job job : new ArrayList<>(queue)) {
                if (job.wait > 0) {
                    job.wait--;
                    continue;
                }
                BlockPos next = job.positions.poll();
                if (next == null) {
                    queue.remove(job);
                    continue;
                }
                process(job, next);
                job.wait = Math.max(0, config.settings.delay - 1);
                if (job.positions.isEmpty()) queue.remove(job);
            }
            if (queue.isEmpty()) empty.add(entry.getKey());
        }
        for (ServerLevel level : empty) JOBS.remove(level);
        if (!COOLDOWN_TICKS.isEmpty()) {
            List<UUID> done = new ArrayList<>();
            for (Map.Entry<UUID, Integer> e : COOLDOWN_TICKS.entrySet()) {
                int left = e.getValue() - 1;
                if (left <= 0) done.add(e.getKey()); else e.setValue(left);
            }
            for (UUID id : done) COOLDOWN_TICKS.remove(id);
        }
    }

    private static void process(Job job, BlockPos pos) {
        if (job.player.isRemoved() || job.player.hasDisconnected()) return;
        BlockState state = job.level.getBlockState(pos);
        if (state.isAir() || !matches(state, job.group)) return;
        ItemStack tool = job.player.getItemInHand(InteractionHand.MAIN_HAND);
        INTERNAL.set(Boolean.TRUE);
        try {
            job.level.destroyBlock(pos, true, job.player, 512);
            if (config.settings.decreaseDurability && !job.player.isCreative() && tool.isDamageableItem()) {
                tool.hurtAndBreak(1, job.level, job.player, item -> {
                });
            }
            if (config.settings.hungerPerBlock > 0.0D && !job.player.isCreative()) {
                job.player.getFoodData().addExhaustion((float) config.settings.hungerPerBlock);
            }
        } catch (Exception e) {
            LOG.warn("[dshcore] chain block failed at {}: {}", pos, e.toString());
        } finally {
            INTERNAL.set(Boolean.FALSE);
        }
    }

    public static void clear() {
        JOBS.clear();
        COOLDOWN_TICKS.clear();
    }

    private static ChainPattern selectedPattern() {
        String wanted = config.settings.pattern;
        for (ChainPattern pattern : config.patterns) {
            if (pattern.enabled && pattern.id.equalsIgnoreCase(wanted)) return pattern;
        }
        for (ChainPattern pattern : config.patterns) {
            if (pattern.enabled) return pattern;
        }
        return new ChainPattern("normal", PatternType.NORMAL);
    }

    private static BlockGroup findGroup(BlockState state) {
        for (BlockGroup group : config.groups) {
            if (group.enabled && matches(state, group)) return group;
        }
        return null;
    }

    private static boolean matches(BlockState state, BlockGroup group) {
        if (group == null || group.blocks == null) return false;
        for (String entry : group.blocks) {
            if (entry == null || entry.isBlank()) continue;
            if (entry.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(entry.substring(1));
                if (id == null) continue;
                if (state.is(TagKey.create(Registries.BLOCK, id))) return true;
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id != null && BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id)) return true;
            }
        }
        return false;
    }

    private static boolean toolMatches(ItemStack stack, BlockGroup group) {
        if (group == null || group.tools == null || group.tools.isEmpty()) return true;
        for (String entry : group.tools) {
            if (entry == null || entry.isBlank()) continue;
            if (entry.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(entry.substring(1));
                if (id != null && stack.is(TagKey.create(Registries.ITEM, id))) return true;
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id != null && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) return true;
            }
        }
        return false;
    }

    private static List<BlockPos> collect(ServerLevel level, BlockPos origin, BlockState originState,
                                          BlockGroup group, ChainPattern pattern, ChainSettings s, ServerPlayer player) {
        if (pattern == null) pattern = new ChainPattern("normal", PatternType.NORMAL);
        int max = s.maxChain;
        int radius = s.searchRadius;
        List<BlockPos> out = new ArrayList<>();
        switch (pattern.type) {
            case NORMAL -> {
                Set<BlockPos> visited = new HashSet<>();
                ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
                frontier.add(origin);
                visited.add(origin);
                while (!frontier.isEmpty() && out.size() < max) {
                    BlockPos cur = frontier.poll();
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                BlockPos next = cur.offset(dx, dy, dz);
                                if (!visited.add(next)) continue;
                                if (distSq(origin, next) > (double) radius * radius) continue;
                                if (matches(level.getBlockState(next), group)) {
                                    out.add(next);
                                    frontier.add(next);
                                    if (out.size() >= max) return out;
                                }
                            }
                        }
                    }
                }
            }
            case FLAT -> {
                Set<BlockPos> visited = new HashSet<>();
                ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
                frontier.add(origin);
                visited.add(origin);
                while (!frontier.isEmpty() && out.size() < max) {
                    BlockPos cur = frontier.poll();
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            BlockPos next = cur.offset(dx, 0, dz);
                            if (!visited.add(next)) continue;
                            if (distSq(origin, next) > (double) radius * radius) continue;
                            if (matches(level.getBlockState(next), group)) {
                                out.add(next);
                                frontier.add(next);
                                if (out.size() >= max) return out;
                            }
                        }
                    }
                }
            }
            case TUNNEL -> {
                Direction dir = player.getDirection();
                Direction side = dir.getClockWise();
                int w = Math.max(1, pattern.width);
                int h = Math.max(1, pattern.height);
                int halfW = (w - 1) / 2;
                int halfH = (h - 1) / 2;
                for (int step = 1; step <= radius && out.size() < max; step++) {
                    BlockPos center = origin.relative(dir, step);
                    for (int sw = -halfW; sw <= halfW && out.size() < max; sw++) {
                        for (int sh = -halfH; sh <= halfH && out.size() < max; sh++) {
                            BlockPos p = center.offset(side.getStepX() * sw, sh, side.getStepZ() * sw);
                            if (matches(level.getBlockState(p), group)) out.add(p);
                        }
                    }
                }
            }
            case STAIRS -> {
                Direction dir = player.getDirection();
                Direction side = dir.getClockWise();
                int halfW = (Math.max(1, pattern.width) - 1) / 2;
                for (int step = 1; step <= radius && out.size() < max; step++) {
                    BlockPos center = origin.relative(dir, step).offset(0, pattern.stairsUp ? step : -step, 0);
                    for (int sw = -halfW; sw <= halfW && out.size() < max; sw++) {
                        BlockPos p = center.offset(side.getStepX() * sw, 0, side.getStepZ() * sw);
                        if (matches(level.getBlockState(p), group)) out.add(p);
                    }
                }
            }
        }
        return out;
    }

    private static double distSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class Job {
        final ServerPlayer player;
        final ServerLevel level;
        final BlockGroup group;
        final ArrayDeque<BlockPos> positions;
        int wait = 0;

        Job(ServerPlayer player, ServerLevel level, BlockGroup group, List<BlockPos> positions) {
            this.player = player;
            this.level = level;
            this.group = group;
            this.positions = new ArrayDeque<>(positions);
        }
    }

    /** Debug/permission helper used by commands. */
    public static void sendStatus(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[dshcore] chain enabled=" + config.settings.enabled
                + " pattern=" + config.settings.pattern + " groups=" + config.groups.size()), false);
    }
}
