
struct Material{
	float roughness;
	float metalness;
	float emissiveness;
	float scattering;
	float reflectionStrength;
};

struct GbufferData{
	vec3 albedo;
	float albedoAlpha;
	vec3 worldNormal;
	vec3 vertexNormal;
	vec2 lightmap;
	float materialID;
	float parallaxShadow;
	//float waterMask;
	//float rainAlpha;
	Material material;
};


#define material_air 	Material(1.0, 0.0,   0.0, 0.0, 0.0);
#define material_water 	Material(0.0, 0.018, 0.0, 0.0, 1.0);
#define material_glass 	Material(0.0, 0.04,  0.0, 0.0, 1.0);

Material MaterialFromTex(vec4 specTex){
	Material material;

	#ifdef ENABLE_ROUGH_SPECULAR
		material.roughness = 1.0 - specTex.r;
		material.roughness = material.roughness * material.roughness;


		#if TEXTURE_PBR_FORMAT < 2
			material.metalness = specTex.g;
		#else
			#if ROUGHNESS_CLAMP == 2
				material.metalness = saturate(specTex.g * 1.1 - 0.1);
			#else
				material.metalness = specTex.g;
			#endif
		#endif
		

		#if ROUGHNESS_CLAMP == 2
			material.reflectionStrength = curveTop(saturate(specTex.r * 2.5 - 0.85));
		#elif ROUGHNESS_CLAMP == 1
			material.reflectionStrength = saturate(pow(specTex.r, 0.25));
		#else
			material.reflectionStrength = float(specTex.r > 0.0);
		#endif


		#if TEXTURE_PBR_FORMAT < 2
			material.reflectionStrength = saturate(material.reflectionStrength + step(229.5 / 255.0, material.metalness) * 1e10);
		#else
			material.reflectionStrength = saturate(material.reflectionStrength + material.metalness);
		#endif


		material.metalness = max(material.metalness, 0.04);


	#else
		material = material_air;
	#endif


	material.emissiveness = specTex.a;


	#if TEXTURE_PBR_FORMAT < 2
		material.scattering = saturate((specTex.z * 255.0 - 64.0) / 191.0) * SSS_STRENGTH + SSS_STRENGTH_OFFSET;
	#else
		material.scattering = 0.0;
	#endif


	return material;
}

vec3 PredefinedMetalF0(float index){
	vec3 f0 = vec3(0.0);

	if (abs(index - 230.0 / 255.0) < 2e-4){
		f0 = vec3(0.56, 0.58, 0.58); // Iron
	}else if (abs(index - 231.0 / 255.0) < 2e-4){
		f0 = vec3(1.00, 0.69, 0.28); // Gold
	}else if (abs(index - 232.0 / 255.0) < 2e-4){
		f0 = vec3(0.81, 0.82, 0.83); // Aluminum
	}else if (abs(index - 233.0 / 255.0) < 2e-4){
		f0 = vec3(0.50, 0.49, 0.49); // Chromium
	}else if (abs(index - 234.0 / 255.0) < 2e-4){
		f0 = vec3(0.95, 0.52, 0.35); // Copper
	}else if (abs(index - 235.0 / 255.0) < 2e-4){
		f0 = vec3(0.81, 0.83, 0.87); // Lead
	}else if (abs(index - 236.0 / 255.0) < 2e-4){
		f0 = vec3(0.66, 0.63, 0.58); // Platinum
	}else if (abs(index - 237.0 / 255.0) < 2e-4){
		f0 = vec3(0.95, 0.91, 0.81); // Sliver
	}

	return f0;
}


GbufferData GetGbufferDataSoild(){
	GbufferData data;

	vec4 gbuffer5 = texelFetch(FBTEX_GSOLID_DATA, texelCoord, 0);
	vec4 gbuffer6 = texelFetch(FBTEX_GSOLID_NORMAL, texelCoord, 0);

	data.albedo 		= GammaToLinear(texelFetch(FBTEX_ALBEDO, texelCoord, 0).rgb);
	data.albedoAlpha 	= 1.0;
	data.worldNormal 	= DecodeNormal(gbuffer6.xy);
	data.vertexNormal 	= DecodeNormal(gbuffer6.zw);
	data.lightmap 		= Unpack2xU8_from_U16(gbuffer5.w);
	#ifdef DIMENSION_END
		data.lightmap 	= vec2(data.lightmap.r, 1.0);
	#else
		data.lightmap 	= vec2(data.lightmap.r, SkyLightmapCurve(data.lightmap.g));
	#endif
	vec2 gbuffer5z 		= Unpack2xU8_ID_from_U16(gbuffer5.z);
	data.materialID 	= gbuffer5z.y;
	data.parallaxShadow = gbuffer5z.x;

	vec4 specTex = vec4(Unpack2xU8_from_U16(gbuffer5.x), Unpack2xU8_from_U16(gbuffer5.y));
	data.material = MaterialFromTex(specTex);

	return data;
}


