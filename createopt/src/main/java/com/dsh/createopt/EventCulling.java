package com.dsh.createopt;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * 纯事件剔除路径（零 mixin 方案）。
 *
 * 可行性结论（详见 REPORT.md）：
 * - NeoForge 1.21.1 只有 RenderLivingEvent.Pre / RenderPlayerEvent.Pre 可取消渲染；
 * - 没有方块实体渲染取消事件（client/event 里只有 EntityRenderersEvent 注册 API）→ Create 机械的 BER 覆盖不到；
 * - 没有粒子生成事件、没有实体脚下阴影事件。
 * 所以纯事件版只能做 living entity 距离剔除，作为 lite 包 / 保守用户的可选路径，默认关闭。
 */
public final class EventCulling {
    private EventCulling() {}

    @SuppressWarnings("rawtypes")
    public static void onRenderLiving(RenderLivingEvent.Pre event) {
        CreateOpt.Settings s = CreateOpt.SETTINGS;
        if (!s.eventCulling || s.eventCullDistance <= 0.0D) return;

        LivingEntity entity = event.getEntity();
        if (entity == null) return;
        if (s.keepPlayers && entity instanceof Player) return;
        if (s.keepBosses && Culling.isBoss(entity)) return;
        if (s.keepNamed && entity.hasCustomName()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;
        // Sable 子关卡 / 其它 level 的实体：渲染坐标已被变换，不按世界坐标判断
        if (entity.level() != mc.level) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dist = cam.distanceTo(entity.position());
        if (dist > s.eventCullDistance) {
            CreateOpt.debugLog("[CreateOpt] event-cull {} at {} blocks", entity.getType(), String.format("%.1f", dist));
            event.setCanceled(true);
        }
    }
}
