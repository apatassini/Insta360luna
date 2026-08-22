package it.persoft.lunaultra.ui.media

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visore per le foto sferiche.
 *
 * Una panoramica a 360° è un'immagine equirettangolare: mostrarla piatta la deforma ai poli e
 * la taglia in due dove i bordi si ricongiungono. Qui invece si guarda da dentro — si trascina
 * per girarsi intorno e si stringe per allargare o restringere il campo visivo.
 *
 * Non c'è nessuna sfera da disegnare: si copre lo schermo con due triangoli e per ogni pixel si
 * calcola in quale direzione si sta guardando, poi si va a prendere quel punto sulla texture.
 * Una sfera di triangoli darebbe lo stesso risultato con più codice e più errori di
 * interpolazione vicino ai poli.
 */
private const val VERTEX_SHADER = """
attribute vec2 aPosition;
varying vec2 vNdc;
void main() {
    vNdc = aPosition;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

private const val FRAGMENT_SHADER = """
precision highp float;
varying vec2 vNdc;
uniform sampler2D uTexture;
uniform float uAspect;
uniform float uTanHalfFov;
uniform mat3 uRotation;
const float PI = 3.14159265359;
void main() {
    vec3 ray = normalize(vec3(vNdc.x * uAspect * uTanHalfFov, vNdc.y * uTanHalfFov, -1.0));
    vec3 dir = uRotation * ray;
    float u = atan(dir.x, -dir.z) / (2.0 * PI) + 0.5;
    float v = acos(clamp(dir.y, -1.0, 1.0)) / PI;
    gl_FragColor = texture2D(uTexture, vec2(u, v));
}
"""

/** Stato della vista: dove si sta guardando e quanto campo visivo si abbraccia. */
class SphereState {
    @Volatile
    var yawDegrees: Float = 0f

    @Volatile
    var pitchDegrees: Float = 0f

    @Volatile
    var fovDegrees: Float = 80f

    fun rotateBy(deltaYaw: Float, deltaPitch: Float) {
        yawDegrees = (yawDegrees + deltaYaw) % 360f
        // Oltre i poli l'immagine si capovolge e ci si perde: ci si ferma appena prima.
        pitchDegrees = (pitchDegrees + deltaPitch).coerceIn(-85f, 85f)
    }

    fun zoomBy(factor: Float) {
        fovDegrees = (fovDegrees / factor).coerceIn(25f, 110f)
    }

    fun reset() {
        yawDegrees = 0f
        pitchDegrees = 0f
        fovDegrees = 80f
    }
}

@Composable
fun SphereImage(bitmap: Bitmap, state: SphereState, modifier: Modifier = Modifier) {
    val renderer = remember(bitmap) { EquirectRenderer(bitmap, state) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        update = { view -> view.onResume() },
        onRelease = { view -> view.onPause() },
    )
}

private class EquirectRenderer(
    private val bitmap: Bitmap,
    private val state: SphereState,
) : GLSurfaceView.Renderer {

    private var program = 0
    private var texture = 0
    private var aspect = 1f
    private val rotation = FloatArray(9)

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        texture = uploadTexture(bitmap)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)

        fillRotation(state.yawDegrees, state.pitchDegrees)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uRotation"), 1, false, rotation, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAspect"), aspect)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uTanHalfFov"),
            kotlin.math.tan(Math.toRadians(state.fovDegrees / 2.0)).toFloat(),
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    /** Rotazione yaw-poi-pitch, scritta a mano: sono nove numeri, non serve una libreria. */
    private fun fillRotation(yawDegrees: Float, pitchDegrees: Float) {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val pitch = Math.toRadians(pitchDegrees.toDouble())
        val cy = cos(yaw).toFloat()
        val sy = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()

        // Colonne, come le vuole OpenGL: R = Ry(yaw) * Rx(pitch)
        rotation[0] = cy
        rotation[1] = 0f
        rotation[2] = -sy
        rotation[3] = sy * sp
        rotation[4] = cp
        rotation[5] = cy * sp
        rotation[6] = sy * cp
        rotation[7] = -sp
        rotation[8] = cy * cp
    }

    private fun uploadTexture(source: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // In orizzontale l'immagine gira e si richiude su sé stessa; in verticale no.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)
        return ids[0]
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        return id
    }
}
