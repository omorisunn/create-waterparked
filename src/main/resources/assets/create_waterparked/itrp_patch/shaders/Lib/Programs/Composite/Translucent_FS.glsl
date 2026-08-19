

#include "/Lib/UniformDeclare.glsl"
#include "/Lib/Utilities.glsl"


uniform sampler2D shadowtex0;
uniform sampler2D shadowtex1;
uniform sampler2D shadowcolor0;


/* RENDERTARGETS: 0 */
//layout(location = 0) out vec4 framebuffer_albedo;

#ifdef SUPER_RESOLUTION
	layout(rgba16f) writeonly uniform image2D FBIMG_DIFFUSE_TEMPORAL;
#endif

layout(rgba16f) writeonly uniform image2D FBIMG_SST_TEMPORAL;
layout(r32f) writeonly uniform image2D img_prevDepth2D;


ivec2 texelCoord = ivec2(gl_FragCoord.xy);
vec2 texCoord = gl_FragCoord.xy * UNIFORM_PIXEL_SIZE;


#ifdef DIMENSION_OVERWORLD
	in vec3 worldShadowVector;
	in vec3 shadowVector;
	in vec3 worldSunVector;

	in vec3 colorShadowlight;
	in vec3 colorSunlight;
	in vec3 colorMoonlight;

	in vec3 colorSkylight;

	in vec2 fogTime;
#endif
#ifdef DIMENSION_END
	const vec3 worldShadowVector = shadowModelViewInverseEnd[2];
#endif


#include "/Lib/GbufferData.glsl"
#include "/Lib/Uniform/GbufferTransforms.glsl"
#include "/Lib/PathTracing/Voxelizer/VoxelProfile.glsl"
#include "/Lib/Uniform/ShadowTransforms.glsl"

#include "/Lib/BasicFunctions/TemporalNoise.glsl"
#ifdef DIMENSION_OVERWORLD
#include "/Lib/BasicFunctions/PrecomputedAtmosphere.glsl"
#endif
#include "/Lib/BasicFunctions/Blocklight.glsl"
#include "/Lib/BasicFunctions/HeldLight.glsl"
#ifdef DIMENSION_NETHER
	#include "/Lib/BasicFunctions/NetherColor.glsl"
#endif

#ifdef DIMENSION_OVERWORLD
	#define FULL_WATERFOG
	#include "/Lib/IndividualFunctions/WaterFog.glsl"

	#define VFOG_LQ
	#include "/Lib/IndividualFunctions/VolumetricFog.glsl"
#endif
#ifdef DIMENSION_END
	#include "/Lib/IndividualFunctions/EndSky.glsl"
#endif


#include "/Lib/PathTracing/Tracer/TracingUtilities.glsl"

#ifdef PT_IRC
	uniform sampler3D irradianceCache3D;
	uniform sampler3D irradianceCache3D_Alt;

	#include "/Lib/PathTracing/Tracer/SampleIRC.glsl"
#endif

#if defined PT_OCCLUDED_WATER_SPECULAR && defined DIMENSION_OVERWORLD
	in float shadowHighlightStrength;

	uniform sampler3D voxelData3D;
	uniform sampler3D voxelColor3D;

	#include "/Lib/PathTracing/Voxelizer/BlockShape.glsl"
	#include "/Lib/PathTracing/Tracer/ShadowTracing.glsl"

	#define OCCLUDED_WATER_TRACING
	#include "/Lib/PathTracing/Tracer/SpecularTracer.glsl"
#endif



