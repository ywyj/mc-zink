package com.dsh.createopt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CreateOpt（DSH 自研）1.1.0 - zink 适配版。
 *
 * 0.6.0 的两个审计问题在 1.1.0 中处理：
 * 1) 不再用 net.vulkanmod.Initializer 做环境判定（zink 路线没有 VulkanMod 类，旧判定恒 false 使 vkGuard 分支形同虚设）；
 *    1.1.0 删除全部 VulkanMod 专用分支/mixin，环境探测只看 GL_RENDERER/GL_VENDOR/GL_VERSION 并只打日志，不参与行为分叉。
 * 2) RenderTargetStencilMixin 无条件 cancel enableStencil 已删除：zink 是标准 GL 路径，stencil 可用；TACZ/超分等模组的 enableStencil 不再被误伤。
 *
 * 保留的 4 个 mixin 均为纯逻辑剔除（不碰 GL/管线）：
 * EntityCullingMixin / BlockEntityCullingMixin / ParticleCullingMixin / EntityShadowMixin。
 */
@Mod(CreateOpt.MOD_ID)
public final class CreateOpt {
    public static final String MOD_ID = "createopt";
    public static final String MOD_VERSION = "1.1.0";
    public static final Logger LOG = LoggerFactory.getLogger("CreateOpt");

    /** 全局配置（启动时从 config/createopt.json 读取，缺失的键用默认值并回写） */
    public static Settings SETTINGS = new Settings();

    /** lite 包（-Pprofile=lite 构建）：jar 内没有任何 mixin 配置，只做事件剔除 */
    public static boolean LITE = false;

    private static long lastDebugMs = 0L;
    private static long suppressedDebug = 0L;

