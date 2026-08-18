package net.omori_sunny.create_waterparked.mixin.config;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * Gates optional-platform mixins so the mod keeps working without Sodium/Iris.
 * The water-pass injection only ever applies when both the full Sodium renderer
 * AND Iris are present at runtime (otherwise the @Mixin would crash a clean
 * installation at class load).
 */
public class WaterparkedMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("client.iris.IrisWaterPassMixin")) {
            // ModList is NOT available during mixin application (class
            // transformation runs before mod loading), so decide by target
            // class presence: the water-pass injection only applies when the
            // full Sodium chunk renderer is on the classpath.
            return classResourceExists(targetClassName);
        }
        if (mixinClassName.endsWith("client.iris.GlShaderSourceProbeMixin")) {
            // Water-classification injection for Colorwheel's clrwl programs:
            // only applies when Iris is present (GlShader is Iris's class).
            return classResourceExists(targetClassName);
        }
        if (mixinClassName.endsWith("client.colorwheel.ColorwheelWaterEntityMixin")) {
            // NEVER Class.forName here: loading the target class during mixin
            // config preparation cements it in the JVM without the mixin
            // applied (the transformer only runs on first load), so the mixin
            // would silently never apply. A load-free resource probe sees mod
            // jars at this stage (same mechanism the Sodium guard uses) and
            // leaves the class untouched until Colorwheel itself instantiates
            // it, at which point the mixin applies normally.
            return classResourceExists("dev.djefrey.colorwheel.engine.ClrwlMeshPool");
        }
        return true;
    }

    // Load-free presence check via the context class loader's resources.
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