vec2 GlassRefraction(vec3 normal, vec3 vertexNormal, vec3 viewPos, vec3 viewDir, float depth, inout float refractDepth, inout float opaqueDist, float waterDist, Material material, MaterialMask mask, out vec3 color, inout vec4 waterData){
	vec2 refractCoord = texCoord;
	vec3 refractPos = viewPos;

	if (mask.stainedGlass + mask.water > 0.5){
		
		float waterDeep = opaqueDist - waterDist;

		if (mask.stainedGlass > 0.5){
			vec3 refractDir = refract(viewDir, normal, 0.66);
			float isHand = depth * (1.0 / 0.7);
			isHand = float(saturate(isHand) == isHand);
			refractDir = refractDir * ((1.0 - isHand * 0.93) / saturate(dot(refractDir, -normal)));

			#ifdef LABPBR_REFRACTION_ROUGHNESS
				if (material.roughness > 0.0){
					#ifdef RENDERING_MODE
						uint randSeed = HashWellons32(uint(gl_FragCoord.x + screenSize.x * gl_FragCoord.y) * uint(frameCounter));
						vec2 noise = vec2(RandWellons(randSeed), RandWellons(randSeed));
					#else
						#ifdef REFRACTION_ROUGHNESS_TEMPORAL
							vec2 noise = BlueNoiseTemporal();
						#else
							vec2 noise = BlueNoise();
						#endif
					#endif
					vec2 scattering = vec2(cos(noise.x * TAU), sin(noise.x * TAU)) * noise.y;
					scattering.y *= aspectRatio;
					float scale = min(fsqrt(material.roughness) * -viewPos.z * (REFRACTION_ROUGHNESS_STRENGTH * 0.6), 0.5);
					refractDir.xy += scattering * scale;
				}
			#endif

			refractPos += refractDir * (saturate(waterDeep * (1.0 / -viewPos.z + 0.025)) * 0.125);
			
			refractCoord = (vec2(gbufferProjection0.x, gbufferProjection0.y) * refractPos.xy + gbufferProjection1.xy) / -refractPos.z * 0.5 + 0.5;

		}else{			
			vertexNormal = vertexNormal * mat3(gbufferModelViewInverse);
			vec3 normalDiff = normal - vertexNormal;

			refractPos += (viewDir + normalDiff) * saturate(waterDeep * 0.3) * 3.0;
			
			refractCoord = (vec2(gbufferProjection0.x, gbufferProjection0.y) * refractPos.xy + gbufferProjection1.xy) / -refractPos.z * 0.5 + 0.5;
		}


		#if defined SUPER_RESOLUTION || defined CUSTOM_RENDER_RESOLUTION
			float sampleDepth = textureLod(depthtexS, refractCoord * fsrRenderScale, 0.0).x;
		#else
			float sampleDepth = textureLod(depthtexS, refractCoord, 0.0).x;
		#endif

		if (sampleDepth > depth && saturate(refractCoord) == refractCoord){
			#ifdef SUPER_RESOLUTION
				vec2 sampleCoord = refractCoord * fsrRenderScale;
				color = textureLod(FBTEX_MAIN_OUTPUT, sampleCoord, 0.0).rgb;
				refractDepth = textureLod(depthtexS, sampleCoord, 0.0).x;
				
			#else
				color = textureLod(FBTEX_MAIN_OUTPUT, refractCoord, 0.0).rgb;
				#ifdef CUSTOM_RENDER_RESOLUTION
					refractDepth = textureLod(depthtexS, refractCoord * fsrRenderScale, 0.0).x;
				#else
					refractDepth = textureLod(depthtexS, refractCoord, 0.0).x;
				#endif
			#endif

			waterData = texelFetch(FBTEX_GWATER, ivec2(refractCoord * UNIFORM_SCREEN_SIZE), 0);

			vec3 viewPosOpaque = ViewPos_From_ScreenPos(refractCoord, refractDepth);
			//*
			#ifdef LOD_RENDERING
				if (refractDepth == 1.0){
					#if defined DISTANT_HORIZONS && (defined SUPER_RESOLUTION || defined CUSTOM_RENDER_RESOLUTION)
						refractDepth = textureLod(LOD_DEPTH_TEX_1, refractCoord * fsrRenderScale, 0.0).x;
					#else
						refractDepth = textureLod(LOD_DEPTH_TEX_1, refractCoord, 0.0).x;
					#endif
					viewPosOpaque = ViewPos_From_ScreenPos_LOD(refractCoord, refractDepth);
					refractDepth = -refractDepth;
				}
			#endif
			//*/
			opaqueDist = length(viewPosOpaque) + float(refractDepth == 1.0) * 1e10;
		}else{
			refractCoord = texCoord;
			
			color = texelFetch(FBTEX_MAIN_OUTPUT, texelCoord, 0).rgb;
			waterData = texelFetch(FBTEX_GWATER, texelCoord, 0);

			//#ifdef DIMENSION_NETHER
			//	depth = texelFetch(depthtexS, texelCoord, 0).x;
			//#endif

		}



	}else{
		color = texelFetch(FBTEX_MAIN_OUTPUT, texelCoord, 0).rgb;
		waterData = texelFetch(FBTEX_GWATER, texelCoord, 0);
	}

	#ifdef DIMENSION_NETHER
		if (mask.stainedGlass + mask.particle > 0.5){
			float rayDist = min(opaqueDist, far * 1.4);
			NetherFog(color, waterDist, rayDist);
		}
	#endif

	#ifdef DIMENSION_END
		if (mask.stainedGlass + mask.particle > 0.5){
			vec3 rayDir = mat3(gbufferModelViewInverse) * normalize(refractPos);
			EndFog(color, waterDist, opaqueDist, rayDir, shadowModelViewInverseEnd[2]);
		}
	#endif

	return refractCoord;
}


