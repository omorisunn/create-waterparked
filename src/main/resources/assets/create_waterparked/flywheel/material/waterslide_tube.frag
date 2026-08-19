// Per-fragment UV reconstruction.
// Vertex shader outputs unwrapped physical pixels; this shader tiles them inside the sprite.

in vec4 flw_tubeSprite;
in vec3 flw_tubeTex;
in vec4 flw_tubeFlags;
in vec2 flw_tubeExtra;

void flw_materialFragment() {
    float isWater = flw_tubeFlags.x;
    float texW = max(flw_tubeTex.x, 1.0);
    float texH = max(flw_tubeTex.y, 1.0);
    float borderPx = flw_tubeTex.z;

    vec2 uv;
    if (isWater > 0.5) {
        // v was already divided by the tile span in the vertex shader, so the
        // repeat is `span` blocks wide (smaller striping under shaderpacks)
        float u = flw_tubeSprite.x + mod(flw_vertexTexCoord.x, 1.0) * (flw_tubeSprite.y - flw_tubeSprite.x);
        float vDown = flw_tubeSprite.z + mod(flw_vertexTexCoord.y, 1.0) * (flw_tubeSprite.w - flw_tubeSprite.z);
        float vUp = flw_tubeSprite.z + mod(flw_tubeExtra.x, 1.0) * (flw_tubeSprite.w - flw_tubeSprite.z);
        vec4 up = texture(flw_diffuseTex, vec2(u, vUp));
        vec4 down = texture(flw_diffuseTex, vec2(u, vDown));
        flw_sampleColor = mix(up, down, flw_tubeExtra.y);
        flw_fragColor = flw_vertexColor * flw_sampleColor;
        return;
    } else {
        // atlas-space sprite uv is baked into the mesh (kept clean for the
        // shaderpack path which samples texture() with the same vertex uv);
        // sample directly — border pixels are part of the sprite
        uv = flw_vertexTexCoord;
    }

    flw_sampleColor = texture(flw_diffuseTex, uv);
    flw_fragColor = flw_vertexColor * flw_sampleColor;
}
