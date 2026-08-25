package it.persoft.lunaultra.stitch

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * La proiezione di un fotogramma sulla tela, fatta dalla scheda grafica.
 *
 * È il lavoro per cui una GPU esiste: per ogni pixel di uscita si torna indietro attraverso
 * l'obiettivo e si legge la foto sorgente con interpolazione bilineare. Quella lettura, che
 * sulla CPU costa quattro accessi in memoria più le moltiplicazioni dell'interpolazione,
 * sull'hardware delle texture è **gratis** — la fa l'unità di campionamento, non un ciclo.
 *
 * Quattro vincoli hanno deciso la forma di questa classe.
 *
 * **La tela non ci sta in una texture.** Diciassettemila pixel di larghezza superano il limite
 * di ottomila che molti telefoni dichiarano. Si disegna quindi a piastrelle, e ogni piastrella
 * si rilegge nella memoria dell'app.
 *
 * **Il contesto grafico appartiene a un filo.** Le corutine cambiano filo alle sospensioni,
 * quindi tutte le chiamate devono passare da uno solo: se ne occupa chi usa questa classe.
 *
 * **Niente qui può far fallire un'unione.** Ogni passo è dentro una `runCatching`, e il
 * fallimento restituisce `null`: chi ha chiamato ricade sul percorso CPU, che resta quello
 * buono e testato. Uno shader sbagliato deve costare una riga di log, non una panoramica.
 *
 * **Deve dire la stessa cosa della CPU.** Lo shader è la traduzione riga per riga di
 * [FrameProjector] più [featherWeight] più `FrameCorrection.factorAt`. Chi tocca una delle due
 * strade deve toccare l'altra, e l'autocontrollo di chi chiama confronta le due su un
 * campione di pixel proprio per accorgersi quando qualcuno se ne dimentica.
 *
 * Il colore torna già nell'ordine che serve. La lettura di OpenGL consegna i byte nell'ordine
 * R, G, B, A; su una macchina little-endian quei quattro byte letti come intero danno
 * `0xAABBGGRR`. Lo shader scrive quindi le componenti al contrario — `(b, g, r, a)` — e
 * l'intero che ne esce è già `0xAARRGGBB`, cioè il formato dei Bitmap di Android. L'alfa non
 * è trasparenza: è il **peso della sfumatura**, che serve a chi fonde.
 */
