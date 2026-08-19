// Support structures are pure-translation instances with CPU-baked positions
// that can sit far from the instance origin, so every instance carries its own
// real bounding sphere (boundCenter in instance space + boundRadius).

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    center = i.origin + i.boundCenter;
    radius = max(i.boundRadius, 0.1);
}