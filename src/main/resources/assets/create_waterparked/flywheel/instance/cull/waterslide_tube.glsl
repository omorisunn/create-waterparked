// Bounding sphere covering the bent segment. Custom sections bake per-angle factors
// (0.3..3.0) into the model vertices, so the real geometry can reach ~3x the scalar
// radius; the sphere must cover that or the depth-pyramid cull drops whole segments.

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    vec3 mid = 0.5 * (i.prevSpine + i.currSpine);
    float chordLen = length(i.currSpine - i.prevSpine);
    center = mid;
    radius = 0.5 * chordLen + max(i.prevRadius, i.currRadius) * 3.0 + 0.5;
}