void MergeSpecular(inout vec3 color, vec3 viewPos, vec3 viewDir, float dist, vec3 normal, vec3 albedo, Material material, MaterialMask mask, bool totalInternalReflection){
	vec4 reflection = texelFetch(FBTEX_ALT_OUTPUT, texelCoord, 0);

	//color = reflection.rgb; return;
	
	#ifdef SPECULAR_HELDLIGHT
		vec3 f0 = vec3(material.metalness);

		if (material.metalness > 229.5 / 255.0){
			#if TEXTURE_PBR_FORMAT == 1 && defined LABPBR_PREDEFINED_METAL
				if(material.metalness < 237.5 / 255.0){
					f0 = PredefinedMetalF0(material.metalness);
				}else{
					f0 = albedo * (1.0 - METAL_MINIMAL_F0) + METAL_MINIMAL_F0;
				}				
			#else
				f0 = albedo * (1.0 - METAL_MINIMAL_F0) + METAL_MINIMAL_F0;
			#endif
		}
	#endif

	if (totalInternalReflection){
		color = reflection.rgb;
	}else{
		vec3 l = normalize(reflect(viewDir, normal) + normal * material.roughness);
		vec3 h = normalize(l - viewDir);
		float LdotH = saturate(dot(l, h));
		float NdotL = saturate(dot(normal, l));
		float NdotV = saturate(dot(normal, -viewDir));

		if (mask.stainedGlass + mask.water > 0.5){
			float specular = F_Schlick(LdotH, material.metalness, 1.0);
			specular *= V_Schlick(NdotL, NdotV + 0.8, material.roughness);
			specular *= material.reflectionStrength;

			color = mix(color, reflection.rgb, saturate(specular));
		}else if (material.metalness > 229.5 / 255.0){
			#ifndef SPECULAR_HELDLIGHT
				vec3 f0 = vec3(0.0);
				#if TEXTURE_PBR_FORMAT == 1 && defined LABPBR_PREDEFINED_METAL
					if(material.metalness < 237.5 / 255.0){
						f0 = PredefinedMetalF0(material.metalness);
					}else{
						f0 = albedo * (1.0 - METAL_MINIMAL_F0) + METAL_MINIMAL_F0;
					}				
				#else
					f0 = albedo * (1.0 - METAL_MINIMAL_F0) + METAL_MINIMAL_F0;
				#endif
			#endif

			const float diffuse = clamp(METAL_ORIGIN_COLOR / (1.0 - METALMASK_STRENGTH), 0.0, 1.0);
			vec3 specular = saturate(F_Schlick_Reflection(LdotH, f0));

			color = mix(texelFetch(FBTEX_SST_TEMPORAL, texelCoord, 0).rgb, color, diffuse);
			color += reflection.rgb * specular;
						
		}else{
			color += reflection.rgb * material.reflectionStrength;
		}
	}

	#ifdef SPECULAR_HELDLIGHT
		if (mask.hand < 0.5 && heldBlockLightValue + heldBlockLightValue2 > 0)
			color += TorchSpecularHighlight(viewPos, viewDir, dist, normal, material.roughness, f0);
	#endif
}


#ifndef DIMENSION_NETHER

