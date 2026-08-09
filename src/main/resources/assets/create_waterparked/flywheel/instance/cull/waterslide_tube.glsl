// Bounding sphere covering the bent segment.

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    vec3 mid = 0.5 * (i.prevSpine + i.currSpine);
    float chordLen = length(i.currSpine - i.prevSpine);
    center = mid;
    radius = 0.5 * chordLen + max(i.prevRadius, i.currRadius) + 0.5;
}
