//Water_FS


#include "/Lib/Settings.glsl"
#include "/Lib/Utilities.glsl"


uniform mat4 gbufferModelViewInverse;

uniform vec3 cameraPosition;
uniform int frameCounter;
uniform float frameTimeCounter;
uniform float wetness;
uniform float rainStrength;
uniform int isEyeInWater;

uniform vec4 gbufferProjectionInverse0;
uniform vec3 gbufferProjectionInverse1;

uniform float eyeSnowySmooth;
uniform float eyeNoPrecipitationSmooth;

uniform sampler2D tex;
uniform sampler2D normals;
uniform sampler2D specular;
uniform sampler2D noisetex;
uniform sampler2D depthtex1;

#ifdef PROGRAM_COLORWHEEL
	/* RENDERTARGETS: 0,3,4,5 */
	layout(location = 0) out vec4 framebuffer_albedo;
	layout(location = 1) out vec4 framebuffer_gtransData;
	layout(location = 2) out vec4 framebuffer_gtransNormal;
	layout(location = 3) out vec4 framebuffer_gwater; // Waterparked: write real water data so the composite treats us like vanilla water
#else
	/* RENDERTARGETS: 0,3,4,5 */
	layout(location = 0) out vec4 framebuffer_albedo;
	layout(location = 1) out vec4 framebuffer_gtransData;
	layout(location = 2) out vec4 framebuffer_gtransNormal;
	layout(location = 3) out vec4 framebuffer_gwater;
#endif

#if defined PT_OCCLUDED_WATER_SPECULAR && defined DIMENSION_OVERWORLD
	layout(r32ui) uniform uimage2D img_waterDepth2D;
#endif


in vec3 v_color;
in vec2 v_texCoord;
in vec3 v_worldPos;
in vec2 v_blockLight;
flat in float v_materialIDs;

#if defined TERRAIN_VS_TBN && !defined PROGRAM_COLORWHEEL
	in mat3 v_tbn;
#endif


#if defined SUPER_RESOLUTION || defined CUSTOM_RENDER_RESOLUTION
	#include "/Lib/FidelityFX/FSR2/GbufferScale.glsl"
#endif
#include "/Lib/BasicFunctions/TemporalNoise.glsl"

#include "/Lib/IndividualFunctions/WaterWaves.glsl"
#include "/Lib/IndividualFunctions/Ripple.glsl"


#ifdef SUPER_RESOLUTION
	uniform vec2 fsrPixelSize;
	uniform vec2 fsrJitter;
	#define UNIFORM_PIXEL_SIZE fsrPixelSize
	#define UNIFORM_TAA_JITTER fsrJitter
#else
	uniform vec2 pixelSize;
	uniform vec2 taaJitter;
	#define UNIFORM_PIXEL_SIZE pixelSize
	#define UNIFORM_TAA_JITTER taaJitter
#endif

vec3 ViewPos_From_ScreenPos(vec2 coord, float depth){
	#if defined TAA || defined SUPER_RESOLUTION
		coord -= UNIFORM_TAA_JITTER * 0.5;
	#endif
	vec3 ndcPos = vec3(coord, depth) * 2.0 - 1.0;
	vec3 viewPos = vec3(vec2(gbufferProjectionInverse0.x, gbufferProjectionInverse0.y) * ndcPos.xy, 0.0) + gbufferProjectionInverse1;
	return viewPos / (gbufferProjectionInverse0.z * ndcPos.z + gbufferProjectionInverse0.w);
}

float LinearDepth_From_ScreenDepth(float depth){
	depth = depth * 2.0 - 1.0;
	return 1.0 / (depth * gbufferProjectionInverse0.z + gbufferProjectionInverse0.w);
}


vec4 sampleNormals(vec2 coord, vec2 duv1, vec2 duv2){
	#ifdef TERRAIN_NORMAL_MAP
		return textureGrad(normals, coord, duv1, duv2);
	#else
		return vec4(0.5, 0.5, 0.0, 1.0);
	#endif
}

vec4 sampleSpecular(vec2 coord, vec2 duv1, vec2 duv2){
	#ifdef TERRAIN_SPECULAR_MAP
		return textureGrad(specular, coord, duv1, duv2);
	#else
		return vec4(0.0);
	#endif
}

