// Per-fragment UV reconstruction.
// Vertex shader outputs unwrapped physical pixels; this shader tiles them inside the sprite.

in vec4 flw_tubeSprite;
in vec3 flw_tubeTex;
in vec4 flw_tubeFlags;
in vec4 flw_tubeExtra;

// seamless center-band fold for a world-locked block coordinate: the texture's border rows
// are never sampled, the center repeats on a world-continuous phase at native pixel scale —
// nothing stretches, and adjacent tiles connect. border=0 is plain tiling.
float sliceCoord(float xBlocks, float texPx, float border) {
    float centerW = texPx - 2.0 * border;
    if (centerW < 1.0) return fract(xBlocks) * texPx;
    return border + mod(xBlocks * texPx - border, centerW);
}

// ring-arc u for wall shells: REAL border strips only at the sector's own edges (the
// fragment's sector bounds arrive in flw_tubeExtra for opaque walls); the interior folds
// with the seamless center band
float wallU(float uBlocks, float texPx, float border, vec4 sprite, vec2 sector) {
    float span = sprite.y - sprite.x;
    float bBlocks = border / texPx;
    float su0 = sector.x;
    float su1 = sector.y;
    if (border >= 0.5 && su1 - su0 > 2.0 * bBlocks) {
        float p;
        if (uBlocks >= su0 && uBlocks < su0 + bBlocks) {
            p = (uBlocks - su0) / bBlocks * border;
        } else if (uBlocks > su1 - bBlocks && uBlocks <= su1) {
            p = texPx - border + (uBlocks - (su1 - bBlocks)) / bBlocks * border;
        } else {
            p = sliceCoord(uBlocks, texPx, border);
        }
        return sprite.x + (p / texPx) * span;
    }
    return sprite.x + (sliceCoord(uBlocks, texPx, border) / texPx) * span;
}

// curve-length v for wall shells: REAL border strips at the curve's two mouths (the curve's
// world v range arrives in flw_tubeExtra.zw); the interior folds seamlessly
float wallV(float vBlocks, float texPx, float border, vec4 sprite, vec2 vRange) {
    float span = sprite.w - sprite.z;
    float bBlocks = border / texPx;
    float v0 = vRange.x;
    float v1 = vRange.y;
    if (border >= 0.5 && v1 - v0 > 2.0 * bBlocks) {
        float p;
        if (vBlocks >= v0 && vBlocks < v0 + bBlocks) {
            p = (vBlocks - v0) / bBlocks * border;
        } else if (vBlocks > v1 - bBlocks && vBlocks <= v1) {
            p = texPx - border + (vBlocks - (v1 - bBlocks)) / bBlocks * border;
        } else {
            p = sliceCoord(vBlocks, texPx, border);
        }
        return sprite.z + (p / texPx) * span;
    }
    return sprite.z + (sliceCoord(vBlocks, texPx, border) / texPx) * span;
}

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
        float uBlocks = -flw_vertexTexCoord.x;
        bool isWallShell = flw_tubeFlags.w < 1.5 && flw_tubeFlags.y < 1.5;
        float u = isWallShell
            ? wallU(uBlocks, texW, borderPx, flw_tubeSprite, flw_tubeExtra.xy)
            : flw_tubeSprite.x + (sliceCoord(uBlocks, texW, borderPx) / texW) * (flw_tubeSprite.y - flw_tubeSprite.x);
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
                : flw_tubeSprite.z + (sliceCoord(vRaw, texH, borderPx) / texH) * (flw_tubeSprite.w - flw_tubeSprite.z);
        } else if (isWallShell) {
            v = wallV(vRaw, texH, borderPx, flw_tubeSprite, flw_tubeExtra.zw);
        } else {
            v = flw_tubeSprite.z + (sliceCoord(vRaw, texH, borderPx) / texH) * (flw_tubeSprite.w - flw_tubeSprite.z);
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
