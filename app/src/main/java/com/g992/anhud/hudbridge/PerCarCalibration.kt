package com.g992.anhud.hudbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-car surround camera calibration from the AVM top-view mesh (/calibration/Table_2d.db on QNX),
 * a 1:1 port of chinaResearch/analysis/rvc/percar/percar.py (load_blocks, kb_unproject, dlt_pose,
 * refine, calibrate, sanity, view_matrix, protocol_ypr, build_lut).
 *
 * Car frame: metres, origin on the ground below the front axle, X right, Y forward, Z up.
 * Camera frame (CAMCFG): x right, y down, z forward. 3x3 matrices are row-major DoubleArray(9).
 * Lens: Kannala-Brandt with fx = fy = f, cx, cy fitted and k1..k4 fixed to [K_REF].
 */
object PerCarCalibration {
    const val BLOCK_SIZE = 392
    const val SRC_W = 1280
    const val SRC_H = 800
    /** k1..k4 of car #1's rear camera; not observable from the table beyond ~77°, so fixed. */
    val K_REF = doubleArrayOf(0.07596, -0.05067, 0.04659, -0.01080)
    const val F_INIT = 328.0
    const val CX_INIT = 640.0
    const val CY_INIT = 400.0
    /** The daemon draws black for rays further than this from the optical axis. */
    const val MAX_RAY_ANGLE_DEG = 100.0

    /** Table tags of the single-camera mesh blocks (odd tags are blend zones). */
    const val TAG_RIGHT = 0
    const val TAG_FRONT = 2
    const val TAG_LEFT = 4
    const val TAG_REAR = 6
    private val TAGS = intArrayOf(TAG_RIGHT, TAG_FRONT, TAG_LEFT, TAG_REAR)

    /** Sample fetched from QNX: [SAMPLE_CHUNKS] runs of [SAMPLE_CHUNK_BLOCKS] blocks spread over the file. */
    const val SAMPLE_CHUNKS = 64
    const val SAMPLE_CHUNK_BLOCKS = 8
    const val SAMPLE_BYTES = SAMPLE_CHUNKS * SAMPLE_CHUNK_BLOCKS * BLOCK_SIZE

    private const val MIN_FIT_POINTS = 30
    private const val MIN_SANE_POINTS = 40

    /** Mesh vertices of one camera: [xyz] car metres (3 per point), [uv] source pixels (2 per point). */
    class Points(val xyz: DoubleArray, val uv: DoubleArray) {
        val size: Int
            get() = uv.size / 2
    }

    /** Fitted camera: car -> camera rotation [r], translation [t] (camera = R*car + t), lens f/cx/cy. */
    class Fit(
        val r: DoubleArray,
        val t: DoubleArray,
        val f: Double,
        val cx: Double,
        val cy: Double,
        val rms: Double,
        val maxErr: Double,
        val n: Int
    ) {
        /** Camera centre in the car frame. */
        val centre: DoubleArray
            get() = DoubleArray(3) { i -> -(r[i] * t[0] + r[3 + i] * t[1] + r[6 + i] * t[2]) }

        /** (heading, down, roll) of the physical camera in car terms, degrees. */
        val carTerms: DoubleArray
            get() = camCarTerms(r)
    }

    /** First block of each of the [SAMPLE_CHUNKS] chunks: round(i * (nblk - 8) / 63) in integers. */
    fun sampleOffsets(blockCount: Int): IntArray {
        val span = (blockCount - SAMPLE_CHUNK_BLOCKS).toLong().coerceAtLeast(0L)
        val last = (SAMPLE_CHUNKS - 1).toLong()
        return IntArray(SAMPLE_CHUNKS) { i -> ((2L * i * span + last) / (2L * last)).toInt() }
    }

    // ------------------------------------------------------------------ file parsing

