out vec4 flw_tubeSprite;
out vec3 flw_tubeTex;
out vec4 flw_tubeFlags;
out vec2 flw_tubeExtra;

const float BASE_WALL = 0.1;
// keep in sync with WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK
const float WATER_V_CYCLES_PER_BLOCK = 1.0;

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

float jitterHash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float jitterNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = jitterHash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = jitterHash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = jitterHash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = jitterHash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = jitterHash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = jitterHash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = jitterHash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = jitterHash13(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    float nxy0 = mix(nx00, nx10, f.y);
    float nxy1 = mix(nx01, nx11, f.y);
    return mix(nxy0, nxy1, f.z);
}

float jitterFbm(vec3 p) {
    return jitterNoise3(p) * 0.5
        + jitterNoise3(p * 2.13 + 17.7) * 0.3
        + jitterNoise3(p * 4.29 + 31.1) * 0.2;
}

void flw_instanceVertex(in FlwInstance i) {
    vec3 lp = flw_vertexPos.xyz;
    vec3 ln = flw_vertexNormal;
    if (i.mirror < 0.0) {
        lp.x = -lp.x;
        ln.x = -ln.x;
    }

    float spriteU0 = i.spriteU0;
    float spriteU1 = i.spriteU1;
    float spriteV0 = i.spriteV0;
    float spriteV1 = i.spriteV1;
    float isWater = i.isWater;
    // block sprites are 16px; border pixels fixed at the default (2)
    float texW = 16.0;
    float texH = 16.0;
    float borderPx = 2.0;
    float boundaryFactor = 1.0;
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
    flw_tubeFlags = vec4(isWater, boundaryFactor, isCap, i.waterTileSpan);
    vec3 worldPos;
    if (isWater > 0.5) {
        vec3 radial = normalize(lp.x * lateral + lp.y * faceUp);
        vec3 tangential = cross(tangent, radial);
        float r0 = length(lp.xy) * radius;
        vec3 worldBase = spine + radial * r0;
        float speedT = mix(i.flowStart, i.flowEnd, t);
        float flowJitter = clamp(speedT, 0.0, 1.0);
        float radialOff = 0.0;
        float tangOff = 0.0;
        if (i.jitterScale > 0.0001) {
            float timePhase = i.jitterTime * i.jitterTimeScale * speedT;
            float ang = atan(lp.y, lp.x);
            float angKey = cos(2.0 * ang) * 2.0;
            float tangSign = clamp(cos(ang) / 0.85, -1.0, 1.0);
            vec3 np = vec3(spine.x * i.jitterFrequency, spine.y * i.jitterFrequency + angKey, spine.z * i.jitterFrequency);
            float nRadial = jitterFbm(np + vec3(0.0, 0.0, timePhase));
            float nTang = jitterFbm(np + vec3(5.2, 1.3, timePhase * 1.3)) * tangSign;
            float amp = min(flowJitter * boundaryFactor * i.jitterScale * 0.25, 0.06);
            radialOff = (nRadial * 2.0 - 1.0) * amp;
            tangOff = (nTang * 2.0 - 1.0) * amp * 0.6;
        }
        float maxOut = max(radius - i.wallThickness - r0, 0.0);
        radialOff = clamp(radialOff, -r0, maxOut);
        worldPos = worldBase + radial * radialOff + tangential * tangOff;
    } else {
        float radial;
        if (isCap < 0.5 && abs(ln.z) > 0.2) {
            radial = max(radius, 0.001);
        } else if (dot(lp.xy, ln.xy) < 0.0) {
            radial = max(radius - BASE_WALL, 0.001);
        } else {
            radial = max(radius + (i.wallThickness - BASE_WALL), 0.001);
        }
        worldPos = spine + lp.x * lateral * radial + lp.y * faceUp * radial;
        if (i.waterTileSpan > 1.5) {
            float arc = i.arcBase + arcLenTo(t, c0, c1, c2, c3);
            float total = max(i.downstreamMix, 0.1);
            float gTexH = clamp(round((spriteV1 - spriteV0) * 1024.0), 1.0, 64.0);
            float vPx = -1.0;
            if (arc < borderPx / 16.0) {
                vPx = max(arc * 16.0, 0.05);
            } else if (arc > total - borderPx / 16.0) {
                vPx = min(gTexH - borderPx + (arc - (total - borderPx / 16.0)) * 16.0, gTexH - 0.05);
            }
            if (vPx >= 0.0) {
                flw_vertexTexCoord = vec2(
                    flw_vertexTexCoord.x,
                    spriteV0 + (vPx / gTexH) * (spriteV1 - spriteV0)
                );
            }
        }
    }
    flw_vertexPos = vec4(worldPos, 1.0);

    if (isWater < 0.5 && isCap > 0.5) {
        flw_vertexNormal = ln.z > 0.0 ? i.currTangent : -i.prevTangent;
    } else {
        mat3 frame = mat3(lateral, faceUp, tangent);
        // the side-wall inner mark rides in normal-z; the lighting normal stays planar
        vec3 nrm = (abs(ln.z) > 0.2 && isCap < 0.5)
            ? vec3(normalize(ln.xy), 0.0)
            : ln;
        flw_vertexNormal = frame * nrm;
    }

    flw_vertexColor = i.color;
    if (isWater > 0.5 && i.tailFadeEnd > i.tailFadeStart + 0.0001) {
        float streamArc = i.arcBase + t * 0.5;
        float tailFade = 1.0 - smoothstep(i.tailFadeStart, i.tailFadeEnd, streamArc);
        flw_vertexColor.a *= tailFade;
    }
    flw_vertexOverlay = i.overlay;
    flw_vertexLight = vec2(i.light) / 256.0;

    if (isWater > 0.5) {
        float uf = flw_vertexTexCoord.x;
        float vf = flw_vertexTexCoord.y;
        float phase = mix(i.phaseStart, i.phaseEnd, t);
        float base = (i.arcBase + arcLenTo(vf, c0, c1, c2, c3)) * WATER_V_CYCLES_PER_BLOCK;
        float vDown = base + phase * i.flowSign;
        float span = max(i.waterTileSpan, 1.0);
        float vSpan = vDown / span;
        if (i.waterAtlasUV > 0.5) {
            flw_vertexTexCoord = vec2(
                spriteU0 + mod(uf, 1.0) * (spriteU1 - spriteU0),
                spriteV0 + mod(vSpan, 1.0) * (spriteV1 - spriteV0)
            );
            flw_tubeExtra = vec2(
                spriteV0 + mod(vSpan, 1.0) * (spriteV1 - spriteV0), i.downstreamMix
            );
        } else {
            flw_vertexTexCoord = vec2(uf, vSpan);
            flw_tubeExtra = vec2(vSpan, i.downstreamMix);
        }
    } else {
    }
}