vec3 ParticleShadow(vec3 worldPos, vec3 voxelPos, vec3 vertexNormal, float dist, float sunlight, float lightmap){
	worldPos += gbufferModelViewInverse[3].xyz;

	#ifdef DIMENSION_END
		vec3 shadowNormal = shadowModelViewEnd * vertexNormal;
		shadowNormal.z = -shadowNormal.z;

		vec3 shadowScreenPos = shadowModelViewEnd * worldPos;
	#else
		vec3 shadowNormal = mat3(shadowModelView0, shadowModelView1, shadowModelView2) * vertexNormal;
		shadowNormal.z = -shadowNormal.z;

		vec3 shadowScreenPos = mat3(shadowModelView0, shadowModelView1, shadowModelView2) * worldPos;
	#endif
	shadowScreenPos *= vec3(shadowProjection[0][0], shadowProjection[0][0], -shadowProjection[0][0] * 0.5);

	//float zScale = 1.0 / shadowProjection[0][0];


	#if defined TAA || defined SUPER_RESOLUTION
		vec2 noise = BlueNoiseTemporal();
	#else
		vec2 noise = BlueNoise();
	#endif

	vec3 result = vec3(1.0);


	if (sunlight > 0.0){
		shadowScreenPos += shadowNormal * max(2e-4, 1e-5 * dist);

		vec2 shadowCoord = shadowScreenPos.xy * 0.5 + 0.5;
		vec4 warp = SampleRTWWarpSmoothWithPixelSize(shadowCoord);
		vec2 warpPixelSizeMinMax = vec2(minVec2(warp.zw), maxVec2(warp.zw)) * warpPixelScale;

		shadowCoord += warp.xy;	
		if (all(bvec2(shadowCoord == clamp(shadowCoord, vec2(shadowPixelSize), vec2(1.0 - shadowPixelSize)), shadowScreenPos.z < 1.0))){
			result = vec3(0.0);

			shadowScreenPos = shadowScreenPos * 0.5 + 0.5;

			float avgDiff = 0.0;
			float vaildSampleCounts = 0.0;

			float spread = max(
				0.025 * shadowProjection[0][0],
				(SHADOW_BASIC_BLUR * 2e-6) / (warpPixelSizeMinMax.x + 2e-3)
			);

			shadowScreenPos.z -= (1.0 + noise.y * 1.0) * max(3e-5, 1e-6 / warpPixelSizeMinMax.x) * 0.1; // todo

			const float steps = float(SHADOW_QUALITY);
			const float rSteps = 1.0 / steps;

			float angle = noise.x * TAU;
			vec2 rot = vec2(cos(angle), sin(angle));
			
			for (int i = 0; i < steps; i++){
				rot *= rotMatGR;
				float radius = sqrt((float(i) + noise.y) * rSteps);
				vec2 offset = rot * (radius * spread);

				vec3 sampleCoord = vec3(shadowScreenPos.xy + offset, shadowScreenPos.z);
				sampleCoord.xy += SampleRTWWarpSmooth(sampleCoord.xy);
				//ShiftShadowScreenPos(sampleCoord.xy);

				#ifdef COLORED_SHADOWS
					float soildShadow = SampleShadowBilinear(shadowtex1, sampleCoord);

					if (soildShadow > 0.0){
						float translucentShadow = SampleShadowBilinear(shadowtex0, sampleCoord);
						result += vec3(translucentShadow);

						float coloredShadow = saturate(soildShadow - translucentShadow);
						
						if (coloredShadow > 1e-3){
							vec4 shadowColorSample = textureLod(shadowcolor0, sampleCoord.xy, 0.0);
							if (shadowColorSample.a > 0.003 && shadowColorSample.a < 1.0){ 
								shadowColorSample.rgb = AlbedoToAbsorption(GammaToLinear(shadowColorSample.rgb), shadowColorSample.a);;
								result += shadowColorSample.rgb * coloredShadow;
							}
							//else{
							//	caustics += vec4(shadowColorSample.rgb, 1.0);
							//	//result += vec3(1.0);
							//}
						}
					}
				#else
					float soildShadow = step(sampleCoord.z, textureLod(shadowtex1, sampleCoord.xy, 0.0).x);
					result += vec3(soildShadow);
				#endif
			}
			result *= rSteps;

/*
			if(caustics.a > 0.0){
				caustics.rgb /= caustics.a;

				float altitude = caustics.g * 2.0 + caustics.b * 510.0;
				altitude = max(altitude - 64.0 - cameraPosition.y - worldPos.y, 0.0);

				caustics.r = mix(0.85, caustics.r, saturate(altitude * 0.5));
				if (isEyeInWater == 0) caustics.r *= saturate(lightmap * 10.0);
				caustics.rgb = exp2(-vec3(WATER_ATTENUATION_R, WATER_ATTENUATION_G, WATER_ATTENUATION_B) * altitude) * caustics.r;
			}

			result = mix(result, caustics.rgb, caustics.a * rSteps);
*/
		}

		//if (any(greaterThan(result, vec3(0.0)))){
		//	result *= SimpleShadowTracing(voxelPos, worldShadowVector);
		//}

	}
	
	return result * sunlight;
}

#endif