GbufferData GetGbufferDataTranslucent(out bool isSmooth){
	GbufferData data;

	vec4 gbuffer5 = texelFetch(FBTEX_GTRANS_DATA, texelCoord, 0);
	vec4 gbuffer6 = texelFetch(FBTEX_GTRANS_NORMAL, texelCoord, 0);

	data.worldNormal 	= DecodeNormal(gbuffer6.xy);
	data.vertexNormal 	= DecodeNormal(gbuffer6.zw);
	data.lightmap 		= Unpack2xU8_from_U16(gbuffer5.w);
	#ifdef DIMENSION_END
		data.lightmap 	= vec2(data.lightmap.r, 1.0);
	#else
		data.lightmap 	= vec2(data.lightmap.r, SkyLightmapCurve(data.lightmap.g));
	#endif
	vec2 gbuffer5z 		= Unpack2xU8_ID_from_U16(gbuffer5.z);
	data.materialID 	= gbuffer5z.y;
	data.parallaxShadow = 1.0;

	vec4 specTex = vec4(Unpack2xU8_from_U16(gbuffer5.x), Unpack2xU8_from_U16(gbuffer5.y));

	isSmooth = false;

	if (data.materialID == MATID_WATER){
		data.albedo = GammaToLinear(specTex.rgb);
		data.albedoAlpha = specTex.a;
		data.material = material_water;
		isSmooth = true;

	}else if (data.materialID == MATID_STAINEDGLASS){
		data.albedo = GammaToLinear(specTex.rgb);
		data.albedoAlpha = specTex.a;
		data.material = material_glass;
		#ifdef ENABLE_ROUGH_SPECULAR
			data.material.roughness = gbuffer5z.x > 0.0 ? 1.0 - gbuffer5z.x : 0.0;
			data.material.roughness = data.material.roughness * data.material.roughness;
			isSmooth = data.material.roughness < 2e-5;
		#else
			isSmooth = true;
		#endif

	}else if (abs(data.materialID - (MATID_PARTICLE + 0.5)) < 1.0){
		data.albedo = GammaToLinear(specTex.rgb);
		data.albedoAlpha = specTex.a;
		data.material = material_air;

	}else{
		data.albedo = GammaToLinear(texelFetch(FBTEX_ALBEDO, texelCoord, 0).rgb);
		data.albedoAlpha = 1.0;
		data.material = MaterialFromTex(specTex);
		#ifdef ENABLE_ROUGH_SPECULAR
			//isSmooth = data.material.roughness < 1e-5;
		#endif
	}
	#ifdef DECREASE_HAND_SPECULAR
	#endif

	#ifdef DISABLE_HAND_SPECULAR
		if (data.materialID == MATID_HAND) data.material = material_air;
	#elif defined DECREASE_HAND_SPECULAR
		if (data.materialID == MATID_HAND){
			data.material.metalness *= saturate(1.0 - data.material.roughness * 3.0);
			//data.material.reflectionStrength = saturate(data.material.reflectionStrength + step(229.5 / 255.0, data.material.metalness) * 1e10);
			data.material.reflectionStrength = min(data.material.reflectionStrength, 0.5);
		}

	#endif

	return data;
}

float GetSolidMaterialID(ivec2 coord){
	return Unpack2xU8_ID_Y_from_U16(texelFetch(FBTEX_GSOLID_DATA, coord, 0).z);
}

float GetTransMaterialID(ivec2 coord){
	return Unpack2xU8_ID_Y_from_U16(texelFetch(FBTEX_GTRANS_DATA, coord, 0).z);
}


struct MaterialMask{
	float sky;
	float grass;
	float leaves;
	float hand;
	float entityPlayer;
	float water;
	float stainedGlass;

	float lightning;
	float entitiesSnow;

	float endrod;
	float fire;
	float torch;
	float lightSource;
	float redstoneTorch;
	//float soulFire;
	float soulTorch;
	float amethyst;

	float particle;
	//float particlelit;

	float endPortal;

	float selection;
};

MaterialMask CalculateMasks(float materialIDs){
	MaterialMask mask;

	mask.sky				= float(materialIDs == MATID_SKY);
	mask.grass				= float(materialIDs == MATID_GRASS || materialIDs == MATID_BEACON_BEAM);
	mask.leaves				= float(materialIDs == MATID_LEAVES);
	mask.hand				= float(materialIDs == MATID_HAND);

	mask.water				= float(materialIDs == MATID_WATER);
	mask.stainedGlass		= float(materialIDs == MATID_STAINEDGLASS);

	mask.entityPlayer		= float(materialIDs == MATID_ENTITIES_PLAYER);
	mask.entitiesSnow		= float(materialIDs == MATID_ENTITIES_SNOW || materialIDs == MATID_BEACON_BEAM);
	mask.lightning			= float(materialIDs == MATID_LIGHTNING);

	mask.endrod				= float(materialIDs == MATID_ENDROD);
	mask.fire				= float(materialIDs == MATID_FIRE);
	mask.torch				= float(materialIDs == MATID_TORCH);
	mask.redstoneTorch		= float(materialIDs == MATID_REDSTONE_TORCH);
	mask.amethyst			= float(materialIDs == MATID_AMETHYST);
	mask.soulTorch			= float(materialIDs == MATID_SOULTORCH || materialIDs == MATID_COPPER_LANTERN);

	mask.particle			= float(materialIDs == MATID_PARTICLE);
	//mask.particlelit		= float(materialIDs == MATID_PARTICLE_LIT);

	mask.endPortal			= float(materialIDs == MATID_END_PORTAL);

	mask.selection			= float(materialIDs == MATID_SELECTION);

	return mask;
}