    /**
     * Blocks of 392 bytes: int64 tag + 8 vertices x (x, y, z, alpha, u, v) float64, little-endian.
     * Returns tag -> unique single-camera vertices well inside the frame. [groundOnly] keeps z == 0
     * (Table.db has 3-D bowl vertices too). Throws IllegalArgumentException on malformed data.
     */
    fun parseBlocks(data: ByteArray, groundOnly: Boolean = false): Map<Int, Points> {
        require(data.isNotEmpty() && data.size % BLOCK_SIZE == 0) { "size ${data.size} not a multiple of $BLOCK_SIZE" }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val unique = HashMap<Int, LinkedHashSet<List<Double>>>()
        TAGS.forEach { unique[it] = LinkedHashSet() }
        val blocks = data.size / BLOCK_SIZE
        for (i in 0 until blocks) {
            val base = i * BLOCK_SIZE
            val tag = b.getLong(base)
            require(tag in 0L..7L) { "bad tag $tag" }
            val vertices = DoubleArray(48) { b.getDouble(base + 8 + it * 8) }
            require(vertices.all { it.isFinite() }) { "NaN/Inf in table" }
            val set = unique[tag.toInt()] ?: continue
            for (v in 0 until 8) {
                set.add(vertices.asList().subList(v * 6, v * 6 + 6).toList())
            }
        }
        return unique.mapValues { (_, set) ->
            val kept = set.filter { p ->
                p[4] > 0.002 && p[4] < 0.998 && p[5] > 0.002 && p[5] < 0.998 && p[3] == 1.0 &&
                    (!groundOnly || p[2] == 0.0)
            }
            Points(
                xyz = DoubleArray(kept.size * 3) { kept[it / 3][it % 3] },
                uv = DoubleArray(kept.size * 2) { kept[it / 2][4 + it % 2] * if (it % 2 == 0) SRC_W else SRC_H }
            )
        }
    }

    // ------------------------------------------------------------------ KB model

    /** Camera-frame point -> source pixel (out[0], out[1]). */
    fun kbProject(x: Double, y: Double, z: Double, f: Double, cx: Double, cy: Double, k: DoubleArray, out: DoubleArray) {
        val r = hypot(x, y)
        val t = atan2(r, z)
        val t2 = t * t
        val rd = t * (1 + k[0] * t2 + k[1] * t2 * t2 + k[2] * t2 * t2 * t2 + k[3] * t2 * t2 * t2 * t2)
        val s = if (r > 1e-12) rd / max(r, 1e-12) else 0.0
        out[0] = cx + f * s * x
        out[1] = cy + f * s * y
    }

    /** Source pixel -> unit ray in the camera frame (out[0..2]). */
    fun kbUnproject(u: Double, v: Double, f: Double, cx: Double, cy: Double, k: DoubleArray, out: DoubleArray) {
        val x = (u - cx) / f
        val y = (v - cy) / f
        val rd = hypot(x, y)
        var t = rd
        repeat(8) {
            val t2 = t * t
            val fn = t * (1 + k[0] * t2 + k[1] * t2 * t2 + k[2] * t2 * t2 * t2 + k[3] * t2 * t2 * t2 * t2) - rd
            t -= fn / (1 + 3 * k[0] * t2 + 5 * k[1] * t2 * t2 + 7 * k[2] * t2 * t2 * t2 + 9 * k[3] * t2 * t2 * t2 * t2)
        }
        val s = if (rd > 1e-12) sin(t) / max(rd, 1e-12) else 0.0
        out[0] = x * s
        out[1] = y * s
        out[2] = cos(t)
    }

    // ------------------------------------------------------------------ small linear algebra

    fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun transpose3(a: DoubleArray): DoubleArray = DoubleArray(9) { i -> a[(i % 3) * 3 + i / 3] }

