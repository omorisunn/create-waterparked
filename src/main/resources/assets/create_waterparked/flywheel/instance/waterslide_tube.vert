// Unit circle cross-section, z = length (0..0.5).
// Per-vertex packed data: texCoord, color (sector/tex/border), overlay/light (sprite rect).
// UV is passed as unwrapped physical pixels; the fragment shader tiles them.

out vec4 flw_tubeSprite;
out vec3 flw_tubeTex;
out vec4 flw_tubeFlags;
out vec2 flw_tubeExtra;

const float BASE_WALL = 0.1;
// keep in sync with WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK
const float WATER_V_CYCLES_PER_BLOCK = 1.0;

// arc length from 0 to v along the segment bezier
float arcLenTo(float v, vec3 c0, vec3 c1, vec3 c2, vec3 c3) {
    float sum = 0.0;
    for (int i = 0; i < 8; i++) {
        float t0 = v * float(i) / 8.0;
        float t1 = v * float(i + 1) / 8.0;
        float m0 = 1.0 - t0;
        float m1 = 1.0 - t1;
        vec3 d0 = 3.0 * m0 * m0 * (c1 - c0) + 6.0 * m0 * t0 * (c2 - c1) + 3.0 * t0 * t0 * (c3 - c2);
        vec3 d1 = 3.0 * m1 * m1 * (c1 - c0) + 6.0 * m1 * t1 * (c2 - c1) + 3.0 * t1 * t1 * (c3 - c2);
        sum += (length(d0) + length(d1)) * 0.5 * (t1 - t0);
    }
    return sum;
}

void flw_instanceVertex(in FlwInstance i) {
    vec3 lp = flw_vertexPos.xyz;
    vec3 ln = flw_vertexNormal;
    if (i.mirror < 0.0) {
        lp.x = -lp.x;
        ln.x = -ln.x;
    }

    vec2 rawLight = flw_vertexLight * 256.0;
    float spriteV0 = rawLight.x / 65535.0;
    float spriteV1 = rawLight.y / 65535.0;
    ivec2 ov = flw_vertexOverlay;
    float spriteU0 = float(ov.x) / 32767.0;
    float spriteU1 = float(ov.y & 0x7FFF) / 32767.0;
    bool isSideWall = ov.y < 0;
    float sectorRadians = flw_vertexColor.r * 6.28318530718;
    float texW = max(flw_vertexColor.g * 64.0, 1.0);
    float texH = max(flw_vertexColor.b * 64.0, 1.0);
    float isWater = flw_vertexColor.g < 0.1 ? 1.0 : 0.0;
    float borderPx = flw_vertexColor.a * 16.0;
    flw_tubeSprite = vec4(spriteU0, spriteU1, spriteV0, spriteV1);
    flw_tubeTex = vec3(texW, texH, borderPx);

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
    flw_tubeFlags = vec4(isWater, 0.0, isCap, 0.0);
    vec3 worldPos;
    if (isWater > 0.5) {
        // water envelope vertices carry their own cross-section coordinates
        worldPos = spine + lp.x * lateral * radius + lp.y * faceUp * radius;
    } else {
        float radial;
        if (isSideWall) {
            radial = mix(radius + (i.wallThickness - BASE_WALL), radius - BASE_WALL, flw_vertexTexCoord.x);
        } else {
            float inner = length(lp.xy) < 0.95 ? 1.0
                : (dot(lp.xy, ln.xy) < 0.0 ? 1.0 : 0.0);
            radial = inner > 0.5
                ? max(radius - BASE_WALL, 0.001)
                : max(radius + (i.wallThickness - BASE_WALL), 0.001);
        }
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
        float uf = flw_vertexTexCoord.x;
        float vf = flw_vertexTexCoord.y;
        // one texture tile per block along the flow
        float phase = mix(i.phaseStart, i.phaseEnd, t);
        float base = (i.arcBase + arcLenTo(vf, c0, c1, c2, c3)) * WATER_V_CYCLES_PER_BLOCK;
        float vDown = base + phase * i.flowSign;
        flw_vertexTexCoord = vec2(uf, vDown);
        flw_tubeExtra = vec2(vDown, i.downstreamMix);
    } else {
        // tile the whole material, center and border repeat
        float targetW;
        if (isSideWall) {
            targetW = max(i.wallThickness * 16.0, 1.0);
        } else {
            float inner = length(lp.xy) < 0.95 ? 1.0
                : (dot(lp.xy, ln.xy) < 0.0 ? 1.0 : 0.0);
            float texRadius = inner > 0.5
                ? radius - BASE_WALL
                : radius + (i.wallThickness - BASE_WALL);
            targetW = max(sectorRadians * texRadius * 16.0, 1.0);
        }
        float px = flw_vertexTexCoord.x * targetW;
        // undistorted V: real arc length along the curve, caps use wall thickness
        float py = isCap > 0.5
            ? flw_vertexTexCoord.y * i.wallThickness * 16.0
            : arcLenTo(flw_vertexTexCoord.y, c0, c1, c2, c3) * 16.0;
        flw_vertexTexCoord = vec2(px, py);
    }
}