void ParticleLighting(inout vec3 color, vec3 worldPos, float waterDist, vec3 albedo, float albedoAlpha, vec2 lightmap){
	vec3 particleColor = vec3(step(1.0, lightmap.r) * (BLOCKLIGHT_BRIGHTNESS * PARTICLELIGHT_BRIGHTNESS * 0.15));
	vec3 particleNormal = gbufferModelViewInverse[2].xyz;
	vec3 voxelPos = worldPos + cameraPositionFract + (voxelResolution * 0.5);


	vec3 vanillaLighting = BlockLighting_Raw(lightmap.r);
	#ifdef DIMENSION_OVERWORLD
		vanillaLighting += (colorSkylight * 0.25 + colorShadowlight * 0.02) * lightmap.g;
	#endif
	#ifdef DIMENSION_NETHER
		vanillaLighting += NetherLighting() * 3.0;
	#endif				
	#ifdef DIMENSION_END
		vanillaLighting += vec3(1.088, 0.979, 0.923) * 0.006;
	#endif

	#ifdef PT_IRC
		float fade = max(saturate(maxVec2(abs(worldPos.xz)) * 0.25 - 7.0), saturate(abs(worldPos.y) * 0.25 - 3.0));

		if (fade < 1.0){
			vec3 ircLighting = SampleIrradianceCache_Bilinear_Blocker(voxelPos) * 0.02;
			particleColor += mix(ircLighting, vanillaLighting, fade);
		}else
	#endif
		{
			particleColor += vanillaLighting;
		}

	#ifndef DIMENSION_NETHER
		float sunlight = dot(particleNormal, worldShadowVector) * 0.2 + 0.3;
		
		#ifdef DIMENSION_OVERWORLD
			sunlight *= 1.0 - wetness * RAIN_SHADOW;
			#ifdef SUNLIGHT_LEAK_FIX
				sunlight *= saturate(lightmap.g * 1e5 + float(isEyeInWater == 1));
			#endif
			particleColor += ParticleShadow(worldPos, voxelPos, particleNormal, waterDist, sunlight, lightmap.g) * colorSunlight;
		#else
			const vec3 blackbody = vec3(1.088, 0.979, 0.923) * SUNLIGHT_INTENSITY * 0.7;
			particleColor += ParticleShadow(worldPos, voxelPos, particleNormal, waterDist, sunlight, lightmap.g) * blackbody;
		#endif
	#endif

	particleColor *= albedo;
	if (isEyeInWater == 1){
		color = mix(color, particleColor, albedoAlpha * exp2(-vec3(WATER_ATTENUATION_R, WATER_ATTENUATION_G, WATER_ATTENUATION_B) * waterDist));
	}else{
		color = mix(color, particleColor, albedoAlpha);
	}
}



#ifdef SUPER_RESOLUTION
#ifdef LOD_RENDERING

	vec2 ScreenVelocity(float depth, MaterialMask mask){

		vec2 velocity = vec2(0.0);

		if (mask.endPortal < 0.5){
			vec3 projection = vec3(texCoord, abs(depth));

			if (depth < 0.0){
				#if defined PT_OCCLUDED_WATER_SPECULAR && defined DIMENSION_OVERWORLD && !defined VOXY
					if (mask.water > 0.5) projection.z = ScreenDepth_From_LinearDepth_LOD(texelFetch(waterDepth2D, texelCoord, 0).x);
				#endif

				projection = projection * 2.0 - 1.0;
				projection = (vec3(vec2(gbufferProjectionInverse0.x, gbufferProjectionInverse0.y) * projection.xy, 0.0) + lodProjectionInverse1) / (lodProjectionInverse0.x * projection.z + lodProjectionInverse0.y);

				projection = mat3(gbufferModelViewInverse) * projection + gbufferModelViewInverse[3].xyz;

				projection += cameraPositionToPrevious;

				projection = mat3(gbufferPreviousModelView) * projection + gbufferPreviousModelView[3].xyz;
				projection = (vec3(gbufferPreviousProjection0.x, gbufferPreviousProjection0.y, gbufferPreviousProjection0.z) * projection + gbufferPreviousProjection1) / -projection.z * 0.5 + 0.5;

			}else{
				#if defined PT_OCCLUDED_WATER_SPECULAR && defined DIMENSION_OVERWORLD
					if (mask.water > 0.5) projection.z = ScreenDepth_From_LinearDepth(texelFetch(waterDepth2D, texelCoord, 0).x);
				#endif

				projection = projection * 2.0 - 1.0;
				projection = (vec3(vec2(gbufferProjectionInverse0.x, gbufferProjectionInverse0.y) * projection.xy, 0.0) + gbufferProjectionInverse1) / (gbufferProjectionInverse0.z * projection.z + gbufferProjectionInverse0.w);

				if (mask.hand > 0.5){
					projection += (gbufferPreviousModelView[3].xyz - gbufferModelView[3].xyz) * MC_HAND_DEPTH;
				}else{
					projection = mat3(gbufferModelViewInverse) * projection + gbufferModelViewInverse[3].xyz;
					projection += cameraPositionToPrevious;
					projection = mat3(gbufferPreviousModelView) * projection + gbufferPreviousModelView[3].xyz;
				}
				
				projection = (vec3(gbufferPreviousProjection0.x, gbufferPreviousProjection0.y, gbufferPreviousProjection0.z) * projection + gbufferPreviousProjection1) / -projection.z * 0.5 + 0.5;
			}
			
			velocity = texCoord - projection.xy;
		}
		
		return velocity;
	}

