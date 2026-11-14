/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * Ported from packages/core/src/bg-render/mesh-renderer: shaders and offscreen blending.
 */

package yos.music.player.ui.widgets.effects.mesh

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

// The vertex colors of AMLL are always white, so the shader leaves them out
internal const val MESH_VERTEX_SHADER = """
attribute vec2 a_pos;
attribute vec2 a_uv;
varying vec2 v_uv;

uniform float u_aspect;

void main() {
    v_uv = a_uv;
    vec2 pos = a_pos;
    if (u_aspect > 1.0) {
        pos.y *= u_aspect;
    } else {
        pos.x /= u_aspect;
    }
    gl_Position = vec4(pos, 0.0, 1.0);
}
"""

internal const val MESH_FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_volume;
uniform float u_alpha;
uniform float u_sinAngle;
uniform float u_cosAngle;
uniform float u_vignette;
uniform float u_grain;

const float INV_255 = 1.0 / 255.0;
const float HALF_INV_255 = 0.5 / 255.0;
const float GRADIENT_NOISE_A = 52.9829189;
const vec2 GRADIENT_NOISE_B = vec2(0.06711056, 0.00583715);

float gradientNoise(in vec2 uv) {
    return fract(GRADIENT_NOISE_A * fract(dot(uv, GRADIENT_NOISE_B)));
}

void main() {
    float volumeEffect = u_volume * 2.0;

    float dither = (INV_255 * gradientNoise(gl_FragCoord.xy) - HALF_INV_255) * u_grain;

    vec2 centeredUV = v_uv - vec2(0.2);

    vec2 rotatedUV = vec2(
        u_cosAngle * centeredUV.x - u_sinAngle * centeredUV.y,
        u_sinAngle * centeredUV.x + u_cosAngle * centeredUV.y
    );

    vec2 finalUV = rotatedUV * max(0.001, 1.0 - volumeEffect) + vec2(0.5);

    vec4 result = texture2D(u_texture, finalUV);

    float alphaVolumeFactor = u_alpha * max(0.5, 1.0 - u_volume * 0.5);
    result.rgb *= alphaVolumeFactor;
    result.a *= alphaVolumeFactor;

    result.rgb += vec3(dither);

    float dist = distance(v_uv, vec2(0.5));
    float vignette = smoothstep(0.8, 0.3, dist);
    float mask = 1.0 - (1.0 - vignette) * 0.4 * u_vignette;
    result.rgb *= mask;

    gl_FragColor = result;
}
"""

internal const val QUAD_VERTEX_SHADER = """
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
    gl_Position = vec4(a_pos, 0.0, 1.0);
    v_uv = a_pos * 0.5 + 0.5;
}
"""

internal const val QUAD_FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_alpha;
void main() {
    vec4 color = texture2D(u_texture, v_uv);
    gl_FragColor = vec4(color.rgb, color.a * u_alpha);
}
"""

/**
 * Draws the mesh gradient with OpenGL ES 2.0. Every method must run on the thread that owns the GL context.
 * Each cover is drawn into an offscreen texture, then blended onto the screen with the alpha of its fade.
 */
internal class MeshGradientRenderer : MeshBackend<MeshGradientRenderer.GlState>() {
    class GlState(val vertexBuffer: Int, val indexBuffer: Int, val indexCount: Int, val texture: Int)

    private var meshProgram = 0
    private var quadProgram = 0
    private var meshPos = 0
    private var meshUv = 0
    private var quadPos = 0
    private var quadBuffer = 0
    private var framebuffer = 0
    private var framebufferTexture = 0
    private var width = 0
    private var height = 0