    public CreateOpt(IEventBus modEventBus, ModContainer modContainer) {
        LITE = CreateOpt.class.getResource("/createopt-lite.marker") != null;
        load();

        LOG.info("[CreateOpt] v{} loaded (lite={}): entityCull={} blockEntityCull={} particleCull={}", MOD_VERSION, LITE,
                SETTINGS.entityCullDistance, SETTINGS.blockEntityCullDistance, SETTINGS.particleCullDistance);
        LOG.info("[CreateOpt] occlusion={} occRange=[{},{}] budgets={}/{} cacheMs={} eventCulling={} eventCull={}", SETTINGS.occlusion,
                SETTINGS.occlusionMinDistance, SETTINGS.occlusionMaxDistance, SETTINGS.entityOcclusionBudget,
                SETTINGS.blockEntityOcclusionBudget, SETTINGS.occlusionCacheMs, SETTINGS.eventCulling, SETTINGS.eventCullDistance);
        LOG.info("[CreateOpt] shadow={} keepPlayers={} keepBosses={} keepNamed={} debug={}",
                SETTINGS.disableVanillaEntityShadow, SETTINGS.keepPlayers, SETTINGS.keepBosses, SETTINGS.keepNamed, SETTINGS.debug);

        if (!FMLEnvironment.dist.isClient()) {
            LOG.info("[CreateOpt] non-client dist: culling features are inactive");
            return;
        }
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, Env::onFrame);
        NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre.class, (RenderLivingEvent.Pre event) -> EventCulling.onRenderLiving(event));
    }

    /** 节流 debug 日志：debug=true 时每秒最多一条，并汇报被吞掉的行数 */
    public static void debugLog(String msg, Object... args) {
        if (!SETTINGS.debug) return;
        long now = System.currentTimeMillis();
        if (now - lastDebugMs < 1000L) {
            suppressedDebug++;
            return;
        }
        if (suppressedDebug > 0L) {
            LOG.info("[CreateOpt] ({} debug lines suppressed)", suppressedDebug);
            suppressedDebug = 0L;
        }
        lastDebugMs = now;
        LOG.info(msg, args);
    }

    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("createopt.json");
        boolean existed = Files.exists(path);
        Settings loaded = null;
        if (existed) {
            try {
                loaded = new Gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), Settings.class);
            } catch (Exception e) {
                LOG.warn("[CreateOpt] config parse error, using defaults: {}", e.toString());
            }
        }
        Settings s = loaded != null ? loaded : new Settings();

        if (!existed) {
            if (LITE) {
                // 零 mixin 包默认只开事件剔除
                s.eventCulling = true;
                s.eventCullDistance = 64.0D;
            }
        } else if (s.configVersion < 2) {
            // 0.6.x 旧配置迁移：VulkanMod 专用开关失效 → 归零；遮挡剔除旧默认 true 太激进（与 EntityCulling/Opticores 重复射线）→ 默认关
            boolean hadVulkanSwitches = s.fixUnsupportedStencil || s.vkGuardGlInit || s.vkGuardFlywheelNoise;
            s.occlusion = false;
            s.fixUnsupportedStencil = false;
            s.vkGuardGlInit = false;
            s.vkGuardFlywheelNoise = false;
            if (s.eventCullDistance <= 0.0D) s.eventCullDistance = 64.0D;
            if (LITE) s.eventCulling = true;
            if (hadVulkanSwitches) {
                LOG.info("[CreateOpt] migrated legacy config: VulkanMod-only switches (fixUnsupportedStencil/vkGuardGlInit/vkGuardFlywheelNoise) are no longer supported and were reset to false");
            }
            LOG.info("[CreateOpt] migrated config to v2: occlusion=false (was true by default in 0.6.x; re-enable only if you do not run EntityCulling/Opticores)");
        }
        if (s.configVersion != 2) s.configVersion = 2;
        sanitize(s);
        SETTINGS = s;

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[CreateOpt] config write error: {}", e.toString());
        }
    }

    private static void sanitize(Settings s) {
        s.entityCullDistance = clamp(s.entityCullDistance, 0.0D, 512.0D);
        s.blockEntityCullDistance = clamp(s.blockEntityCullDistance, 0.0D, 512.0D);
        s.particleCullDistance = clamp(s.particleCullDistance, 0.0D, 512.0D);
        s.eventCullDistance = clamp(s.eventCullDistance, 0.0D, 512.0D);
        s.occlusionMinDistance = clamp(s.occlusionMinDistance, 0.0D, 256.0D);
        s.occlusionMaxDistance = clamp(s.occlusionMaxDistance, s.occlusionMinDistance, 512.0D);
        s.occlusionStep = clamp(s.occlusionStep, 0.1D, 4.0D);
        s.occlusionCacheMs = clamp(s.occlusionCacheMs, 0L, 5000L);
        s.entityOcclusionBudget = (int) clamp(s.entityOcclusionBudget, 0L, 512L);
        s.blockEntityOcclusionBudget = (int) clamp(s.blockEntityOcclusionBudget, 0L, 512L);
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    public static final class Settings {
        /** 配置版本：0.6.x 没有该字段（读出来是 0），1.1.0 起为 2，用于自动迁移 */
        public int configVersion = 0;

        /** 实体（LivingEntity 等所有 Entity）超过这个距离不渲染（0 = 关闭距离剔除；单位：格） */
        public double entityCullDistance = 64.0D;
        /** 方块实体超过这个距离不渲染（0 = 关闭；Create 机械的 BER 走这条） */
        public double blockEntityCullDistance = 48.0D;
        /** 是否启用遮挡剔除（被方块挡住的不渲染）。1.1.0 默认关：实例已有 EntityCulling + Opticores 做异步遮挡，双份射线不划算 */
        public boolean occlusion = false;
        /** 低于这个距离不做遮挡测试（在脸上本来就能看见） */
        public double occlusionMinDistance = 6.0D;
        /** 超过这个距离不做遮挡测试（省 CPU） */
        public double occlusionMaxDistance = 96.0D;
        /** 每 16ms 窗口内最多做多少次实体遮挡射线 */
        public int entityOcclusionBudget = 24;
        /** 每 16ms 窗口内最多做多少次方块实体遮挡射线 */
        public int blockEntityOcclusionBudget = 24;
        /** 遮挡测试结果缓存时间（毫秒） */
        public long occlusionCacheMs = 200L;
        /** 射线采样步长（格），越小越准越费 CPU */
        public double occlusionStep = 0.75D;
        /** 超过这个距离的粒子不再生成（0 = 关闭）。粒子剔除最便宜：不生成就不 tick、不提交 */
        public double particleCullDistance = 48.0D;
        /** 关闭原版实体脚下 blob 阴影（光影通常自带 entity shadow，可关；默认关） */
        public boolean disableVanillaEntityShadow = false;

        /** 纯事件剔除（零 mixin 路径）：只覆盖 LivingEntity/Player，1.1.0 默认关，lite 包默认开 */
        public boolean eventCulling = false;
        /** 纯事件剔除距离（格） */
        public double eventCullDistance = 64.0D;

        public boolean keepPlayers = true;
        public boolean keepBosses = true;
        public boolean keepNamed = true;
        public boolean debug = false;

        // ---- 以下为 0.6.x 的 VulkanMod 专用开关：1.1.0 起保留字段只为兼容旧配置文件，行为上完全忽略 ----
        public boolean fixUnsupportedStencil = false;
        public boolean vkGuardGlInit = false;
        public boolean vkGuardFlywheelNoise = false;
    }
}
