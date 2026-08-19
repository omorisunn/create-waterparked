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

// low-precision fast 3D value noise (hash corners + smoothstep trilinear)
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

// 3-octave fractal sum for small-scale turbulent detail
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

    // sprite rect and flags ride the INSTANCE buffer (ColoredLitOverlay's
    // instance fields), never the mesh attributes: Colorwheel forwards the raw
    // mesh vertex attributes verbatim to the shaderpack, so anything packed
    // there (sprite rect, border) would leak into pack material/alpha/light.
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
        // water envelope vertices carry their own cross-section coordinates;
        // add speed- and wall-proximity-scaled turbulence, clamped to the inner wall
        vec3 radial = normalize(lp.x * lateral + lp.y * faceUp);
        vec3 tangential = cross(tangent, radial);
        float r0 = length(lp.xy) * radius;
        vec3 worldBase = spine + radial * r0;
        float speedT = mix(i.flowStart, i.flowEnd, t);
        float flowJitter = clamp(speedT, 0.0, 1.0);
        float radialOff = 0.0;
        float tangOff = 0.0;
        // iterationRP passes jitterScale = 0 for its 10x water meshes: skip the
        // whole FBM here instead of computing noise that multiplies to zero.
        if (i.jitterScale > 0.0001) {
            float timePhase = i.jitterTime * i.jitterTimeScale * speedT;
            // noise keyed on spine (bit-identical at shared segment boundaries) + the
            // cross-section angle, so adjacent segments jitter identically at their seam.
            // cos(2*ang) is invariant under the ang -> PI - ang remapping caused by a
            // reversed frame (negated tangent/lateral) or a joined curve, so forward
            // and backward segments share the same noise key at a boundary ring.
            float ang = atan(lp.y, lp.x);
            float angKey = cos(2.0 * ang) * 2.0;
            // the tangential basis flips sign under the same remapping; cos(ang)
            // carries exactly that sign, so mirror the tangential jitter back
            float tangSign = clamp(cos(ang) / 0.85, -1.0, 1.0);
            vec3 np = vec3(spine.x * i.jitterFrequency, spine.y * i.jitterFrequency + angKey, spine.z * i.jitterFrequency);
            float nRadial = jitterFbm(np + vec3(0.0, 0.0, timePhase));
            float nTang = jitterFbm(np + vec3(5.2, 1.3, timePhase * 1.3)) * tangSign;
            // keep the geometric amplitude small and comparable to conventional
            // vertex-wave calculations: normalized flow, boundary falloff, a 0.25
            // wave scale, and a hard cap so a fast segment can never spike out.
            float amp = min(flowJitter * boundaryFactor * i.jitterScale * 0.25, 0.06);
            radialOff = (nRadial * 2.0 - 1.0) * amp;
            tangOff = (nTang * 2.0 - 1.0) * amp * 0.6;
        }
        float maxOut = max(radius - i.wallThickness - r0, 0.0);
        radialOff = clamp(radialOff, -r0, maxOut);
        worldPos = worldBase + radial * radialOff + tangential * tangOff;
    } else {
        // One rule for the tube wall AND side walls: inner/outer comes from
        // lp.xy length (side-wall verts sit at 0.92/1.0) and normal orientation.
        // The old negative-u side-wall channel was mix()ed as a radial fraction
        // and extruded the side wall ~1 block outside the tube at OPEN sector
        // boundaries (the wrong wall width between sectors).
        float radial;
        float inner = length(lp.xy) < 0.95 ? 1.0
            : (dot(lp.xy, ln.xy) < 0.0 ? 1.0 : 0.0);
        radial = inner > 0.5
            ? max(radius - BASE_WALL, 0.001)
            : max(radius + (i.wallThickness - BASE_WALL), 0.001);
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
    if (isWater > 0.5 && i.tailFadeEnd > i.tailFadeStart + 0.0001) {
        // smooth per-vertex tail fade along the thrown stream; stream segments
        // use a fixed 0.5 arc step, so arcBase + t*0.5 is the stream coordinate
        float streamArc = i.arcBase + t * 0.5;
        float tailFade = 1.0 - smoothstep(i.tailFadeStart, i.tailFadeEnd, streamArc);
        flw_vertexColor.a *= tailFade;
    }
    flw_vertexOverlay = i.overlay;
    flw_vertexLight = vec2(i.light) / 256.0;

    if (isWater > 0.5) {
        float uf = flw_vertexTexCoord.x;
        float vf = flw_vertexTexCoord.y;
        // one texture tile per block along the flow
        float phase = mix(i.phaseStart, i.phaseEnd, t);
        float base = (i.arcBase + arcLenTo(vf, c0, c1, c2, c3)) * WATER_V_CYCLES_PER_BLOCK;
        float vDown = base + phase * i.flowSign;
        // stretch the tile repeat to `waterTileSpan` blocks (>=1); shaderpack
        // water materials/normals sample this vertex UV, so a larger span melts
        // the per-block striping without touching the non-shader look (span=1)
        float span = max(i.waterTileSpan, 1.0);
        float vSpan = vDown / span;
        if (i.waterAtlasUV > 0.5) {
            // atlas-sampling pack (iterationRP): gbuffers_water samples tex
            // (the block atlas) directly with the vertex uv, so export the
            // coordinate folded into the water_still sprite rect - tile
            // coordinates (0..2.09, v = arc length) would sample arbitrary
            // atlas regions and the water looks like loud garbage texture.
            // (Only set while colorwheel routes our meshes, so the plain
            // fragment path below never sees these pre-folded coords.)
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
        // Wall/cap/side-wall uv is already atlas-space (sprite rect baked by
        // the mesh builder for the Colorwheel/pack path, which samples
        // texture() with this vertex uv directly). Nothing to do — border
        // pixels are part of the sprite.
    }
}