    fun onSurfaceCreated() {
        meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        quadProgram = createProgram(QUAD_VERTEX_SHADER, QUAD_FRAGMENT_SHADER)
        meshPos = GLES20.glGetAttribLocation(meshProgram, "a_pos")
        meshUv = GLES20.glGetAttribLocation(meshProgram, "a_uv")
        quadPos = GLES20.glGetAttribLocation(quadProgram, "a_pos")
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)
        quadBuffer = generateBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, floatBuffer(quad), GLES20.GL_STATIC_DRAW)
    }

    fun onSizeChanged(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (framebufferTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(framebufferTexture), 0)
        framebuffer = 0
        framebufferTexture = 0
        if (width <= 0 || height <= 0) return
        framebufferTexture = generateTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        setTextureParameters(GLES20.GL_CLAMP_TO_EDGE)
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, framebufferTexture, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun canPaint() = framebuffer != 0

    override fun createState(pixels: IntArray, geometry: MeshGeometry): GlState {
        val vertexBuffer = generateBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, geometry.vertices.size * 4, floatBuffer(geometry.vertices), GLES20.GL_STATIC_DRAW
        )
        val indexBuffer = generateBuffer()
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        val indices = ByteBuffer.allocateDirect(geometry.indices.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().put(geometry.indices)
        indices.position(0)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, geometry.indices.size * 2, indices, GLES20.GL_STATIC_DRAW)

        val texture = generateTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        val rgba = ByteBuffer.allocateDirect(pixels.size * 4)
        for (c in pixels) {
            rgba.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
        }
        rgba.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, CoverTexture.SIZE, CoverTexture.SIZE, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba
        )
        setTextureParameters(GLES20.GL_MIRRORED_REPEAT)
        return GlState(vertexBuffer, indexBuffer, geometry.indices.size, texture)
    }

    override fun destroyState(state: GlState) {
        GLES20.glDeleteBuffers(2, intArrayOf(state.vertexBuffer, state.indexBuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(state.texture), 0)
    }

    override fun paint(entries: List<MeshAnimator.Entry<GlState>>, angle: Float, volume: Float) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        for (entry in entries) {
            val state = entry.state
            // 1. The mesh goes to the offscreen texture, without blending
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(meshProgram)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "u_aspect"), width.toFloat() / height)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgram, "u_texture"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "u_volume"), volume)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "u_alpha"), 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "u_sinAngle"), sin(angle))
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "u_cosAngle"), cos(angle))
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "u_vignette"), settings.vignette)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "u_grain"), settings.grain)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, state.texture)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, state.vertexBuffer)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, state.indexBuffer)
            GLES20.glEnableVertexAttribArray(meshPos)
            GLES20.glVertexAttribPointer(meshPos, 2, GLES20.GL_FLOAT, false, 16, 0)
            GLES20.glEnableVertexAttribArray(meshUv)
            GLES20.glVertexAttribPointer(meshUv, 2, GLES20.GL_FLOAT, false, 16, 8)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, state.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
            GLES20.glDisableVertexAttribArray(meshPos)
            GLES20.glDisableVertexAttribArray(meshUv)

            // 2. The offscreen texture goes to the screen, faded by the state of this cover
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(
                GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA
            )
            GLES20.glUseProgram(quadProgram)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(quadProgram, "u_texture"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "u_alpha"), MeshAnimator.ease(entry.alpha))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTexture)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
            GLES20.glEnableVertexAttribArray(quadPos)
            GLES20.glVertexAttribPointer(quadPos, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(quadPos)
        }
        GLES20.glFlush()
    }

    override fun releaseGpu() {
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (framebufferTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(framebufferTexture), 0)
        if (quadBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(quadBuffer), 0)
        if (meshProgram != 0) GLES20.glDeleteProgram(meshProgram)
        if (quadProgram != 0) GLES20.glDeleteProgram(quadProgram)
        framebuffer = 0; framebufferTexture = 0; quadBuffer = 0; meshProgram = 0; quadProgram = 0
        width = 0; height = 0
    }

    private fun generateBuffer(): Int = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]

    private fun generateTexture(): Int = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]

    private fun setTextureParameters(wrap: Int) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
    }

    private fun floatBuffer(data: FloatArray) =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Mesh gradient program did not link: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Log.e("MeshGradient", "Shader did not compile: $log")
            throw RuntimeException("Mesh gradient shader did not compile: $log")
        }
        return shader
    }
}
