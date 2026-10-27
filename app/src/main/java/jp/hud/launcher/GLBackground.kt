package jp.hud.launcher

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * OpenGL ES 2.0 で描く本物の3Dレイヤー（HUDの一番奥）。
 *  - 深い宇宙のグラデーション、瞬く星、コアの光輪
 *  - 投影台から奥へ流れる遠近グリッドの床
 *  - 回転するホログラム地球儀（輪郭の発光・経緯線・大陸のドット・走査線）
 *  - 傾いた軌道リングと周回する衛星、現在地のピン
 */
class GLBackground(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    // HudView から毎フレーム渡される値（ピクセル単位）
    @Volatile var coreX = 0f
    @Volatile var coreY = 0f
    @Volatile var coreR = 100f
    @Volatile var globeR = 40f
    @Volatile var horizon = 0f
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    @Volatile var pulse = 0f
    @Volatile var fps = 24
    @Volatile var paused = false
    @Volatile var dim = 0f
    @Volatile var pinLat = 35.02
    @Volatile var pinLon = 138.48
    @Volatile var ready = false

    private var thread: RenderThread? = null

    init { surfaceTextureListener = this; isOpaque = true }

    /** 背景は柔らかい絵なので、内部解像度を下げて描いて拡大する（GPU負荷を約1/3に） */
    private val SCALE = 0.55f

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        st.setDefaultBufferSize((w * SCALE).toInt(), (h * SCALE).toInt())
        thread = RenderThread(st, w, h).also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        st.setDefaultBufferSize((w * SCALE).toInt(), (h * SCALE).toInt()); thread?.resize(w, h)
    }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.quit(); try { thread?.join(500) } catch (e: Exception) { }
        thread = null; ready = false
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private inner class RenderThread(val st: SurfaceTexture, @Volatile var w: Int, @Volatile var h: Int) : Thread("hud-gl") {
        @Volatile private var running = true
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surf: EGLSurface = EGL14.EGL_NO_SURFACE

        fun resize(nw: Int, nh: Int) { w = nw; h = nh }
        fun quit() { running = false }

        override fun run() {
            try {
                initEgl()
                initGl()
                ready = true
                val t0 = System.nanoTime()
                while (running) {
                    val frameStart = System.nanoTime()
                    if (!paused) {
                        GLES20.glViewport(0, 0, (w * SCALE).toInt(), (h * SCALE).toInt())
                        drawFrame((frameStart - t0) / 1e9f)
                        EGL14.eglSwapBuffers(display, surf)
                    }
                    val budget = 1_000_000_000L / fps.coerceIn(5, 60)
                    val spent = System.nanoTime() - frameStart
                    val sleep = (budget - spent) / 1_000_000
                    if (sleep > 0) sleep(sleep) else if (paused) sleep(100)
                }
            } catch (e: Throwable) {
                ready = false
            } finally {
                releaseEgl()
            }
        }

        private fun initEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0)
            ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreateWindowSurface(display, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surf, surf, ctx)
        }

        private fun releaseEgl() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
        }

        // ---------------- GL resources ----------------
        private var bgProg = 0; private var globeProg = 0; private var lineProg = 0
        private lateinit var quad: FloatBuffer
        private lateinit var sphere: FloatBuffer
        private lateinit var sphereIdx: ShortBuffer
        private var sphereCount = 0
        private val linePts = FloatArray(2 * 512)
        private lateinit var lineBuf: FloatBuffer

        private fun fb(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun initGl() {
            bgProg = program(VS_QUAD, FS_BG)
            globeProg = program(VS_GLOBE, FS_GLOBE)
            lineProg = program(VS_LINE, FS_LINE)
            quad = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            // sphere mesh
            val lat = 36; val lon = 64
            val v = FloatArray((lat + 1) * (lon + 1) * 3)
            var k = 0
            for (i in 0..lat) {
                val th = Math.PI * i / lat - Math.PI / 2
                for (j in 0..lon) {
                    val ph = 2 * Math.PI * j / lon
                    v[k++] = (cos(th) * cos(ph)).toFloat(); v[k++] = sin(th).toFloat(); v[k++] = (cos(th) * sin(ph)).toFloat()
                }
            }
            sphere = fb(v)
            val idx = ShortArray(lat * lon * 6); k = 0
            for (i in 0 until lat) for (j in 0 until lon) {
                val a = (i * (lon + 1) + j).toShort(); val b = (a + lon + 1).toShort()
                idx[k++] = a; idx[k++] = b; idx[k++] = (a + 1).toShort()
                idx[k++] = b; idx[k++] = (b + 1).toShort(); idx[k++] = (a + 1).toShort()
            }
            sphereCount = idx.size
            sphereIdx = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx); position(0) }
            lineBuf = ByteBuffer.allocateDirect(linePts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
        }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
        }
        private fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p); return p
        }

        // rotation matrix (column-major mat3)
        private val rot = FloatArray(9)
        private fun buildRot(ax: Float, ay: Float) {
            val cx = cos(ax); val sx = sin(ax); val cy = cos(ay); val sy = sin(ay)
            // R = Rx(ax) * Ry(ay)
            rot[0] = cy; rot[1] = sx * sy; rot[2] = -cx * sy
            rot[3] = 0f; rot[4] = cx; rot[5] = sx
            rot[6] = sy; rot[7] = -sx * cy; rot[8] = cx * cy
        }
        private fun apply(x: Float, y: Float, z: Float): FloatArray =
            floatArrayOf(rot[0] * x + rot[3] * y + rot[6] * z, rot[1] * x + rot[4] * y + rot[7] * z, rot[2] * x + rot[5] * y + rot[8] * z)

        private fun drawFrame(t: Float) {
            val W = w.toFloat(); val H = h.toFloat()
            GLES20.glDisable(GLES20.GL_BLEND)
            // ---- background
            GLES20.glUseProgram(bgProg)
            uni2(bgProg, "uRes", W, H); uni2(bgProg, "uCore", coreX, coreY)
            uni1(bgProg, "uR", coreR); uni1(bgProg, "uT", t); uni2(bgProg, "uTilt", tiltX, tiltY)
            uni1(bgProg, "uPulse", pulse); uni1(bgProg, "uHor", horizon); uni1(bgProg, "uDim", dim)
            val aq = GLES20.glGetAttribLocation(bgProg, "aPos")
            GLES20.glEnableVertexAttribArray(aq)
            GLES20.glVertexAttribPointer(aq, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aq)

            // ---- globe (additive)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            val ax = 0.38f + tiltY * 0.25f
            val ay = t * 0.21f + tiltX * 0.3f
            buildRot(ax, ay)
            GLES20.glUseProgram(globeProg)
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(globeProg, "uRot"), 1, false, rot, 0)
            uni2(globeProg, "uCore", coreX, coreY); uni1(globeProg, "uR", globeR); uni2(globeProg, "uRes", W, H)
            uni1(globeProg, "uT", t); uni1(globeProg, "uAlpha", (1f - dim * 0.5f) * (0.85f + 0.3f * pulse))
            val ag = GLES20.glGetAttribLocation(globeProg, "aPos")
            GLES20.glEnableVertexAttribArray(ag)
            GLES20.glVertexAttribPointer(ag, 3, GLES20.GL_FLOAT, false, 0, sphere)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereCount, GLES20.GL_UNSIGNED_SHORT, sphereIdx)
            GLES20.glDisableVertexAttribArray(ag)

            // ---- orbit rings + satellites + pin
            GLES20.glUseProgram(lineProg)
            uni2(lineProg, "uRes", W, H)
            val al = GLES20.glGetAttribLocation(lineProg, "aPos")
            GLES20.glEnableVertexAttribArray(al)
            for (k in 0 until 3) {
                val tilt = 1.15f + k * 0.22f; val spin = k * 1.9f + 0.4f
                val rr = 1.22f + k * 0.13f
                val n = 128
                for (i in 0 until n) {
                    val a = (2 * Math.PI * i / (n - 1)).toFloat()
                    val p = orbit(a, rr, tilt, spin)
                    linePts[i * 2] = coreX + p[0] * globeR; linePts[i * 2 + 1] = coreY - p[1] * globeR
                }
                drawLines(al, n, GLES20.GL_LINE_STRIP, 0.25f, 0.55f, 0.75f, 0.35f, 1f)
                // satellite with trail
                val sa = t * (0.7f + k * 0.23f) + k
                for (j in 0 until 10) {
                    val p = orbit(sa - j * 0.05f, rr, tilt, spin)
                    linePts[j * 2] = coreX + p[0] * globeR; linePts[j * 2 + 1] = coreY - p[1] * globeR
                }
                val front = if (orbit(sa, rr, tilt, spin)[2] > 0) 1f else 0.35f
                drawLines(al, 10, GLES20.GL_LINE_STRIP, 0.6f * front, 0.9f * front, 1f * front, 1f, 1f)
                drawLines(al, 1, GLES20.GL_POINTS, 0.8f * front, 0.95f * front, 1f * front, 1f, globeR * 0.09f)
            }
            // location pin
            val la = Math.toRadians(pinLat); val lo = Math.toRadians(pinLon - 138.48 + 90.0)
            val pp = apply((cos(la) * cos(lo)).toFloat(), sin(la).toFloat(), (cos(la) * sin(lo)).toFloat())
            if (pp[2] > 0f) {
                linePts[0] = coreX + pp[0] * globeR; linePts[1] = coreY - pp[1] * globeR
                val pz = 1f + 0.5f * sin(t * 5f)
                drawLines(al, 1, GLES20.GL_POINTS, 1f, 0.35f, 0.35f, 1f, globeR * 0.14f * pz)
                // beam
                linePts[2] = linePts[0] + pp[0] * globeR * 0.5f; linePts[3] = linePts[1] - pp[1] * globeR * 0.5f
                drawLines(al, 2, GLES20.GL_LINES, 0.9f, 0.3f, 0.3f, 1f, 1f)
            }
            GLES20.glDisableVertexAttribArray(al)
        }

        /** 傾いた軌道上の点（回転後・正規化座標） */
        private fun orbit(a: Float, r: Float, tilt: Float, spin: Float): FloatArray {
            var x = cos(a) * r; var y = 0f; var z = sin(a) * r
            // tilt around X
            val y1 = y * cos(tilt) - z * sin(tilt); val z1 = y * sin(tilt) + z * cos(tilt); y = y1; z = z1
            // spin around Z
            val x2 = x * cos(spin) - y * sin(spin); val y2 = x * sin(spin) + y * cos(spin); x = x2; y = y2
            return apply(x, y, z)
        }

        private fun drawLines(attr: Int, n: Int, mode: Int, r: Float, g: Float, b: Float, a: Float, size: Float) {
            lineBuf.position(0); lineBuf.put(linePts, 0, n * 2); lineBuf.position(0)
            GLES20.glVertexAttribPointer(attr, 2, GLES20.GL_FLOAT, false, 0, lineBuf)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(lineProg, "uColor"), r * a, g * a, b * a, 1f)
            uni1(lineProg, "uSize", size * SCALE)
            uni1(lineProg, "uPoint", if (mode == GLES20.GL_POINTS) 1f else 0f)
            GLES20.glLineWidth(2f)
            GLES20.glDrawArrays(mode, 0, n)
        }

        private fun uni1(p: Int, n: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(p, n), v)
        private fun uni2(p: Int, n: String, a: Float, b: Float) = GLES20.glUniform2f(GLES20.glGetUniformLocation(p, n), a, b)
    }

    companion object {
        private const val VS_QUAD = """
            attribute vec2 aPos; varying vec2 vUv;
            void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }"""

        private const val FS_BG = """
            precision mediump float;
            uniform vec2 uRes; uniform vec2 uCore; uniform float uR; uniform float uT; uniform vec2 uTilt;
            uniform float uPulse; uniform float uHor; uniform float uDim;
            varying vec2 vUv;
            float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            void main(){
                vec2 px = vec2(vUv.x * uRes.x, (1.0 - vUv.y) * uRes.y);
                vec2 d = (px - uCore) / uRes.y;
                float r = length(d);
                vec3 col = mix(vec3(0.035, 0.15, 0.26), vec3(0.004, 0.018, 0.04), smoothstep(0.0, 1.0, r));
                float hr = uR / uRes.y;
                col += vec3(0.22, 0.62, 1.0) * exp(-pow(r / (hr * 1.7), 2.0)) * (0.32 + 0.25 * uPulse);
                col += vec3(0.10, 0.35, 0.7) * exp(-pow(r / (hr * 4.0), 2.0)) * 0.25;
                // stars
                vec2 sp = px + uTilt * 30.0;
                vec2 g = floor(sp / 3.0);
                float h = hash(g);
                float tw = 0.55 + 0.45 * sin(uT * (1.5 + h * 3.0) + h * 60.0);
                col += vec3(0.65, 0.88, 1.0) * step(0.9972, h) * tw * 0.9;
                // perspective grid floor
                if (px.y > uHor) {
                    float y = (px.y - uHor) / max(uRes.y - uHor, 1.0);
                    float z = 1.0 / (y + 0.035);
                    float x = (px.x - uCore.x + uTilt.x * 60.0) / uRes.y * z * 0.55;
                    float zz = z * 0.6 + uT * 0.9;
                    float gx = abs(fract(x * 3.0) - 0.5);
                    float gz = abs(fract(zz) - 0.5);
                    float wdt = 0.012 * z;
                    float line = (1.0 - smoothstep(0.0, wdt, gx)) + (1.0 - smoothstep(0.0, wdt * 0.8, gz));
                    float fade = smoothstep(0.0, 0.25, y);
                    col += vec3(0.3, 0.78, 1.0) * line * 0.26 * fade;
                    col += vec3(0.15, 0.5, 1.0) * 0.06 * y;
                }
                col += vec3(0.35, 0.8, 1.0) * exp(-abs(px.y - uHor) / 2.5) * 0.35;
                vec2 q = vUv - 0.5;
                col *= 1.0 - dot(q, q) * 1.2;
                col *= 1.0 - uDim * 0.55;
                gl_FragColor = vec4(col, 1.0);
            }"""

        private const val VS_GLOBE = """
            attribute vec3 aPos; uniform mat3 uRot; uniform vec2 uCore; uniform float uR; uniform vec2 uRes;
            varying vec3 vN; varying vec3 vObj;
            void main(){
                vec3 p = uRot * aPos; vN = p; vObj = aPos;
                vec2 s = uCore + vec2(p.x, -p.y) * uR;
                gl_Position = vec4(s.x / uRes.x * 2.0 - 1.0, 1.0 - s.y / uRes.y * 2.0, 0.0, 1.0);
            }"""

        private const val FS_GLOBE = """
            precision mediump float;
            varying vec3 vN; varying vec3 vObj; uniform float uT; uniform float uAlpha;
            float hash(vec3 p){ return fract(sin(dot(p, vec3(12.9898, 78.233, 45.164))) * 43758.5453); }
            float noise(vec3 p){
                vec3 i = floor(p); vec3 f = fract(p); f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash(i), hash(i + vec3(1.0,0.0,0.0)), f.x), mix(hash(i + vec3(0.0,1.0,0.0)), hash(i + vec3(1.0,1.0,0.0)), f.x), f.y),
                           mix(mix(hash(i + vec3(0.0,0.0,1.0)), hash(i + vec3(1.0,0.0,1.0)), f.x), mix(hash(i + vec3(0.0,1.0,1.0)), hash(i + vec3(1.0,1.0,1.0)), f.x), f.y), f.z);
            }
            void main(){
                vec3 n = normalize(vN);
                float front = n.z > 0.0 ? 1.0 : 0.22;
                float fres = pow(1.0 - abs(n.z), 2.2);
                vec3 o = normalize(vObj);
                float lat = asin(clamp(o.y, -1.0, 1.0));
                float lon = atan(o.z, o.x);
                float g1 = 1.0 - smoothstep(0.0, 0.035, abs(fract(lat / 0.2618 + 0.5) - 0.5));
                float g2 = 1.0 - smoothstep(0.0, 0.035, abs(fract(lon / 0.2618 + 0.5) - 0.5));
                float land = smoothstep(0.5, 0.58, noise(o * 2.3) * 0.65 + noise(o * 5.5) * 0.35);
                vec2 uv = vec2(lon * 14.0, lat * 14.0);
                float dots = 1.0 - smoothstep(0.12, 0.32, length(fract(uv) - 0.5));
                float scan = 1.0 - smoothstep(0.0, 0.04, abs(fract(o.y * 0.5 + 0.5 - uT * 0.12) - 0.5));
                float a = fres * 0.95 + (g1 + g2) * 0.16 + land * dots * 0.6 + scan * 0.3 + 0.035;
                vec3 c = mix(vec3(0.3, 0.75, 1.0), vec3(0.85, 0.97, 1.0), fres * 0.6);
                gl_FragColor = vec4(c * a * front * uAlpha, 1.0);
            }"""

        private const val VS_LINE = """
            attribute vec2 aPos; uniform vec2 uRes; uniform float uSize;
            void main(){ gl_PointSize = uSize; gl_Position = vec4(aPos.x / uRes.x * 2.0 - 1.0, 1.0 - aPos.y / uRes.y * 2.0, 0.0, 1.0); }"""

        private const val FS_LINE = """
            precision mediump float; uniform vec4 uColor; uniform float uPoint;
            void main(){
                float a = 1.0;
                if (uPoint > 0.5) { float d = length(gl_PointCoord - 0.5); a = 1.0 - smoothstep(0.25, 0.5, d); }
                gl_FragColor = vec4(uColor.rgb * a, 1.0);
            }"""
    }
}
