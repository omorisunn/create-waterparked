package net.omori_sunny.create_waterparked.mixin.client.iris;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.math.MatrixUtil;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat;
import net.omori_sunny.create_waterparked.client.compat.IrisWaterInjection;

/**
 * Pushes the mounted-slide tube water + thrown water through the SAME GL
 * program + uniforms that Sodium/Iris are currently running for the water
 * (translucent terrain) pass, so whichever shaderpack is active shades them
 * with its own water shader (gbuffers_water). Only registered when Sodium and
 * Iris are installed (see WaterparkedMixinPlugin).
 */
@Mixin(ShaderChunkRenderer.class)
public abstract class IrisWaterPassMixin {

    @Shadow
    protected GlProgram<ChunkShaderInterface> activeProgram;

    @Inject(method = "begin", at = @At("TAIL"), remap = false)
    private void waterparked$flushWaterPass(TerrainRenderPass pass, CallbackInfo ci) {
        // With Colorwheel present the water is rendered through the Flywheel
        // visual path (entity-stamped meshes -> pack water program); the chunk
        // injection would draw a second, unshaded copy on top. Keep this path
        // only for iris-without-colorwheel setups.
        if (!IrisColorwheelCompat.waterShadingActive()) return;
        if (IrisColorwheelCompat.colorwheelPresent()) return;
        if (pass != DefaultTerrainRenderPasses.TRANSLUCENT) return;
        GlProgram<ChunkShaderInterface> prog = this.activeProgram;
        if (prog == null) return;

        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.getMainCamera();
        if (mc.level == null || mc.player == null) return;

        // view = inverse camera rotation * -eye (what the terrain shader expects)
        Matrix4f view = new Matrix4f().set(cam.rotation());
        view.invert();
        view.translate(new org.joml.Vector3f(
            (float) -cam.getPosition().x,
            (float) -cam.getPosition().y,
            (float) -cam.getPosition().z));

        Matrix4f proj = new Matrix4f(mc.gameRenderer.getProjectionMatrix(1.0F));

        ChunkShaderInterface iface = prog.getInterface();
        iface.setupState();
        iface.setProjectionMatrix(proj);
        iface.setModelViewMatrix(view);
        net.minecraft.world.phys.Vec3 off = net.omori_sunny.create_waterparked.client.compat.IrisWaterInjection.currentRegionOffset();
        iface.setRegionOffset((float) off.x, (float) off.y, (float) off.z);
        IrisWaterInjection.renderWaterGeometry();
        iface.resetState();
    }
}
