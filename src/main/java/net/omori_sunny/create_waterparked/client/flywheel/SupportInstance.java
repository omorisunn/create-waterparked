package net.omori_sunny.create_waterparked.client.flywheel;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.instance.ColoredLitOverlayInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class SupportInstance extends ColoredLitOverlayInstance {
    public final Vector3f origin = new Vector3f();
    // 1 = whole face tiling, 0 = wall style 16px border tiling
    public float fullTileMode = 0f;
    // sprite rect of this mesh texture, instances keep mesh attributes clean
    public float spriteU0 = 0f, spriteU1 = 1f, spriteV0 = 0f, spriteV1 = 1f;
    // real bounding sphere of the baked mesh in instance space, rides the instance
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