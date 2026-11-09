package dev.cubentia.app.spike3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.cubentia.domain.appearance.StickerAppearance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OQ-3 spike, throwaway: the cube on a [GLSurfaceView] with OpenGL ES 3.0 and nothing else. One VBO
 * holds all 27 cubies in local space (body box plus sticker quads with vertex colors); each cubie
 * is drawn with its own model matrix from [SpikeCubeModel.modelMatrix]. The VBO is rebuilt only when
 * sticker colors change (a Snap). Renders continuously and advances the queue once per frame.
 */
class GlesCubeRenderer : SpikeRenderer("gles") {
    @Composable
    override fun Content(modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(GLES_VERSION)
                    setEGLConfigChooser(8, 8, 8, 8, DEPTH_BITS, 0)
                    setRenderer(Gl())
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
        )
    }

    private inner class Gl : GLSurfaceView.Renderer {
        private var program = 0
        private var vbo = 0
        private var uViewProj = 0
        private var uModel = 0
        private var builtVersion = -1
        private val first = IntArray(SpikeCubeModel.CUBIE_COUNT)
        private val count = IntArray(SpikeCubeModel.CUBIE_COUNT)
        private val models = FloatArray(SpikeCubeModel.CUBIE_COUNT * MAT4)
        private val view = FloatArray(MAT4)
        private val projection = FloatArray(MAT4)
        private val viewProj = FloatArray(MAT4)

        override fun onSurfaceCreated(
            unused: GL10?,
            config: EGLConfig?,
        ) {
            program = link(VERTEX_SHADER, FRAGMENT_SHADER)
            uViewProj = GLES30.glGetUniformLocation(program, "uViewProj")
            uModel = GLES30.glGetUniformLocation(program, "uModel")
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            vbo = ids[0]
            builtVersion = -1
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glClearColor(BACKGROUND, BACKGROUND, BACKGROUND, 1f)
            Matrix.setLookAtM(view, 0, EYE_X, EYE_Y, EYE_Z, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        override fun onSurfaceChanged(
            unused: GL10?,
            width: Int,
            height: Int,
        ) {
            GLES30.glViewport(0, 0, width, height)
            val aspect = width.toFloat() / height.coerceAtLeast(1)
            // Keep FOV_DEGREES across the narrower side, so the cube fits in portrait too.
            val halfTan = Math.tan(Math.toRadians(FOV_DEGREES / 2.0)) / minOf(aspect, 1f)
            val fovY = Math.toDegrees(2 * Math.atan(halfTan)).toFloat()
            Matrix.perspectiveM(projection, 0, fovY, aspect, NEAR, FAR)
            Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
        }

        override fun onDrawFrame(unused: GL10?) {
            driver.onFrame(System.nanoTime())
            val vertices =
                driver.withModel { model ->
                    for (i in 0 until SpikeCubeModel.CUBIE_COUNT) model.modelMatrix(i, models, i * MAT4)
                    if (model.colorVersion != builtVersion) {
                        builtVersion = model.colorVersion
                        buildVertices(model)
                    } else {
                        null
                    }
                }
            if (vertices != null) upload(vertices)

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES
            for (attribute in 0 until ATTRIBUTES) {
                GLES30.glEnableVertexAttribArray(attribute)
                GLES30.glVertexAttribPointer(attribute, 3, GLES30.GL_FLOAT, false, stride, attribute * 3 * Float.SIZE_BYTES)
            }
            for (i in 0 until SpikeCubeModel.CUBIE_COUNT) {
                if (count[i] == 0) continue
                GLES30.glUniformMatrix4fv(uModel, 1, false, models, i * MAT4)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, first[i], count[i])
            }
        }

        private fun buildVertices(model: SpikeCubeModel): FloatBuffer {
            val data = ArrayList<Float>()
            model.cubies.forEachIndexed { index, cubie ->
                first[index] = data.size / FLOATS_PER_VERTEX
                if (cubie.stickers.isNotEmpty()) {
                    box(data, BODY_HALF, BODY_RGB)
                    for (sticker in cubie.stickers) {
                        quad(data, sticker.nx, sticker.ny, sticker.nz, rgb(StickerAppearance.of(sticker.color).fill))
                    }
                }
                count[index] = data.size / FLOATS_PER_VERTEX - first[index]
            }
            return ByteBuffer
                .allocateDirect(data.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    data.forEach { put(it) }
                    position(0)
                }
        }

        private fun upload(vertices: FloatBuffer) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.capacity() * Float.SIZE_BYTES, vertices, GLES30.GL_STATIC_DRAW)
        }
    }

    private companion object {
        const val GLES_VERSION = 3
        const val DEPTH_BITS = 16
        const val MAT4 = 16
        const val ATTRIBUTES = 3
        const val FLOATS_PER_VERTEX = 9
        const val BODY_HALF = 0.47f
        const val STICKER_HALF = 0.40f
        const val STICKER_LIFT = 0.472f
        const val BACKGROUND = 0.93f
        const val EYE_X = 4.2f
        const val EYE_Y = 4.6f
        const val EYE_Z = 6.4f
        const val FOV_DEGREES = 38f
        const val NEAR = 1f
        const val FAR = 30f
        val BODY_RGB = rgb(StickerAppearance.CUBIE_BODY)

        fun rgb(argb: Long) =
            floatArrayOf(
                ((argb shr 16) and 0xFF) / 255f,
                ((argb shr 8) and 0xFF) / 255f,
                (argb and 0xFF) / 255f,
            )

        /** Two counter-clockwise triangles of the square at distance [lift] along the normal. */
        fun face(
            out: MutableList<Float>,
            nx: Int,
            ny: Int,
            nz: Int,
            half: Float,
            lift: Float,
            color: FloatArray,
        ) {
            val n = floatArrayOf(nx.toFloat(), ny.toFloat(), nz.toFloat())
            // Two tangents u, v with u × v = n.
            val u = if (nx != 0) floatArrayOf(0f, 0f, -nx.toFloat()) else floatArrayOf(1f, 0f, 0f)
            val v = cross(n, u)

            fun corner(
                a: Float,
                b: Float,
            ) {
                for (k in 0 until 3) out += n[k] * lift + (u[k] * a + v[k] * b) * half
                for (k in 0 until 3) out += n[k]
                for (k in 0 until 3) out += color[k]
            }
            corner(-1f, -1f)
            corner(1f, -1f)
            corner(1f, 1f)
            corner(-1f, -1f)
            corner(1f, 1f)
            corner(-1f, 1f)
        }

        fun cross(
            a: FloatArray,
            b: FloatArray,
        ) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

        fun box(
            out: MutableList<Float>,
            half: Float,
            color: FloatArray,
        ) {
            for ((nx, ny, nz) in AXES) face(out, nx, ny, nz, half, half, color)
        }

        fun quad(
            out: MutableList<Float>,
            nx: Int,
            ny: Int,
            nz: Int,
            color: FloatArray,
        ) = face(out, nx, ny, nz, STICKER_HALF, STICKER_LIFT, color)

        val AXES =
            listOf(
                Triple(1, 0, 0),
                Triple(-1, 0, 0),
                Triple(0, 1, 0),
                Triple(0, -1, 0),
                Triple(0, 0, 1),
                Triple(0, 0, -1),
            )

        fun link(
            vertex: String,
            fragment: String,
        ): Int {
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, vertex))
            GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, fragment))
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "link failed: ${GLES30.glGetProgramInfoLog(program)}" }
            return program
        }

        fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
            return shader
        }

        const val VERTEX_SHADER = """#version 300 es
uniform mat4 uViewProj;
uniform mat4 uModel;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
out vec3 vColor;
void main() {
    vec3 n = normalize(mat3(uModel) * aNormal);
    float light = 0.5 + 0.5 * max(dot(n, normalize(vec3(0.4, 0.8, 0.6))), 0.0);
    vColor = aColor * light;
    gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
}
"""

        const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 outColor;
void main() {
    outColor = vec4(vColor, 1.0);
}
"""
    }
}