    fun rodrigues(wx: Double, wy: Double, wz: Double): DoubleArray {
        val th = sqrt(wx * wx + wy * wy + wz * wz)
        if (th < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val kx = wx / th
        val ky = wy / th
        val kz = wz / th
        val k = doubleArrayOf(0.0, -kz, ky, kz, 0.0, -kx, -ky, kx, 0.0)
        val kk = mul3(k, k)
        val s = sin(th)
        val c1 = 1 - cos(th)
        return DoubleArray(9) { i -> (if (i % 4 == 0) 1.0 else 0.0) + s * k[i] + c1 * kk[i] }
    }

    /** Cyclic Jacobi for a small symmetric n x n matrix (row-major). Returns eigenvalues and V (columns). */
    fun jacobiEigSym(input: DoubleArray, n: Int, sweeps: Int = 30): Pair<DoubleArray, DoubleArray> {
        var a = input.copyOf()
        var v = DoubleArray(n * n) { if (it / n == it % n) 1.0 else 0.0 }
        val bTmp = DoubleArray(n * n)
        for (sweep in 0 until sweeps) {
            var off = 0.0
            for (i in 0 until n) for (j in 0 until n) if (i != j) off += a[i * n + j] * a[i * n + j]
            if (off < 1e-30) break
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    val apq = a[p * n + q]
                    if (abs(apq) < 1e-300) continue
                    val th = (a[q * n + q] - a[p * n + p]) / (2 * apq)
                    val t = if (abs(th) > 1e150) 1.0 / (2 * th) else sign1(th) / (abs(th) + sqrt(th * th + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val s = t * c
                    // B = A * J (J: identity with J[p,p]=J[q,q]=c, J[p,q]=s, J[q,p]=-s), A = J^T * B, V = V * J.
                    a.copyInto(bTmp)
                    for (r in 0 until n) {
                        val arp = a[r * n + p]
                        val arq = a[r * n + q]
                        bTmp[r * n + p] = c * arp - s * arq
                        bTmp[r * n + q] = s * arp + c * arq
                    }
                    val next = bTmp.copyOf()
                    for (col in 0 until n) {
                        val bp = bTmp[p * n + col]
                        val bq = bTmp[q * n + col]
                        next[p * n + col] = c * bp - s * bq
                        next[q * n + col] = s * bp + c * bq
                    }
                    a = next
                    for (r in 0 until n) {
                        val vp = v[r * n + p]
                        val vq = v[r * n + q]
                        v[r * n + p] = c * vp - s * vq
                        v[r * n + q] = s * vp + c * vq
                    }
                }
            }
        }
        return DoubleArray(n) { a[it * n + it] } to v
    }

    /** math.copysign(1, x) */
    private fun sign1(x: Double): Double = if (x < 0 || (x == 0.0 && 1.0 / x < 0)) -1.0 else 1.0

    fun inverse3(m: DoubleArray): DoubleArray {
        val c00 = m[4] * m[8] - m[5] * m[7]
        val c01 = m[5] * m[6] - m[3] * m[8]
        val c02 = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * c00 + m[1] * c01 + m[2] * c02
        return doubleArrayOf(
            c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det
        )
    }

    /** Polar decomposition iteration R = (R + R^-T) / 2. */
    fun orthonormalize(m: DoubleArray): DoubleArray {
        var r = m.copyOf()
        repeat(20) {
            val invT = transpose3(inverse3(r))
            r = DoubleArray(9) { 0.5 * (r[it] + invT[it]) }
        }
        return r
    }

    /** Solves the n x n system a * x = b (Gaussian elimination, partial pivoting); a and b are destroyed. */
    fun solve(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray {
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[piv * n + col])) piv = r
            if (piv != col) {
                for (c in 0 until n) {
                    val tmp = a[col * n + c]
                    a[col * n + c] = a[piv * n + c]
                    a[piv * n + c] = tmp
                }
                val tmp = b[col]
                b[col] = b[piv]
                b[piv] = tmp
            }
            val d = a[col * n + col]
            for (r in col + 1 until n) {
                val fct = a[r * n + col] / d
                if (fct == 0.0) continue
                for (c in col until n) a[r * n + c] -= fct * a[col * n + c]
                b[r] -= fct * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r * n + c] * x[c]
            x[r] = s / a[r * n + r]
        }
        return x
    }

    // ------------------------------------------------------------------ pose recovery

    /** Closed-form pose from ground points (Z = 0): rays m ~ H [X, Y, 1], H = [r1 r2 t]. */
    fun dltPose(pts: Points, f: Double, cx: Double, cy: Double, k: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = pts.size
        val ata = DoubleArray(81)
        val m = DoubleArray(3)
        val row = DoubleArray(9)
        val mx = DoubleArray(3 * n)
        for (i in 0 until n) {
            kbUnproject(pts.uv[2 * i], pts.uv[2 * i + 1], f, cx, cy, k, m)
            m.copyInto(mx, 3 * i)
            val p0 = pts.xyz[3 * i]
            val p1 = pts.xyz[3 * i + 1]
            val p = doubleArrayOf(p0, p1, 1.0)
            for (pass in 0 until 2) {
                if (pass == 0) {
                    for (j in 0 until 3) {
                        row[j] = 0.0
                        row[3 + j] = -m[2] * p[j]
                        row[6 + j] = m[1] * p[j]
                    }
                } else {
                    for (j in 0 until 3) {
                        row[j] = m[2] * p[j]
                        row[3 + j] = 0.0
                        row[6 + j] = -m[0] * p[j]
                    }
                }
                for (a in 0 until 9) for (c in 0 until 9) ata[a * 9 + c] += row[a] * row[c]
            }
        }
        val (w, v) = jacobiEigSym(ata, 9)
        var best = 0
        for (i in 1 until 9) if (w[i] < w[best]) best = i
        val h = DoubleArray(9) { v[it * 9 + best] }
        var dot = 0.0
        for (i in 0 until n) {
            val p0 = pts.xyz[3 * i]
            val p1 = pts.xyz[3 * i + 1]
            for (r in 0 until 3) dot += (h[r * 3] * p0 + h[r * 3 + 1] * p1 + h[r * 3 + 2]) * mx[3 * i + r]
        }
        if (dot / n < 0) for (i in 0 until 9) h[i] = -h[i]
        val n0 = sqrt(h[0] * h[0] + h[3] * h[3] + h[6] * h[6])
        val n1 = sqrt(h[1] * h[1] + h[4] * h[4] + h[7] * h[7])
        val s = 2.0 / (n0 + n1)
        val r1 = doubleArrayOf(s * h[0], s * h[3], s * h[6])
        val r2 = doubleArrayOf(s * h[1], s * h[4], s * h[7])
        val t = doubleArrayOf(s * h[2], s * h[5], s * h[8])
        val r3 = doubleArrayOf(r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0])
        val r = DoubleArray(9) { i ->
            when (i % 3) {
                0 -> r1[i / 3]
                1 -> r2[i / 3]
                else -> r3[i / 3]
            }
        }
        return orthonormalize(r) to t
    }

    /** Levenberg-Marquardt on [dw(3), t(3), f, cx, cy] (fx = fy = f, k fixed). R = R0 * exp(dw). */
    fun refine(pts: Points, r0: DoubleArray, t0: DoubleArray, f0: Double, cx0: Double, cy0: Double, k: DoubleArray, iters: Int = 15): Fit {
        val n = pts.size
        val proj = DoubleArray(2)
        fun resid(q: DoubleArray, out: DoubleArray) {
            val r = mul3(r0, rodrigues(q[0], q[1], q[2]))
            for (i in 0 until n) {
                val x = pts.xyz[3 * i]
                val y = pts.xyz[3 * i + 1]
                val z = pts.xyz[3 * i + 2]
                kbProject(
                    r[0] * x + r[1] * y + r[2] * z + q[3],
                    r[3] * x + r[4] * y + r[5] * z + q[4],
                    r[6] * x + r[7] * y + r[8] * z + q[5],
                    q[6], q[7], q[8], k, proj
                )
                out[2 * i] = proj[0] - pts.uv[2 * i]
                out[2 * i + 1] = proj[1] - pts.uv[2 * i + 1]
            }
        }
        val np = 9
        val m = 2 * n
        var q = doubleArrayOf(0.0, 0.0, 0.0, t0[0], t0[1], t0[2], f0, cx0, cy0)
        var lam = 1e-3
        var r = DoubleArray(m).also { resid(q, it) }
        var cost = r.sumOf { it * it }
        val jac = DoubleArray(m * np)
        val plus = DoubleArray(m)
        val minus = DoubleArray(m)
        val rn = DoubleArray(m)
        for (iter in 0 until iters) {
            for (j in 0 until np) {
                val h = if (j < 6) 1e-6 else 1e-4
                val qp = q.copyOf().also { it[j] += h }
                val qm = q.copyOf().also { it[j] -= h }
                resid(qp, plus)
                resid(qm, minus)
                for (i in 0 until m) jac[i * np + j] = (plus[i] - minus[i]) / (2 * h)
            }
            val g = DoubleArray(np)
            val nn = DoubleArray(np * np)
            for (i in 0 until m) {
                val ri = r[i]
                for (a in 0 until np) {
                    val ja = jac[i * np + a]
                    g[a] += ja * ri
                    for (c in 0 until np) nn[a * np + c] += ja * jac[i * np + c]
                }
            }
            var step: DoubleArray
            while (true) {
                val lhs = DoubleArray(np * np) { nn[it] + if (it / np == it % np) lam * nn[it] else 0.0 }
                step = solve(lhs, DoubleArray(np) { -g[it] }, np)
                val qn = DoubleArray(np) { q[it] + step[it] }
                resid(qn, rn)
                val cn = rn.sumOf { it * it }
                if (cn < cost) {
                    q = qn
                    r = rn.copyOf()
                    cost = cn
                    lam = max(lam / 10, 1e-9)
                    break
                }
                lam *= 10
                if (lam > 1e8) break
            }
            if (step.maxOf { abs(it) } < 1e-10) break
        }
        var sum2 = 0.0
        var maxErr = 0.0
        for (i in 0 until n) {
            val e = hypot(r[2 * i], r[2 * i + 1])
            sum2 += e * e
            if (e > maxErr || e.isNaN()) maxErr = e
        }
        return Fit(
            r = mul3(r0, rodrigues(q[0], q[1], q[2])),
            t = doubleArrayOf(q[3], q[4], q[5]),
            f = q[6],
            cx = q[7],
            cy = q[8],
            rms = sqrt(sum2 / n),
            maxErr = maxErr,
            n = n
        )
    }

    /** Fit of one camera, null when there are too few points. */
    fun calibrate(pts: Points): Fit? {
        if (pts.size < MIN_FIT_POINTS) return null
        val (r, t) = dltPose(pts, F_INIT, CX_INIT, CY_INIT, K_REF)
        return refine(pts, r, t, F_INIT, CX_INIT, CY_INIT, K_REF)
    }

    // ------------------------------------------------------------------ car-terms views

    /**
     * Virtual camera -> car rotation. [headingDeg]: from +Y (forward), +90 = right, 180 = back;
     * [downDeg]: below the horizon; [rollDeg]: protocol Rz.
     */
    fun viewMatrix(headingDeg: Double, downDeg: Double, rollDeg: Double = 0.0): DoubleArray {
        val h = Math.toRadians(headingDeg)
        val d = Math.toRadians(downDeg)
        val z = doubleArrayOf(sin(h) * cos(d), cos(h) * cos(d), -sin(d))
        val x = doubleArrayOf(cos(h), -sin(h), 0.0)
        val y = doubleArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
        val cols = DoubleArray(9) { i ->
            when (i % 3) {
                0 -> x[i / 3]
                1 -> y[i / 3]
                else -> z[i / 3]
            }
        }
        return mul3(cols, rodrigues(0.0, 0.0, Math.toRadians(rollDeg)))
    }

    /** Physical camera orientation (car -> camera [rWc]) as (heading, down, roll) degrees. */
    fun camCarTerms(rWc: DoubleArray): DoubleArray {
        // Columns of R^T = rows of R.
        val z = doubleArrayOf(rWc[6], rWc[7], rWc[8])
        val x = doubleArrayOf(rWc[0], rWc[1], rWc[2])
        val heading = Math.toDegrees(atan2(z[0], z[1]))
        val down = Math.toDegrees(asin((-z[2]).coerceIn(-1.0, 1.0)))
        val v0 = viewMatrix(heading, down)
        val dotY = x[0] * v0[1] + x[1] * v0[4] + x[2] * v0[7]
        val dotX = x[0] * v0[0] + x[1] * v0[3] + x[2] * v0[6]
        return doubleArrayOf(heading, down, Math.toDegrees(atan2(dotY, dotX)))
    }

    /** CAMCFG v2 (yaw, pitch, roll) degrees: d_phys = Ry(yaw) Rx(pitch) Rz(roll) d_virtual. */
    fun protocolYpr(rWc: DoubleArray, headingDeg: Double, downDeg: Double, rollDeg: Double): DoubleArray {
        val m = mul3(rWc, viewMatrix(headingDeg, downDeg, rollDeg))
        val pitch = asin((-m[5]).coerceIn(-1.0, 1.0))
        val yaw = atan2(m[2], m[8])
        val roll = atan2(m[3], m[4])
        return doubleArrayOf(Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll))
    }

    /** Angle between the view's centre ray and the physical optical axis, degrees. */
    fun axisAngleDeg(rWc: DoubleArray, headingDeg: Double, downDeg: Double): Double {
        val m = mul3(rWc, viewMatrix(headingDeg, downDeg))
        return Math.toDegrees(acos(m[8].coerceIn(-1.0, 1.0)))
    }

    /** R = Ry(yaw) Rx(pitch) Rz(roll), degrees. */
    fun yprMatrix(yawDeg: Double, pitchDeg: Double, rollDeg: Double): DoubleArray {
        val a = Math.toRadians(yawDeg)
        val b = Math.toRadians(pitchDeg)
        val c = Math.toRadians(rollDeg)
        val ry = doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(b), -sin(b), 0.0, sin(b), cos(b))
        val rz = doubleArrayOf(cos(c), -sin(c), 0.0, sin(c), cos(c), 0.0, 0.0, 0.0, 1.0)
        return mul3(mul3(ry, rx), rz)
    }

    // ------------------------------------------------------------------ sanity checks

    enum class Camera(val tag: Int, val nominalHeading: Double, val nominalDown: Double) {
        LEFT(TAG_LEFT, -90.0, 45.0),
        RIGHT(TAG_RIGHT, 90.0, 45.0),
        FRONT(TAG_FRONT, 0.0, 32.0),
        REAR(TAG_REAR, 180.0, 34.0)
    }

    /** "" when the fit is plausible, else the reason (the camera then gets no correction). */
    fun sanity(camera: Camera, fit: Fit): String {
        val (hd, dn, rl) = fit.carTerms.let { Triple(it[0], it[1], it[2]) }
        val c = fit.centre
        if (fit.n < MIN_SANE_POINTS) return "too few points"
        if (!(fit.rms < 1.5 && fit.maxErr < 6)) return "residual rms %.2f max %.2f".format(fit.rms, fit.maxErr)
        if (!(fit.f > 300 && fit.f < 360 && fit.cx > 600 && fit.cx < 680 && fit.cy > 370 && fit.cy < 430)) {
            return "lens out of range"
        }
        if (!(c[2] > 0.5 && c[2] < 1.6)) return "camera height %.2f".format(c[2])
        if (camera == Camera.LEFT && !(c[0] < -0.5)) return "left cam not on left"
        if (camera == Camera.RIGHT && !(c[0] > 0.5)) return "right cam not on right"
        if (camera == Camera.REAR && !(c[1] < -2.5)) return "rear cam not at rear"
        val dh = floorMod(hd - camera.nominalHeading + 180, 360.0) - 180
        if (!(abs(dh) <= 25 && abs(dn - camera.nominalDown) <= 20 && abs(rl) <= 10)) return "orientation implausible"
        return ""
    }

    /** Python's % for floats. */
    private fun floorMod(a: Double, m: Double): Double = a - m * floor(a / m)

    /** Per camera: the fit when it passed [sanity], else the reason it was rejected. */
    class CameraResult(val fit: Fit?, val reason: String)

    fun calibrateAll(points: Map<Int, Points>, cameras: List<Camera>): Map<Camera, CameraResult> =
        cameras.associateWith { camera ->
            try {
                val pts = points[camera.tag]
                val fit = pts?.let { calibrate(it) }
                if (fit == null) {
                    CameraResult(null, "only ${pts?.size ?: 0} points")
                } else {
                    val reason = sanity(camera, fit)
                    CameraResult(if (reason.isEmpty()) fit else null, reason)
                }
            } catch (e: Exception) {
                CameraResult(null, e.message ?: e.javaClass.simpleName)
            }
        }
}
