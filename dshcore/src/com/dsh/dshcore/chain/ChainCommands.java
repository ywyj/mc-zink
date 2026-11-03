package com.dsh.dshcore.chain;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.dsh.dshcore.DshCoreMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/** Command surface mirroring the original mod's /veinminer tree (settings/groups/blocks/tools/reload/use/toggle). */
@EventBusSubscriber(modid = DshCoreMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ChainCommands {
    private ChainCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("veinminer")
                .executes(ctx -> status(ctx))
                .then(Commands.literal("reload").executes(ctx -> {
                    ChainEngine.loadConfig();
                    ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] chain config reloaded"), true);
                    return 1;
                }))
                .then(Commands.literal("toggle").executes(ctx -> {
                    ChainSettings s = ChainEngine.config().settings;
                    s.enabled = !s.enabled;
                    ChainEngine.config().save();
                    ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] chain " + (s.enabled ? "enabled" : "disabled")), true);
                    return 1;
                }))
                .then(Commands.literal("settings")
                        .then(Commands.literal("list").executes(ctx -> {
                            ChainSettings s = ChainEngine.config().settings;
                            ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] enabled=" + s.enabled
                                    + " cooldown=" + s.cooldown + " mustSneak=" + s.mustSneak
                                    + " delay=" + s.delay + " maxChain=" + s.maxChain
                                    + " needCorrectTool=" + s.needCorrectTool + " searchRadius=" + s.searchRadius
                                    + " permissionRestricted=" + s.permissionRestricted
                                    + " mergeItemDrops=" + s.mergeItemDrops + " autoUpdate=" + s.autoUpdate
                                    + " decreaseDurability=" + s.decreaseDurability
                                    + " hungerPerBlock=" + s.hungerPerBlock
                                    + " miningSpeedModifier=" + s.miningSpeedModifier
                                    + " pattern=" + s.pattern), false);
                            return 1;
                        }))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setSetting(ctx, StringArgumentType.getString(ctx, "key"), StringArgumentType.getString(ctx, "value")))))))
                .then(Commands.literal("groups")
                        .then(Commands.literal("list").executes(ctx -> {
                            ChainConfig cfg = ChainEngine.config();
                            List<String> names = new ArrayList<>();
                            for (BlockGroup g : cfg.groups) names.add(g.name + (g.enabled ? "" : "(off)"));
                            ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] groups: " + String.join(", ", names)), false);
                            return 1;
                        })))
                .then(Commands.literal("blocks")
                        .then(Commands.literal("add")
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .then(Commands.argument("block", StringArgumentType.word())
                                                .executes(ctx -> editList(ctx, true, true)))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .then(Commands.argument("block", StringArgumentType.word())
                                                .executes(ctx -> editList(ctx, false, true))))))
                .then(Commands.literal("tools")
                        .then(Commands.literal("add")
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .then(Commands.argument("tool", StringArgumentType.word())
                                                .executes(ctx -> editList(ctx, true, false)))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .then(Commands.argument("tool", StringArgumentType.word())
                                                .executes(ctx -> editList(ctx, false, false))))))
                .then(Commands.literal("patterns")
                        .then(Commands.literal("list").executes(ctx -> {
                            List<String> ids = new ArrayList<>();
                            for (ChainPattern p : ChainEngine.config().patterns) ids.add(p.id + "[" + p.type + (p.enabled ? "" : ",off") + "]");
                            ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] patterns: " + String.join(", ", ids)), false);
                            return 1;
                        }))
                        .then(Commands.literal("use")
                                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    ChainEngine.config().settings.pattern = id;
                                    ChainEngine.config().save();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] pattern set to " + id), true);
                                    return 1;
                                })))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ChainEngine.sendStatus(ctx.getSource());
        return 1;
    }

    private static int setSetting(CommandContext<CommandSourceStack> ctx, String key, String value) {
        ChainSettings s = ChainEngine.config().settings;
        try {
            switch (key) {
                case "enabled" -> s.enabled = Boolean.parseBoolean(value);
                case "cooldown" -> s.cooldown = Integer.parseInt(value);
                case "mustSneak" -> s.mustSneak = Boolean.parseBoolean(value);
                case "delay" -> s.delay = Integer.parseInt(value);
                case "maxChain" -> s.maxChain = Integer.parseInt(value);
                case "needCorrectTool" -> s.needCorrectTool = Boolean.parseBoolean(value);
                case "searchRadius" -> s.searchRadius = Integer.parseInt(value);
                case "permissionRestricted" -> s.permissionRestricted = Boolean.parseBoolean(value);
                case "mergeItemDrops" -> s.mergeItemDrops = Boolean.parseBoolean(value);
                case "autoUpdate" -> s.autoUpdate = Boolean.parseBoolean(value);
                case "decreaseDurability" -> s.decreaseDurability = Boolean.parseBoolean(value);
                case "hungerPerBlock" -> s.hungerPerBlock = Double.parseDouble(value);
                case "miningSpeedModifier" -> s.miningSpeedModifier = Double.parseDouble(value);
                case "pattern" -> s.pattern = value;
                case "debug" -> s.debug = Boolean.parseBoolean(value);
                default -> {
                    ctx.getSource().sendFailure(Component.literal("[dshcore] unknown setting: " + key));
                    return 0;
                }
            }
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[dshcore] bad value for " + key + ": " + value));
            return 0;
        }
        s.sanitize();
        ChainEngine.config().save();
        ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] " + key + " = " + value), true);
        return 1;
    }

    private static int editList(CommandContext<CommandSourceStack> ctx, boolean add, boolean blocks) {
        String groupName = StringArgumentType.getString(ctx, "group");
        String entry = StringArgumentType.getString(ctx, blocks ? "block" : "tool");
        BlockGroup group = null;
        for (BlockGroup g : ChainEngine.config().groups) {
            if (g.name.equalsIgnoreCase(groupName)) {
                group = g;
                break;
            }
        }
        if (group == null) {
            ctx.getSource().sendFailure(Component.literal("[dshcore] no group: " + groupName));
            return 0;
        }
        final String groupDisplay = group.name;
        List<String> list = blocks ? group.blocks : group.tools;
        if (add) {
            if (!list.contains(entry)) list.add(entry);
        } else {
            list.remove(entry);
        }
        ChainEngine.config().save();
        ctx.getSource().sendSuccess(() -> Component.literal("[dshcore] " + (add ? "added " : "removed ") + entry
                + (blocks ? " to blocks of " : " to tools of ") + groupDisplay), true);
        return 1;
    }
}
