package net.omori_sunny.create_waterparked.client.flywheel;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.instance.ColoredLitOverlayInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class SupportInstance extends ColoredLitOverlayInstance {
    public final Vector3f origin = new Vector3f();
    // 1 = whole-face texture tiling (beam: one full sprite per face, v repeats
    // every block); 0 = wall-style 16px/block border tiling (bracket shell)
    public float fullTileMode = 0f;
    // sprite rect of this mesh's texture (per-instance so mesh attributes stay clean)
    public float spriteU0 = 0f, spriteU1 = 1f, spriteV0 = 0f, spriteV1 = 1f;
    // real bounding sphere of the CPU-baked mesh, in INSTANCE space (relative to
    // `origin`). The baked geometry can sit far from the origin, so these ride
    // the instance instead of the fixed 6.0 sphere at the origin that used to
    // cull the beam/bracket out whenever the camera moved.
    public final Vector3f boundCenter = new Vector3f();
    public float boundRadius = 1f;

    public SupportInstance(InstanceType<? extends SupportInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public SupportInstance setOrigin(Vec3 v) {
        this.origin.set((float) v.x, (float) v.y, (float) v.z);
        return this;
    }

    public SupportInstance setZeroOrigin() {
        this.origin.zero();
        return this;
    }

    public SupportInstance setBounds(Vec3 center, float radius) {
        this.boundCenter.set((float) center.x, (float) center.y, (float) center.z);
        this.boundRadius = radius;
        return this;
    }
}