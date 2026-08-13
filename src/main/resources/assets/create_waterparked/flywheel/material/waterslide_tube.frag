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
        float u = flw_tubeSprite.x + mod(flw_vertexTexCoord.x, 1.0) * (flw_tubeSprite.y - flw_tubeSprite.x);
        float vDown = flw_tubeSprite.z + mod(flw_vertexTexCoord.y, 1.0) * (flw_tubeSprite.w - flw_tubeSprite.z);
        float vUp = flw_tubeSprite.z + mod(flw_tubeExtra.x, 1.0) * (flw_tubeSprite.w - flw_tubeSprite.z);
        vec4 up = texture(flw_diffuseTex, vec2(u, vUp));
        vec4 down = texture(flw_diffuseTex, vec2(u, vDown));
        flw_sampleColor = mix(up, down, flw_tubeExtra.y);
        flw_fragColor = flw_vertexColor * flw_sampleColor;
        return;
    } else {
        float centerW = max(texW - 2.0 * borderPx, 1.0);
        float centerH = max(texH - 2.0 * borderPx, 1.0);
        float uf = mod(borderPx + mod(flw_vertexTexCoord.x, centerW), texW) / texW;
        float vf = mod(borderPx + mod(flw_vertexTexCoord.y, centerH), texH) / texH;
        uv = vec2(
            flw_tubeSprite.x + uf * (flw_tubeSprite.y - flw_tubeSprite.x),
            flw_tubeSprite.z + vf * (flw_tubeSprite.w - flw_tubeSprite.z)
        );
    }

    flw_sampleColor = texture(flw_diffuseTex, uv);
    flw_fragColor = flw_vertexColor * flw_sampleColor;
}