vec4 fetchNormals(ivec2 coord){
	#ifdef ENTITIES_NORMAL_MAP
		return texelFetch(normals, coord, 0);
	#else
		return vec4(0.5, 0.5, 0.0, 1.0);
	#endif
}

vec4 fetchSpecular(ivec2 coord){
	#ifdef ENTITIES_SPECULAR_MAP
		return texelFetch(specular, coord, 0);
	#else
		return vec4(0.0);
	#endif
}


void main(){
//TBN
	vec2 duv1 = dFdx(v_texCoord);
	vec2 duv2 = dFdy(v_texCoord);
	
	#if !defined TERRAIN_VS_TBN || defined PROGRAM_COLORWHEEL
		vec3 dp1 = dFdx(v_worldPos);
		vec3 dp2 = dFdy(v_worldPos);

		vec3 N = normalize(cross(dp1, dp2));
		vec3 dp2perp = cross(dp2, N);
		vec3 dp1perp = cross(N, dp1);
		vec3 T = normalize(dp2perp * duv1.x + dp1perp * duv2.x);
		vec3 B = normalize(dp2perp * duv1.y + dp1perp * duv2.y);
		float invmax = inversesqrt(max(dot(T, T), dot(B, B)));
		mat3 tbn = mat3(T * invmax, B * invmax, N);
	#else
		mat3 tbn = v_tbn;
	#endif


	#if defined SUPER_RESOLUTION || defined CUSTOM_RENDER_RESOLUTION
		if (FsrDiscardFS(gl_FragCoord.xy)) discard;
	#endif

//albedo
	bool isWater = v_materialIDs == MATID_WATER || v_materialIDs == 5.0; // Waterparked

	vec4 albedoTex = textureGrad(tex, v_texCoord, duv1, duv2);
	float albedoAlpha = textureLod(tex, v_texCoord, 0.0).a;
	if (!isWater && albedoAlpha == 0.0) discard;
	
	#ifdef PROGRAM_COLORWHEEL
		vec4 albedo;
		vec2 blockLight;
		float ao;
		vec4 fragColor;
		clrwl_computeFragment(albedoTex, albedo, blockLight, ao, fragColor);

		albedo.rgb *= fragColor.rgb;
		blockLight = vec2(0.0, saturate(blockLight.y * 1.0666667 - 0.03333334));

	#else
		vec4 albedo = vec4(albedoTex.rgb * v_color, albedoTex.a);

		vec2 blockLight = v_blockLight;
	#endif

//wet
	vec3 mcPos = v_worldPos + cameraPosition;

	#ifdef ENABLE_ROUGH_SPECULAR

		#ifdef DIMENSION_OVERWORLD
			#ifndef DISABLE_LOCAL_PRECIPITATION
				float wet = wetness * (1.0 - eyeSnowySmooth) * (1.0 - eyeNoPrecipitationSmooth) + SURFACE_WETNESS;
			#else
				float wet = wetness + SURFACE_WETNESS;
			#endif

			wet *= step(0.9, blockLight.y);

			vec2 rainNormal = vec2(0.0);
			if (wet > 1e-7){
				GetModulatedRainSpecular(wet, mcPos, blockLight.y);
				wet = saturate(wet * 1.8);

				if (isEyeInWater == 1) tbn[2].y = -tbn[2].y;

				#ifdef RAIN_SPLASH_EFFECT
					float splashStrength = rainStrength;
					splashStrength *= saturate(tbn[2].y * 2.0 - 1.0);
					splashStrength *= exp2(-length(v_worldPos) * 0.05);

					if (splashStrength > 1e-5) rainNormal = GetRippleNormal(mcPos.xz, wet, splashStrength);

					if (isEyeInWater == 1) rainNormal = -rainNormal;
				#endif

				wet *= saturate(tbn[2].y * 0.5 + 0.5);
			}

		#else
			float wet = SURFACE_WETNESS;

			if (wet > 1e-7){
				GetModulatedRainSpecular(wet, mcPos, 1.0);
				wet = saturate(wet * 1.8);
				wet *= saturate(tbn[2].y * 0.5 + 0.5);
			}
		#endif

	#else
		float wet = 0.0;
	#endif

//normal
	float dist = 0.0;
	vec3 waterNormal = vec3(0.0, 0.0, 1.0);
	vec4 specularTex = vec4(0.0);

	if (isWater){
		float waterDist = LinearDepth_From_ScreenDepth(gl_FragCoord.z);
		#ifdef WAVE_PARALLAX
			vec3 viewVector = v_worldPos - gbufferModelViewInverse[3].xyz;
			float noise = BlueNoiseTemporal().x;

			mcPos = WaveParallax(mcPos, viewVector, tbn[2].y, noise, waterDist);
		#endif
		float NdotU = saturate(tbn[2].y + float(isEyeInWater == 1) * 2.0);
		waterNormal = WaveNormal(mcPos, NdotU * 13.0 + 5.0);

		waterNormal = tbn * waterNormal;


		dist = length(v_worldPos);

		float opaqueDist = length(ViewPos_From_ScreenPos(gl_FragCoord.xy * UNIFORM_PIXEL_SIZE, texelFetch(depthtex1, ivec2(gl_FragCoord.xy), 0).x));
		dist = opaqueDist - dist;

		#if defined PT_OCCLUDED_WATER_SPECULAR && defined DIMENSION_OVERWORLD
			if (dist > 0.0) imageAtomicMin(img_waterDepth2D, ivec2(gl_FragCoord.xy), floatBitsToUint(waterDist));
		#endif

	}else{
		#ifdef MC_NORMAL_MAP
			waterNormal = DecodeNormalTex(sampleNormals(v_texCoord, duv1, duv2).rgb);
			#ifdef ENABLE_ROUGH_SPECULAR
				waterNormal = mix(waterNormal, vec3(0.0, 0.0, 1.0), wet);
			#endif
		#endif

		waterNormal = tbn * waterNormal;

		#ifdef MC_SPECULAR_MAP
			specularTex = sampleSpecular(v_texCoord, duv1, duv2);
		#endif
	}

	#if defined ENABLE_ROUGH_SPECULAR && defined DIMENSION_OVERWORLD && defined RAIN_SPLASH_EFFECT 
		waterNormal = normalize(waterNormal + vec3(rainNormal.x, 0.0, rainNormal.y));
	#endif


//output
	vec2 normalEnc = EncodeNormal(waterNormal);
	
	vec2 albedoEnc;
	vec2 materialEnc = vec2(specularTex.r, v_materialIDs);

	if (!isWater && albedoAlpha == 1.0){
		#if defined MC_SPECULAR_MAP && TEXTURE_PBR_FORMAT == 2
			specularTex.a = specularTex.b;
		#endif

		#ifdef LABPBR_EMISSIVENESS
			#if TEXTURE_PBR_FORMAT < 2
				specularTex.a -= step(1.0, specularTex.a);
			#endif
			//#if HARDCODED_EMISSIVENESS_MODE > 0
			//	specularTex.a = max(specularTex.a, v_emissiveness);
			//#endif
		#else
			//specularTex.a = v_emissiveness;
		#endif
		
		#ifdef ENABLE_ROUGH_SPECULAR
			specularTex.r = mix(specularTex.r, 1.0, wet);
		#endif

		albedoEnc = vec2(Pack2xU8_to_U16(specularTex.rg), Pack2xU8_to_U16(specularTex.ba));
		materialEnc = vec2(1.0, MATID_SOLID_TRANS);
	}else{
		albedoEnc = vec2(Pack2xU8_to_U16(albedo.rg), Pack2xU8_to_U16(albedo.ba));
		albedo.a = 0.0; // Waterparked: same as vanilla water (was 0.1/0.45/0.75 experiments)

		if (materialEnc.y == MATID_STAINEDGLASS_EMISSIVE){
			materialEnc.y = MATID_STAINEDGLASS;
			blockLight.x = 1.0;
		}else{
			blockLight.x = 0.0;
		}
	}

	materialEnc.y /= 255.0;
	
	framebuffer_albedo = albedo;
	framebuffer_gtransData = vec4(albedoEnc, Pack2xU8_to_U16(materialEnc), Pack2xU8_to_U16(blockLight + 1e-6));
	framebuffer_gtransNormal = vec4(normalEnc, EncodeNormal(tbn[2]));
	// Waterparked: always write the water data (vanilla parity) - the composite's
	// water treatment (fog/refraction/reflection) keys on waterData.w == 1.
	framebuffer_gwater = vec4(normalEnc, Pack2xU8_to_U16(vec2(dist * 0.02, blockLight.y + 1e-6)), float(isWater));
}