#else

	vec2 ScreenVelocity(float depth, MaterialMask mask){
		vec2 velocity = vec2(0.0);

		if (mask.endPortal < 0.5){
			vec3 projection = vec3(texCoord, depth);
			#if defined PT_OCCLUDED_WATER_SPECULAR && defined DIMENSION_OVERWORLD
				if (mask.water > 0.5) projection.z = ScreenDepth_From_LinearDepth(texelFetch(waterDepth2D, texelCoord, 0).x);
			#endif
			
			projection = projection * 2.0 - 1.0;
			projection = (vec3(vec2(gbufferProjectionInverse0.x, gbufferProjectionInverse0.y) * projection.xy, 0.0) + gbufferProjectionInverse1) / (gbufferProjectionInverse0.z * projection.z + gbufferProjectionInverse0.w);

			if (mask.hand > 0.5){
				projection += (gbufferPreviousModelView[3].xyz - gbufferModelView[3].xyz) * MC_HAND_DEPTH;
			}else{
				projection = mat3(gbufferModelViewInverse) * projection + gbufferModelViewInverse[3].xyz;
				if (depth < 1.0) projection += cameraPositionToPrevious;
				projection = mat3(gbufferPreviousModelView) * projection + gbufferPreviousModelView[3].xyz;
			}
			
			projection = (vec3(gbufferPreviousProjection0.x, gbufferPreviousProjection0.y, gbufferPreviousProjection0.z) * projection + gbufferPreviousProjection1) / -projection.z * 0.5 + 0.5;

			velocity = texCoord - projection.xy;
		}

		return velocity;
	}

#endif
#endif