class GpuStitchRenderer private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface,
    private val program: Int,
    private val dummyTexture: Int,
    val maxTextureSize: Int,
    /** Il nome che il driver dà alla scheda: serve solo al log, ma è la prima cosa da sapere. */
    val hardware: String,
) {

    private var sourceTexture = 0
    private var targetTexture = 0
    private var frameBuffer = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private var readBuffer: IntBuffer? = null
    private val locations = HashMap<String, Int>()

    /**
     * Carica la foto sorgente sulla scheda. Una volta per fotogramma: è il trasferimento più
     * caro di tutti (centocinquanta megabyte per uno scatto da trentasette megapixel) e non
     * va rifatto a ogni piastrella.
     */
    fun uploadSource(bitmap: Bitmap): Boolean = runCatching {
        require(bitmap.width <= maxTextureSize && bitmap.height <= maxTextureSize) {
            "la sorgente ${bitmap.width}×${bitmap.height} supera il limite di $maxTextureSize"
        }
        releaseSource()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        sourceTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        // Interpolazione bilineare dall'hardware, e ai bordi si resta sull'ultimo pixel invece
        // di ripetere l'immagine: ripetere farebbe rientrare il lato opposto della foto.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        checkError("caricamento della sorgente")
        true
    }.getOrDefault(false)

    /** Scarica la sorgente: la memoria della scheda è poca, e un fotogramma alla volta basta. */
    fun dropSource() {
        runCatching { releaseSource() }
    }

    /**
     * Disegna una piastrella di tela e la riporta indietro.
     *
     * Le coordinate sono quelle **assolute** della tela: la piastrella copre le colonne da
     * [column0] e le righe da [row0]. Il risultato torna riga per riga dall'alto, e non serve
     * rovesciare niente: OpenGL rilegge dal basso, ma anche lo shader numera le righe dal
     * basso, e le due convenzioni si annullano.
     *
     * Con [weightOnly] non si legge la foto: torna solo il peso della sfumatura nell'alfa. È
     * la ricognizione, che di colori non sa che farsene.
     */
    fun renderTile(
        uniforms: GpuFrameUniforms,
        column0: Int,
        row0: Int,
        width: Int,
        height: Int,
        weightOnly: Boolean = false,
        into: IntArray? = null,
        intoOffset: Int = 0,
        intoStride: Int = width,
    ): Boolean = runCatching {
        ensureTarget(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (weightOnly) dummyTexture else sourceTexture)
        GLES30.glUniform1i(location("uSource"), 0)

        uniforms.apply(::location)
        GLES30.glUniform2f(location("uTileOrigin"), column0.toFloat(), row0.toFloat())
        GLES30.glUniform1i(location("uWeightOnly"), if (weightOnly) 1 else 0)

        // Un triangolo solo che copre tutto: i vertici li fabbrica il vertex shader dagli
        // indici, così non serve nessun buffer di geometria.
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        checkError("disegno della piastrella")

        val pixels = readBuffer ?: error("nessun buffer di rilettura")
        pixels.rewind()
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        checkError("rilettura della piastrella")
        pixels.rewind()
        val out = into ?: error("nessun vettore di destinazione")
        if (intoStride == width && intoOffset + width * height <= out.size) {
            pixels.get(out, intoOffset, width * height)
        } else {
            for (r in 0 until height) {
                pixels.position(r * width)
                pixels.get(out, intoOffset + r * intoStride, width)
            }
        }
        true
    }.getOrDefault(false)

    private fun ensureTarget(width: Int, height: Int) {
        // Basta che ci stia: il bersaglio si allarga, non si rifà. La finestra dell'ultima
        // fascia è sempre più bassa delle altre, e rifare texture, framebuffer e buffer di
        // rilettura per quella sarebbe sedici megabyte buttati a ogni fotogramma. Si disegna
        // e si rilegge nell'angolo in basso a sinistra, che è dove OpenGL comincia comunque.
        if (targetWidth >= width && targetHeight >= height && frameBuffer != 0) return
        releaseTarget()
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        targetTexture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, targetTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

        val buffers = IntArray(1)
        GLES30.glGenFramebuffers(1, buffers, 0)
        frameBuffer = buffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, targetTexture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer non completo: $status" }

        readBuffer = ByteBuffer.allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        targetWidth = width
        targetHeight = height
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun location(name: String): Int =
        locations.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun releaseSource() {
        if (sourceTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(sourceTexture), 0)
            sourceTexture = 0
        }
    }

    private fun releaseTarget() {
        if (frameBuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(frameBuffer), 0)
            frameBuffer = 0
        }
        if (targetTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(targetTexture), 0)
            targetTexture = 0
        }
        readBuffer = null
        targetWidth = 0
        targetHeight = 0
    }

    fun release() {
        runCatching {
            releaseSource()
            releaseTarget()
            if (dummyTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(dummyTexture), 0)
            GLES30.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun checkError(what: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "OpenGL ha risposto $error durante $what" }
    }

    companion object {
        /**
         * Accende un contesto grafico fuori schermo, o restituisce null se non è possibile.
         *
         * Va chiamata **dal filo** che poi disegnerà, e da nessun altro.
         */
        fun create(): GpuStitchRenderer? = runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "nessun display EGL" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize fallita" }

            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, found, 0) && found[0] > 0
            ) { "nessuna configurazione EGL adatta" }

            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "creazione del contesto fallita" }

            // Una superficie minima: non si disegna mai su di lei, serve solo perché un
            // contesto senza superficie non si può rendere corrente su tutti i driver.
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "creazione della superficie fallita" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent fallita" }

            val program = buildProgram()
            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            val name = (GLES30.glGetString(GLES30.GL_RENDERER) ?: "GPU").trim()

            // Un pixel di comodo da legare all'unità di campionamento quando il colore non
            // serve: un'unità senza niente attaccato è comportamento indefinito, anche se
            // lo shader poi non la legge.
            val dummy = IntArray(1)
            GLES30.glGenTextures(1, dummy, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummy[0])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()),
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

            GpuStitchRenderer(
                display, context, surface, program, dummy[0],
                maxTexture[0].coerceAtLeast(2048), name,
            )
        }.getOrNull()

        private fun buildProgram(): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { "collegamento fallito: ${GLES30.glGetProgramInfoLog(program)}" }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            return program
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { "compilazione fallita: ${GLES30.glGetShaderInfoLog(shader)}" }
            return shader
        }

        private const val EGL_OPENGL_ES3_BIT = 0x0040

        /** Il triangolo che copre lo schermo, fabbricato dagli indici senza nessun buffer. */
        private const val VERTEX_SHADER = """#version 300 es
void main() {
    float x = float((gl_VertexID & 1) << 2) - 1.0;
    float y = float((gl_VertexID & 2) << 1) - 1.0;
    gl_Position = vec4(x, y, 0.0, 1.0);
}
"""

        /**
         * La stessa geometria di [FrameProjector], riscritta per la scheda.
         *
         * Ogni blocco qui sotto ha il suo gemello in Kotlin, e i due devono restare uguali:
         * l'autocontrollo del chiamante li confronta su un campione di pixel a ogni unione.
         */
        private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;

