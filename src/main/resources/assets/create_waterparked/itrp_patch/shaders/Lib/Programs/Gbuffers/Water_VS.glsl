//Water_VS


#include "/Lib/Settings.glsl"
#include "/Lib/Utilities.glsl"


uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform vec2 taaJitter;

in vec4 mc_Entity;

out vec3 v_color;
out vec2 v_texCoord;
out vec3 v_worldPos;
out vec2 v_blockLight;
flat out float v_materialIDs;

#if defined TERRAIN_VS_TBN && !defined PROGRAM_COLORWHEEL
	in vec4 at_tangent;
	out mat3 v_tbn;
#endif


#if defined SUPER_RESOLUTION || defined CUSTOM_RENDER_RESOLUTION
	#include "/Lib/FidelityFX/FSR2/GbufferScale.glsl"
#endif


void main(){
	vec4 worldPos = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;
	v_worldPos = worldPos.xyz;
	gl_Position = gl_ProjectionMatrix * gbufferModelView * worldPos;

	#if defined SUPER_RESOLUTION || defined CUSTOM_RENDER_RESOLUTION
		#ifdef CUSTOM_RENDER_RESOLUTION
			RedoProject(gl_Position);
		#endif
		FsrScaleVS(gl_Position, taaJitter);
	#else
		#ifdef TAA
			gl_Position.xy = taaJitter * gl_Position.w + gl_Position.xy;
		#endif
	#endif

	v_color = gl_Color.rgb;
	// Waterparked: under colorwheel the transform maps gl_MultiTexCoord0 to the
	// flywheel texcoord, but our instance shader's pre-folded water uv only
	// reliably reaches the pack through flw_vertexTexCoord - the RAW tile
	// coords (0..2.09) would sample the atlas top-left (white textures).
	#ifdef PROGRAM_COLORWHEEL
		v_texCoord = mat2(gl_TextureMatrix[0]) * flw_vertexTexCoord.xy + gl_TextureMatrix[0][3].xy;
	#else
		v_texCoord = mat2(gl_TextureMatrix[0]) * gl_MultiTexCoord0.xy + gl_TextureMatrix[0][3].xy;
	#endif

	#if defined TERRAIN_VS_TBN && !defined PROGRAM_COLORWHEEL
		vec3 N = normalize(mat3(gbufferModelViewInverse) * gl_NormalMatrix * gl_Normal);
		vec3 T = normalize(mat3(gbufferModelViewInverse) * gl_NormalMatrix * at_tangent.xyz);
		vec3 B = cross(T, N) * sign(at_tangent.w);
		v_tbn = mat3(T, B, N);
	#endif

	v_blockLight = saturate(vec2(gl_MultiTexCoord1.xy - 8) / 232.0);

	if (mc_Entity.x == 12000.0){
		v_materialIDs = MATID_WATER; // Waterparked tube/stream water = vanilla water id
	}else if (mc_Entity.x == 6000.0){
		v_materialIDs = MATID_WATER;
	}else if(abs(mc_Entity.x - 8016.5) < 8.0){
		v_materialIDs = MATID_STAINEDGLASS_EMISSIVE;
	}else{
		v_materialIDs = MATID_STAINEDGLASS;
	}
}
