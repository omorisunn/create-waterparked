// Support structure fragment shader: atlas-space sprite uv (baked by the mesh
// builder in the same coordinate space Colorwheel feeds the shaderpack), so
// we sample directly and the pack samples identically — no whole-atlas bleed.

void flw_materialFragment() {
    flw_sampleColor = texture(flw_diffuseTex, flw_vertexTexCoord);
    flw_fragColor = flw_vertexColor * flw_sampleColor;
}