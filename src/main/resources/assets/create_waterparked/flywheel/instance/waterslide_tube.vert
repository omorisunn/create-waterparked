// Unit circle cross-section, z = length (0..0.5).
// Per-vertex packed data: texCoord, color (sector/tex/border), overlay/light (sprite rect).
// 9-slice UV is rebuilt from per-instance radius/chord.

const float WALL_THICKNESS = 0.1;
const float WATER_DEPTH = 0.12;
const float WATER_BAND_RADIANS = 2.094395102;

void flw_instanceVertex(in FlwInstance i) {
    vec3 lp = flw_vertexPos.xyz;
    vec3 ln = flw_vertexNormal;

    vec2 rawLight = flw_vertexLight * 256.0;
    float spriteV0 = rawLight.x / 65535.0;
    float spriteV1 = rawLight.y / 65535.0;
    ivec2 ov = flw_vertexOverlay;
    float spriteU0 = float(ov.x) / 32767.0;
    float spriteU1 = float(ov.y) / 32767.0;
    float sectorRadians = flw_vertexColor.r * 6.28318530718;
    float texW = max(flw_vertexColor.g * 64.0, 1.0);
    float texH = max(flw_vertexColor.b * 64.0, 1.0);
    float border = flw_vertexColor.a * 16.0;
    float isWater = flw_vertexColor.g < 0.1 ? 1.0 : 0.0;
    float waterType = round(flw_vertexColor.g * 64.0);

    float t = clamp(lp.z * 2.0, 0.0, 1.0);

    vec3 chord = i.currSpine - i.prevSpine;
    float chordLen = length(chord);
    float handle = chordLen / 3.0;
    vec3 c0 = i.prevSpine;
    vec3 c1 = i.prevSpine + i.prevTangent * handle;
    vec3 c2 = i.currSpine - i.currTangent * handle;
    vec3 c3 = i.currSpine;
    float omt = 1.0 - t;
    float omt2 = omt * omt;
    float t2 = t * t;
    vec3 spine = (omt2 * omt) * c0 + (3.0 * omt2 * t) * c1 + (3.0 * omt * t2) * c2 + (t2 * t) * c3;
    vec3 derivative = (3.0 * omt2) * (c1 - c0) + (6.0 * omt * t) * (c2 - c1) + (3.0 * t2) * (c3 - c2);

    float dLenSq = dot(derivative, derivative);
    vec3 tangent;
    if (dLenSq > 1e-12) {
        tangent = derivative * inversesqrt(dLenSq);
    } else {
        float chordLenSq = dot(chord, chord);
        tangent = chordLenSq > 1e-12 ? chord * inversesqrt(chordLenSq) : vec3(0.0, 0.0, 1.0);
    }

    vec3 latLin = mix(i.prevLateral, i.currLateral, t);
    vec3 latPerp = latLin - tangent * dot(latLin, tangent);
    float latLenSq = dot(latPerp, latPerp);
    vec3 lateral;
    if (latLenSq > 1e-12) {
        lateral = latPerp * inversesqrt(latLenSq);
    } else {
        vec3 fallback = abs(tangent.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 fb = fallback - tangent * dot(fallback, tangent);
        lateral = normalize(fb);
    }
    vec3 faceUp = normalize(cross(tangent, lateral));

    float radius = max(mix(i.prevRadius, i.currRadius, t), 0.001);
    float isCap = abs(ln.z) > 0.5 ? 1.0 : 0.0;
    vec3 worldPos;
    if (isWater > 0.5) {
        float rIn = max(radius - WALL_THICKNESS, 0.001);
        float rSurf = max(rIn - WATER_DEPTH, 0.001);
        float radial = waterType > 1.5 ? mix(rIn, rSurf, flw_vertexTexCoord.x)
            : (waterType > 0.5 ? rSurf : rIn);
        worldPos = spine + lp.x * lateral * radial + lp.y * faceUp * radial;
    } else {
        float inner = dot(lp.xy, ln.xy) < 0.0 ? 1.0 : 0.0;
        float radial = max(radius - WALL_THICKNESS * inner, 0.001);
        worldPos = spine + lp.x * lateral * radial + lp.y * faceUp * radial;
    }
    flw_vertexPos = vec4(worldPos, 1.0);

    if (isWater < 0.5 && isCap > 0.5) {
        flw_vertexNormal = ln.z > 0.0 ? i.currTangent : -i.prevTangent;
    } else {
        mat3 frame = mat3(lateral, faceUp, tangent);
        flw_vertexNormal = frame * ln;
    }

    flw_vertexColor = i.color;
    flw_vertexOverlay = i.overlay;
    flw_vertexLight = vec2(i.light) / 256.0;

    if (isWater > 0.5) {
        float flowSign = chord.y < 0.0 ? 1.0 : -1.0;
        float uf = flw_vertexTexCoord.x;
        float vf = flw_vertexTexCoord.y;
        // U stays within one 16px tile across the band
        float uTex = uf;
        float vTex = i.waterVBase + vf * chordLen + i.waterFlow * flowSign;
        flw_vertexTexCoord = vec2(
            spriteU0 + uTex * (spriteU1 - spriteU0),
            spriteV0 + mod(vTex, 1.0) * (spriteV1 - spriteV0)
        );
    } else {
        // 9-slice mapping in sprite pixel space, then into the block atlas.
        float targetW = max(sectorRadians * radius * 16.0, 1.0);
        float targetH = max(mix(chordLen, 0.1, isCap) * 16.0, 1.0);
        float px = flw_vertexTexCoord.x * targetW;
        float py = flw_vertexTexCoord.y * targetH;
        float cx = max(texW - 2.0 * border, 1.0);
        float cy = max(texH - 2.0 * border, 1.0);
        float right = max(targetW - border, border);
        float top = max(targetH - border, border);
        float uf;
        if (px < border) {
            uf = px / texW;
        } else if (px >= right) {
            uf = (texW - (targetW - px)) / texW;
        } else {
            uf = (border + mod(px - border, cx)) / texW;
        }
        float vf;
        if (py < border) {
            vf = py / texH;
        } else if (py >= top) {
            vf = (texH - (targetH - py)) / texH;
        } else {
            vf = (border + mod(py - border, cy)) / texH;
        }
        flw_vertexTexCoord = vec2(spriteU0 + uf * (spriteU1 - spriteU0), spriteV0 + vf * (spriteV1 - spriteV0));
    }
}