uniform sampler2D uSource;
uniform vec2  uToTexture;       // da pixel della copia di lavoro a pixel dell'originale
uniform vec2  uInvSource;       // uno diviso le dimensioni dell'originale
uniform vec2  uWorkingSize;     // dimensioni della copia di lavoro
uniform vec2  uHalfImage;       // metà della copia di lavoro
uniform float uFocal;
uniform float uFocalScale;
uniform float uPanDegrees;
uniform float uCosTilt;
uniform float uSinTilt;
uniform float uCosRoll;
uniform float uSinRoll;

uniform vec2  uTileOrigin;      // colonna e riga di tela della piastrella
uniform float uStartLon;        // longitudine del bordo sinistro della tela
uniform float uPixelsPerDegree;
uniform float uRadius;
uniform float uTopY;
uniform int   uProjection;      // 0 equirettangolare, 1 cilindrica, 2 Mercatore

uniform float uGain;
uniform float uVignetteA;
uniform float uVignetteB;
uniform float uInvNorm;

uniform int  uWeightOnly;       // 1 = solo il peso, niente colore (la ricognizione)
uniform int  uWarpNodes;        // 0 = nessuna deformazione locale
uniform vec2 uWarpSize;         // nodi in orizzontale e verticale
uniform vec2 uWarp[128];

out vec4 fragColor;

const float PI = 3.14159265358979;
const float DEG = 0.01745329251994;
const float MIN_FORWARD = 1e-4;

float latitudeOfRow(float row) {
    float y = uTopY - (row + 0.5);
    if (uProjection == 1) return atan(y / uRadius) / DEG;
    if (uProjection == 2) return (2.0 * atan(exp(y / uRadius)) - PI * 0.5) / DEG;
    return y / uPixelsPerDegree;
}

