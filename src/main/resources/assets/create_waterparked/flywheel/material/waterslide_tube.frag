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
    } else if (flw_vertexTexCoord.x < 0.0) {
        // wall shells, fins and caps: raw world-arc block coordinates from the vertex shader,
        // folded here. Folding per fragment keeps interpolation continuous, so quads crossing
        // a tile boundary never shear; every tile is exactly one block and partial tiles at
        // fins and caps simply truncate at their outer edge
        float u = flw_tubeSprite.x + fract(-flw_vertexTexCoord.x) * (flw_tubeSprite.y - flw_tubeSprite.x);
        float vRaw = flw_vertexTexCoord.y;
        float v;
        if (flw_tubeFlags.w > 1.5) {
            // glass: clamp the two end bands of the curve into the sprite border rows,
            // the body tiles normally; total arc arrives in flw_tubeExtra.y
            float total = max(flw_tubeExtra.y, 0.1);
            float gTexH = clamp(round((flw_tubeSprite.w - flw_tubeSprite.z) * 1024.0), 1.0, 64.0);
            float vPx = -1.0;
            if (vRaw < borderPx / 16.0) {
                vPx = max(vRaw * 16.0, 0.05);
            } else if (vRaw > total - borderPx / 16.0) {
                vPx = min(gTexH - borderPx + (vRaw - (total - borderPx / 16.0)) * 16.0, gTexH - 0.05);
            }
            v = vPx >= 0.0
                ? flw_tubeSprite.z + (vPx / gTexH) * (flw_tubeSprite.w - flw_tubeSprite.z)
                : flw_tubeSprite.z + fract(vRaw) * (flw_tubeSprite.w - flw_tubeSprite.z);
        } else {
            v = flw_tubeSprite.z + fract(vRaw) * (flw_tubeSprite.w - flw_tubeSprite.z);
        }
        uv = vec2(u, v);
    } else {
        // atlas-space sprite uv is baked into the mesh (kept clean for the
        // shaderpack path which samples texture() with the same vertex uv);
        // sample directly — border pixels are part of the sprite
        uv = flw_vertexTexCoord;
    }

    flw_sampleColor = texture(flw_diffuseTex, uv);
    flw_fragColor = flw_vertexColor * flw_sampleColor;
}
