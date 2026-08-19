// Support structure (bracket shell + beam) instance vertex shader.
// Pure translation instancing: positions are baked CPU-side in instance space
// (no bezier/radius GPU transform), so textures are never sheared by the
// instance transform. UVs are atlas-space sprite rects baked by the mesh
// builder — exactly what Colorwheel feeds the shaderpack's texture() sampling.

void flw_instanceVertex(in FlwInstance i) {
    vec3 worldPos = flw_vertexPos.xyz + i.origin;
    flw_vertexPos = vec4(worldPos, 1.0);
    flw_vertexNormal = flw_vertexNormal;
    flw_vertexColor = i.color;
    flw_vertexOverlay = i.overlay;
    flw_vertexLight = vec2(i.light) / 256.0;
    // UVs pass through untouched (CPU-baked atlas coordinates)
}