vec2 warpAt(vec2 p) {
    vec2 cell = uWorkingSize / (uWarpSize - vec2(1.0));
    vec2 g = clamp(p / cell, vec2(0.0), uWarpSize - vec2(1.0));
    vec2 g0 = floor(g);
    vec2 f = g - g0;
    int w = int(uWarpSize.x);
    int x0 = int(g0.x);
    int y0 = int(g0.y);
    int x1 = min(x0 + 1, w - 1);
    int y1 = min(y0 + 1, int(uWarpSize.y) - 1);
    vec2 a = uWarp[y0 * w + x0];
    vec2 b = uWarp[y0 * w + x1];
    vec2 c = uWarp[y1 * w + x0];
    vec2 d = uWarp[y1 * w + x1];
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    float col = uTileOrigin.x + floor(gl_FragCoord.x);
    float row = uTileOrigin.y + floor(gl_FragCoord.y);

    float lonDeg = uStartLon + (col + 0.5) / uPixelsPerDegree - uPanDegrees;
    float lon = lonDeg * DEG;
    float lat = latitudeOfRow(row) * DEG;

    // Versore della direzione, con z in avanti, x a destra e y in alto.
    float cosLat = cos(lat);
    float wx = cosLat * sin(lon);
    float wy = sin(lat);
    float wz = cosLat * cos(lon);

    // Rotazione inversa dell'inclinazione, poi del rollio.
    float ty = wy * uCosTilt - wz * uSinTilt;
    float tz = wy * uSinTilt + wz * uCosTilt;
    if (tz <= MIN_FORWARD) { fragColor = vec4(0.0); return; }

    float rx =  wx * uCosRoll + ty * uSinRoll;
    float ry = -wx * uSinRoll + ty * uCosRoll;

    vec2 p = vec2(
        uHalfImage.x + uFocal * (rx * uFocalScale) / tz,
        uHalfImage.y - uFocal * (ry * uFocalScale) / tz
    );

    // La deformazione locale si applica solo a chi era già dentro: come sulla CPU, un punto
    // fuori campo resta fuori invece di essere trascinato dentro dal campo.
    vec2 limit = uWorkingSize - vec2(1.0);
    bool inside = p.x >= 0.0 && p.y >= 0.0 && p.x <= limit.x && p.y <= limit.y;
    if (uWarpNodes != 0 && inside) p += warpAt(p);
    if (p.x < 0.0 || p.y < 0.0 || p.x > limit.x || p.y > limit.y) { fragColor = vec4(0.0); return; }

    // La sfumatura: distanza dal bordo più vicino sulla metà del lato corto, al quadrato.
    vec2 edge = min(p, limit - p);
    float scale = min(uWorkingSize.x, uWorkingSize.y) * 0.5;
    float feather = clamp(min(edge.x, edge.y) / scale, 0.0, 1.0);
    feather = feather * feather;
    if (feather <= 0.0) { fragColor = vec4(0.0); return; }

    vec3 colour = vec3(0.0);
    if (uWeightOnly == 0) {
        colour = texture(uSource, (p * uToTexture + vec2(0.5)) * uInvSource).rgb;

        // Fotometria: guadagno diviso vignettatura, con lo stesso limite del percorso CPU.
        vec2 d = p - uHalfImage;
        float r2 = dot(d, d) * uInvNorm;
        float v = max(1.0 + uVignetteA * r2 + uVignetteB * r2 * r2, 0.4);
        colour = clamp(colour * (uGain / v), 0.0, 1.0);
    }

    // Componenti al contrario: riletto come intero little-endian diventa 0xAARRGGBB, che è
    // il formato dei Bitmap. L'alfa non è trasparenza, è il peso della sfumatura — e non
    // scende mai a zero per un pixel coperto, esattamente come sulla CPU, dove il peso
    // arrotondato a zero viene riportato a uno: zero vuol dire «qui non ci sono», e un
    // pixel dentro il fotogramma c'è anche quando pesa pochissimo.
    fragColor = vec4(colour.b, colour.g, colour.r, max(feather, 1.0 / 255.0));
}
"""
    }
}

/**
 * I numeri che descrivono un fotogramma alla scheda grafica.
 *
 * Sono gli stessi che [FrameProjector] tiene nei suoi campi: se una delle due strade cambia
 * senza l'altra, l'autocontrollo del chiamante se ne accorge sul primo campione confrontato.
 */
class GpuFrameUniforms(
    /** Dimensioni dell'immagine da cui si campiona davvero: l'originale, o la copia. */
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    /** La scala fra copia di lavoro e sorgente, quella che la CPU chiama `fullScale`. */
    private val toSourceX: Float,
    private val toSourceY: Float,
    private val workingWidth: Int,
    private val workingHeight: Int,
    private val focalPixels: Float,
    private val focalScale: Float,
    private val panDegrees: Float,
    private val cosTilt: Float,
    private val sinTilt: Float,
    private val cosRoll: Float,
    private val sinRoll: Float,
    private val startLongitude: Float,
    private val pixelsPerDegree: Float,
    private val radius: Float,
    private val topY: Float,
    private val projection: Int,
    private val gain: Float,
    private val vignetteA: Float,
    private val vignetteB: Float,
    private val warpNodesX: Int,
    private val warpNodesY: Int,
    private val warp: FloatArray,
) {
    fun apply(location: (String) -> Int) {
        GLES30.glUniform2f(location("uToTexture"), toSourceX, toSourceY)
        GLES30.glUniform2f(location("uInvSource"), 1f / sourceWidth, 1f / sourceHeight)
        GLES30.glUniform2f(location("uWorkingSize"), workingWidth.toFloat(), workingHeight.toFloat())
        GLES30.glUniform2f(location("uHalfImage"), workingWidth / 2f, workingHeight / 2f)
        GLES30.glUniform1f(location("uFocal"), focalPixels)
        GLES30.glUniform1f(location("uFocalScale"), focalScale)
        GLES30.glUniform1f(location("uPanDegrees"), panDegrees)
        GLES30.glUniform1f(location("uCosTilt"), cosTilt)
        GLES30.glUniform1f(location("uSinTilt"), sinTilt)
        GLES30.glUniform1f(location("uCosRoll"), cosRoll)
        GLES30.glUniform1f(location("uSinRoll"), sinRoll)
        GLES30.glUniform1f(location("uStartLon"), startLongitude)
        GLES30.glUniform1f(location("uPixelsPerDegree"), pixelsPerDegree)
        GLES30.glUniform1f(location("uRadius"), radius)
        GLES30.glUniform1f(location("uTopY"), topY)
        GLES30.glUniform1i(location("uProjection"), projection)
        GLES30.glUniform1f(location("uGain"), gain)
        GLES30.glUniform1f(location("uVignetteA"), vignetteA)
        GLES30.glUniform1f(location("uVignetteB"), vignetteB)
        val halfW = workingWidth / 2f
        val halfH = workingHeight / 2f
        GLES30.glUniform1f(location("uInvNorm"), 1f / (halfW * halfW + halfH * halfH))
        GLES30.glUniform1i(location("uWarpNodes"), if (warp.isEmpty()) 0 else warpNodesX * warpNodesY)
        GLES30.glUniform2f(location("uWarpSize"), warpNodesX.toFloat(), warpNodesY.toFloat())
        if (warp.isNotEmpty()) {
            GLES30.glUniform2fv(location("uWarp"), warpNodesX * warpNodesY, warp, 0)
        }
    }
}