/////////////////////////MAIN//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////MAIN//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
void main(){
	float depth = texelFetch(depthtex0, texelCoord, 0).x;
	float opaqueDepth = texelFetch(depthtexS, texelCoord, 0).x;
	float refractDepth = opaqueDepth;

	bool isSmooth = false;
	GbufferData gbuffer 			= GetGbufferDataTranslucent(isSmooth);
	MaterialMask materialMask 		= CalculateMasks(gbuffer.materialID);

	vec3 viewPos 					= ViewPos_From_ScreenPos(texCoord, depth);
	vec3 viewPosOpaque 				= ViewPos_From_ScreenPos(texCoord, opaqueDepth);

	#ifdef LOD_RENDERING
		if (depth == 1.0){
			depth 					= -texelFetch(LOD_DEPTH_TEX_0, texelCoord, 0).x;
			viewPos 				= ViewPos_From_ScreenPos_LOD(texCoord, -depth);
		}

		if (opaqueDepth == 1.0){
			opaqueDepth 			= -texelFetch(LOD_DEPTH_TEX_1, texelCoord, 0).x;
			viewPosOpaque 			= ViewPos_From_ScreenPos_LOD(texCoord, -opaqueDepth);
		}

	#endif
	imageStore(img_prevDepth2D, texelCoord, vec4(opaqueDepth, 0.0, 0.0, 0.0));
	

	vec3 worldPos					= mat3(gbufferModelViewInverse) * viewPos;
	vec3 viewDir 					= normalize(viewPos.xyz);
	vec3 worldDir 					= normalize(worldPos.xyz);

	vec3 viewNormal 				= gbuffer.worldNormal * mat3(gbufferModelViewInverse);

	float waterDist 				= length(viewPos) + float(abs(depth) == 1.0) * 1e10;
	float opaqueDist 				= length(viewPosOpaque) + float(abs(opaqueDepth) == 1.0) * 1e10;


	vec4 waterData = vec4(0.0);
	vec3 color;

	vec2 refractCoord = GlassRefraction(viewNormal, gbuffer.vertexNormal, viewPos, viewDir, depth, refractDepth, opaqueDist, waterDist, gbuffer.material, materialMask, color, waterData);


	#ifdef SUPER_RESOLUTION
		#ifdef LOD_RENDERING
			float velocityReferenceDepth = materialMask.water > 0.5 || depth < 0.0 ? depth : refractDepth;
		#else
			float velocityReferenceDepth = materialMask.water > 0.5 ? depth : refractDepth;
		#endif
		vec2 velocity = ScreenVelocity(velocityReferenceDepth, materialMask);

		#if SR_ENABLE == 1
			imageStore(FBIMG_DIFFUSE_TEMPORAL, texelCoord, vec4(-velocity, 0.0, 0.0));
		#else
			imageStore(FBIMG_DIFFUSE_TEMPORAL, texelCoord, vec4(velocity, 0.0, 0.0));
		#endif
	#endif

	#ifdef LOD_RENDERING
		float referenceDepth = opaqueDepth < 0.0 ? -opaqueDepth - 1.0 : 1.0 - opaqueDepth;
	#else
		float referenceDepth = 1.0 - opaqueDepth;
	#endif



	#ifdef LOD_RENDERING
		if(waterDist > opaqueDist) waterData.w = 0.0;
	#endif
	bool totalInternalReflection = false;
	#ifdef DIMENSION_OVERWORLD
		if (isEyeInWater == 1 && materialMask.water > 0.5){
			float VdotN = dot(worldDir, gbuffer.worldNormal);
			totalInternalReflection = 1.0 - (WATER_IOR * WATER_IOR) * (1.0 - VdotN * VdotN) < 0.0;
		}
	#endif


	vec3 glassTint = vec3(1.0);
	if (materialMask.stainedGlass > 0.5){
		glassTint = AlbedoToAbsorption(gbuffer.albedo,gbuffer.albedoAlpha);

		if (isEyeInWater == 1) color *= glassTint;
	}


	if (gbuffer.material.reflectionStrength > 0.0 && isEyeInWater == 1){
		MergeSpecular(color, viewPos, viewDir, waterDist, viewNormal, gbuffer.albedo, gbuffer.material, materialMask, totalInternalReflection);
	}


	

	#ifdef DIMENSION_OVERWORLD
		if(isEyeInWater == 1 || waterData.w > 0.5){
			float k = 1.0 - (1.0 / (WATER_IOR * WATER_IOR)) * (1.0 - worldShadowVector.y * worldShadowVector.y);
			vec3 shadowVectorRefracted = -worldShadowVector * (1.0 / WATER_IOR);
			shadowVectorRefracted.y -= (1.0 / WATER_IOR) * -worldShadowVector.y + sqrt(k);
			float VdotSR = dot(shadowVectorRefracted, worldDir);

			vec2 waterDataZ = Unpack2xU8_from_U16(waterData.z);
			bool waterOccluded = (waterData.w * saturate(materialMask.stainedGlass + materialMask.particle * saturate(0.995 - gbuffer.albedoAlpha)) - float(isEyeInWater)) > 0.0;
			if (waterOccluded){
				gbuffer.worldNormal = DecodeNormal(waterData.xy);
				gbuffer.lightmap.g = SkyLightmapCurve(waterDataZ.y);
			}


			float waterDeep = 0.0;

			if (isEyeInWater == 0){
				if (waterOccluded){
					waterDeep = waterDataZ.x * 50.0;
				}else{
					waterDeep = opaqueDist - waterDist;
				}
			}else{
				if (materialMask.stainedGlass > 0.5){
					waterDeep = opaqueDist;
				}else{
					waterDeep = waterDist;
				}

			}

			//if (isDH) waterDeep = 0.0;
			
			//float waterDeep = isEyeInWater == 0 ? waterOccluded ? waterDataZ.x * 50.0 : opaqueDist - waterDist : materialMask.stainedGlass > 0.5 ? opaqueDist : waterDist;

			#ifdef WATER_FOG
				WaterFog(color, worldDir, waterDeep, gbuffer.worldNormal, VdotSR, materialMask.hand > 0.5, gbuffer.lightmap.g);
			#endif


			if (waterOccluded){
				#ifdef PT_OCCLUDED_WATER_SPECULAR
					#ifdef VOXY
						if (refractDepth >= 0.0)
					#endif
					refractDepth = ScreenDepth_From_LinearDepth(texelFetch(waterDepth2D, ivec2(refractCoord * UNIFORM_SCREEN_SIZE), 0).x);

					vec3 waterViewPos = ViewPos_From_ScreenPos(refractCoord, refractDepth);
					vec3 waterWorldPos = mat3(gbufferModelViewInverse) * waterViewPos;
					vec3 waterWorldDir = normalize(waterWorldPos);

					vec3 rayDir = reflect(waterWorldDir, gbuffer.worldNormal);
				#else
					vec3 rayDir = reflect(worldDir, gbuffer.worldNormal);
				#endif

				vec3 h = normalize(rayDir - worldDir);
				float LdotH = saturate(dot(rayDir, h));
				float NdotL = saturate(dot(gbuffer.worldNormal, rayDir));
				float NdotV = saturate(dot(gbuffer.worldNormal, -worldDir));

				float specular = F_Schlick(LdotH, 0.018, 1.0);

				#ifdef PT_OCCLUDED_WATER_SPECULAR
					vec3 voxelPos = waterWorldPos + gbufferModelViewInverse[3].xyz + cameraPositionFract + (voxelResolution * 0.5);

					#ifdef DIMENSION_OVERWORLD
						float highlightStrength = shadowHighlightStrength;
						#ifdef SUNLIGHT_LEAK_FIX
							highlightStrength *= saturate(gbuffer.lightmap.g * 1e10);
						#endif
					#else
						const float highlightStrength = 0.0;
					#endif

					vec3 reflection = SpecularTracing(voxelPos, waterWorldPos, waterWorldDir, gbuffer.worldNormal, rayDir, gbuffer.lightmap.g, highlightStrength);
				#else
					vec2 skyCoord = CubemapProjection(rayDir);
					vec3 reflection = textureLod(skyBox2D, skyCoord, 0.0).rgb;
					reflection *= saturate(gbuffer.lightmap.g * 2.5 - 1.5);
				#endif

				color = mix(color, reflection, saturate(specular));
			}
		}


		#if defined DIMENSION_OVERWORLD && ((defined LANDSCATTERING && defined LANDSCATTERING_REFRACTION) || (defined VFOG && defined VFOG_REFRACTION))
			if (materialMask.stainedGlass + materialMask.particle > 0.5 && isEyeInWater == 0){
				vec3 endViewPos = ViewPos_From_ScreenPos(refractCoord, refractDepth);
				#ifdef LOD_RENDERING
					if (refractDepth < 0.0){
						endViewPos = ViewPos_From_ScreenPos_LOD(refractCoord, -refractDepth);
					}
				#endif

				vec3 endPos = mat3(gbufferModelViewInverse) * endViewPos - worldPos;
				float rayDist = length(endPos);

				if (rayDist > 0.0){
					vec3 rayDir = endPos / rayDist;

					#ifdef LOD_RENDERING
						float farDist = max(float(LOD_RENDER_DISTANCE), far) * 1.4;
						#ifdef DH_LIMIT_VFOG_DIST
							farDist = min(2048.0, farDist);
						#endif
					#else
						float farDist = far * 1.4;
					#endif

					rayDist = max(min(rayDist + waterDist, farDist) - waterDist, 0.0);
					endPos = worldPos + rayDir * rayDist;

					#ifdef LANDSCATTERING
						#ifdef LOD_RENDERING
							LandAtmosphericScattering(color, rayDist, worldPos, endPos, rayDir, abs(refractDepth) == 1.0);
						#else
							#ifdef LANDSCATTERING_REFRACTION
								LandAtmosphericScattering(color, rayDist, worldPos, endPos, rayDir, abs(refractDepth) == 1.0);
							#endif
						#endif
					#endif
				
					float globalCloudShadow = 1.0 - wetness * (RAIN_SHADOW * 0.985);

					#ifdef VFOG
					#ifdef VFOG_REFRACTION
						float fogTimeFactor = 1.0;
						#ifndef VFOG_IGNORE_WORLDTIME
							#ifndef DISABLE_LOCAL_PRECIPITATION
								fogTimeFactor *= mix(fogTime.x, VFOG_RAIN_DENSITY_MUL, wetness * (1.0 - eyeNoPrecipitationSmooth * 0.7));
							#else
								fogTimeFactor *= mix(fogTime.x, VFOG_RAIN_DENSITY_MUL, wetness);
							#endif
						#else
							#ifndef DISABLE_LOCAL_PRECIPITATION
								fogTimeFactor *= mix(1.0, VFOG_RAIN_DENSITY_MUL, wetness * (1.0 - eyeNoPrecipitationSmooth * 0.7));
							#else
								fogTimeFactor *= mix(1.0, VFOG_RAIN_DENSITY_MUL, wetness);
							#endif					
						#endif

						if (fogTimeFactor > 0.01) VolumetricFog(color, worldPos, endPos, rayDir, globalCloudShadow, fogTimeFactor);
					#endif
					#endif
				}
			}
		#endif
	
	#else
		if(isEyeInWater == 1 || waterData.w > 0.5) color *= vec3(0.5, 0.55, 0.7);

	#endif


	if (materialMask.stainedGlass > 0.5){
		if (isEyeInWater == 0) color *= glassTint;
		color += gbuffer.albedo * (Radiance(gbuffer.albedo) * gbuffer.lightmap.r * materialMask.stainedGlass * (BLOCKLIGHT_BRIGHTNESS * 0.5));
	}


	if (gbuffer.material.reflectionStrength > 0.0 && isEyeInWater == 0){
		MergeSpecular(color, viewPos, viewDir, waterDist, viewNormal, gbuffer.albedo, gbuffer.material, materialMask, totalInternalReflection);
	}


	if (materialMask.particle > 0.5){
		ParticleLighting(color, worldPos, waterDist, gbuffer.albedo, gbuffer.albedoAlpha, gbuffer.lightmap);
	}

	//if (isSmooth) color = vec3(1.0, 0.0, 0.0);


	imageStore(FBIMG_SST_TEMPORAL, texelCoord, vec4(color, referenceDepth));
}