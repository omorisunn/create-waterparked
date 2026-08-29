package net.omori_sunny.create_waterparked.mixin.config;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.ModList;

// gate optional platform mixins when Sodium or Iris are missing
public class WaterparkedMixinPlugin implements IMixinConfigPlugin {

    private static final String IRIS_PASS_MIXIN = "client.iris.IrisWaterPassMixin";
    private static final String IRIS_PROBE_MIXIN = "client.iris.GlShaderSourceProbeMixin";
    private static final String COLORWHEEL_MIXIN = "client.colorwheel.ColorwheelWaterEntityMixin";
    private static final String COLORWHEEL_TARGET = "dev.djefrey.colorwheel.engine.ClrwlMeshPool";
    private static final String PONDER_PACKAGE = "ponder.";
    private static final String PONDER_LEVEL = "net.createmod.ponder.api.level.PonderLevel";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(IRIS_PASS_MIXIN) || mixinClassName.endsWith(IRIS_PROBE_MIXIN)) {
            // ModList is unavailable, decide by target class presence
            return classResourceExists(targetClassName);
        }
        if (mixinClassName.endsWith(COLORWHEEL_MIXIN)) {
            // never Class.forName here, use a load free resource probe
            return classResourceExists(COLORWHEEL_TARGET);
        }
        // Ponder is optional: without the Ponder mod all ponder mixins must be
        // skipped, otherwise the mixin engine fails to apply them (target
        // classes absent) and the client crashes on load
        if (mixinClassName.startsWith(PONDER_PACKAGE)) {
            return classResourceExists(PONDER_LEVEL);
        }
        return true;
    }

    // load free presence check through the context class loader
    private static boolean classResourceExists(String binaryName) {
        try {
            String path = binaryName.replace('.', '/') + ".class";
            return Thread.currentThread().getContextClassLoader().getResource(path) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
