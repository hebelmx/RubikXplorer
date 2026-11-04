package dev.rubikxplorer.app.render.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import dev.rubikxplorer.domain.input.ViewPose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GL thread consumes immutable snapshots; it never advances presentation. */
data class GlSnapshot(
    val models: FloatArray,
    val vertices: FloatArray,
    val counts: IntArray,
    val meshVersion: Int,
)

class CubeGlRenderer(
    private val atlas: android.graphics.Bitmap,
    private val vertexShader: String,
    private val fragmentShader: String,
) : GLSurfaceView.Renderer {
    @Volatile var eye = ViewPose.DEFAULT_EYE
    private var drawnEye: dev.rubikxplorer.domain.input.Vector3? = null
    private var width = 1
    private var height = 1

    @Volatile var snapshot: GlSnapshot? = null

    /** The stage color (ARGB) the cube is drawn on, so the GL surface blends into the themed stage. */
    @Volatile var background: Long = DEFAULT_BACKGROUND
    val drawnFrames =
        java.util.concurrent.atomic
            .AtomicLong()
    private var texture = 0
    private var program = 0
    private var vbo = 0
    private var uViewProj = 0
    private var uModel = 0
    private var builtVersion = -1
    private val first = IntArray(CubieModel.CUBIE_COUNT)
    private val count = IntArray(CubieModel.CUBIE_COUNT)
    private val models = FloatArray(CubieModel.CUBIE_COUNT * MAT4)
    private var viewProj = FloatArray(MAT4)

    override fun onSurfaceCreated(
        unused: GL10?,
        config: EGLConfig?,
    ) {
        program = link(vertexShader, fragmentShader)
        uViewProj = GLES30.glGetUniformLocation(program, "uViewProj")
        uModel = GLES30.glGetUniformLocation(program, "uModel")
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, atlas, 0)
        builtVersion = -1
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(
        unused: GL10?,
        width: Int,
        height: Int,
    ) {
        GLES30.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
        drawnEye = null
    }

    override fun onDrawFrame(unused: GL10?) {
        val currentEye = eye
        if (drawnEye != currentEye) {
            viewProj = ViewPose(width, height, currentEye).viewProjection()
            drawnEye = currentEye
        }
        val frame = snapshot ?: return
        frame.models.copyInto(models)
        var cursor = 0
        for (i in frame.counts.indices) {
            first[i] = cursor
            count[i] = frame.counts[i]
            cursor += count[i]
        }
        if (frame.meshVersion != builtVersion) {
            builtVersion = frame.meshVersion
            val buffer = ByteBuffer.allocateDirect(frame.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(frame.vertices).position(0)
            upload(buffer)
        }
        val clear = CubeMesh.rgb(background)
        GLES30.glClearColor(clear[0], clear[1], clear[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        val sizes = intArrayOf(3, 3, 3, 2, 3, 1)
        var offset = 0
        for (attribute in sizes.indices) {
            GLES30.glEnableVertexAttribArray(attribute)
            GLES30.glVertexAttribPointer(attribute, sizes[attribute], GLES30.GL_FLOAT, false, stride, offset * 4)
            offset += sizes[attribute]
        }
        for (i in 0 until CubieModel.CUBIE_COUNT) {
            if (count[i] == 0) continue
            GLES30.glUniformMatrix4fv(uModel, 1, false, models, i * MAT4)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, first[i], count[i])
        }
        drawnFrames.incrementAndGet()
    }

    private fun upload(vertices: FloatBuffer) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.capacity() * Float.SIZE_BYTES, vertices, GLES30.GL_STATIC_DRAW)
    }

    private companion object {
        const val MAT4 = 16
        const val FLOATS_PER_VERTEX = 15
        const val DEFAULT_BACKGROUND = 0xFFE8EBEF

        fun link(
            vertex: String,
            fragment: String,
        ): Int {
            val program = GLES30.glCreateProgram()
            val vertexId = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val fragmentId = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            GLES30.glAttachShader(program, vertexId)
            GLES30.glAttachShader(program, fragmentId)
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(vertexId)
            GLES30.glDeleteShader(fragmentId)
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
    }
}
