package com.dsh.dshcore.ec;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Compatibility gate for the EntityCulling mixin config bundled into DSH Core 1.2.
 *
 * <p>EntityCulling is bundled with unchanged bytecode, so its mixins are written against the
 * runtime class layout of Minecraft 1.21.1 / NeoForge 21.1.x. To make a mismatched environment
 * degrade instead of hard-crashing, this plugin:
 *
 * <ul>
 *   <li>probes client classes by classpath resource lookup (never loads game classes, so it is
 *       safe during mixin application);</li>
 *   <li>when the expected 1.21.1 client classes are demonstrably absent, denies the whole
 *       EntityCulling mixin config and logs one WARN line (the mixin config itself is also
 *       required=false / defaultRequire=0 for the case where a single mixin cannot be applied);</li>
 *   <li>per mixin target, additionally verifies that the target class resource exists and denies
 *       just that mixin when it does not;</li>
 *   <li>fails open when its own classloader cannot be probed reliably (so a probe limitation can
 *       never silently disable a working EntityCulling install); never throws.</li>
 * </ul>
 */
public final class EcCompatMixinPlugin implements IMixinConfigPlugin {
    private static final String SELF = "com/dsh/dshcore/ec/EcCompatMixinPlugin.class";
    private static final String[] SENTINELS = {
        "net/minecraft/client/Minecraft.class",
        "net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher.class"
    };

    private boolean disabled;
    private boolean warned;
    private boolean targetCheck = true;

    public EcCompatMixinPlugin() {
        // no-op
    }

    @Override
    public void onLoad(String mixinPackage) {
        try {
            ClassLoader cl = EcCompatMixinPlugin.class.getClassLoader();
            if (cl.getResource(SELF) == null) {
                // cannot probe this classloader reliably: fail open, no per-target gating
                targetCheck = false;
                return;
            }
            int found = 0;
            for (String sentinel : SENTINELS) {
                if (cl.getResource(sentinel) != null) {
                    found++;
                }
            }
            if (found == 0) {
                disabled = true;
                warnOnce("Minecraft 1.21.1 client classes not found (unsupported environment)");
            }
        } catch (Throwable t) {
            disabled = true;
            warnOnce("compat probe failed: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (disabled) {
            return false;
        }
        if (!targetCheck) {
            return true;
        }
        try {
            String path = targetClassName.replace('.', '/') + ".class";
            if (EcCompatMixinPlugin.class.getClassLoader().getResource(path) == null) {
                warnOnce("target class not present, skipping " + mixinClassName);
                return false;
            }
            return true;
        } catch (Throwable t) {
            targetCheck = false;
            warnOnce("target probe failed, applying mixins without per-target check: "
                    + t.getClass().getSimpleName());
            return true;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        // no-op
    }

    private void warnOnce(String why) {
        if (warned) {
            return;
        }
        warned = true;
        System.out.println("[DSH Core] EntityCulling mixin gate: " + why
                + " - EntityCulling mixins are disabled for this environment");
    }
}
