/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * Ported from packages/core/src/bg-render/mesh-renderer (index.ts, cp-generate.ts) and img.ts.
 */

package yos.music.player.ui.widgets.effects.mesh

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A triangle mesh: 4 floats per vertex (x, y, u, v) and the triangle indices */
internal class MeshGeometry(val vertices: FloatArray, val indices: ShortArray)

// 4x4 matrices are 16 doubles in column-major order, like gl-matrix

private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(16)
    for (c in 0 until 4) for (r in 0 until 4) {
        var sum = 0.0
        for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
        out[c * 4 + r] = sum
    }
    return out
}

private fun transpose(a: DoubleArray): DoubleArray {
    val out = DoubleArray(16)
    for (c in 0 until 4) for (r in 0 until 4) out[c * 4 + r] = a[r * 4 + c]
    return out
}

private val hermite = doubleArrayOf(2.0, -2.0, 1.0, 1.0, -3.0, 3.0, -2.0, -1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0)
private val hermiteT = transpose(hermite)

private class ControlPoint(val x: Double, val y: Double, val uTanX: Double, val uTanY: Double, val vTanX: Double, val vTanY: Double)

/**
 * Builds the bicubic Hermite patch mesh of a preset.
 * The vertex colors of AMLL are always white, so they are left out.
 */
internal fun buildHermiteMesh(preset: ControlPointPreset, subDivisions: Int): MeshGeometry {
    val n = preset.width
    val m = preset.height
    require(n >= 2 && m >= 2 && subDivisions >= 2)

    val uPower = 2.0 / (n - 1)
    val vPower = 2.0 / (m - 1)
    val points = arrayOfNulls<ControlPoint>(n * m)
    for (y in 0 until m) for (x in 0 until n) {
        points[x + y * n] = ControlPoint(
            (x.toDouble() / (n - 1)) * 2 - 1, (y.toDouble() / (m - 1)) * 2 - 1,
            uPower, 0.0, 0.0, vPower
        )
    }
    for (c in preset.conf) {
        val uRot = c.ur * Math.PI / 180
        val vRot = c.vr * Math.PI / 180
        val uScale = uPower * c.up
        val vScale = vPower * c.vp
        points[c.cx + c.cy * n] = ControlPoint(
            c.x, c.y,
            cos(uRot) * uScale, sin(uRot) * uScale,
            -sin(vRot) * vScale, cos(vRot) * vScale
        )
    }
    fun cp(x: Int, y: Int) = points[x + y * n]!!

    // The vertex grid is indexed (along the control point rows, along the control point columns)
    val vertexWidth = (m - 1) * subDivisions
    val vertexHeight = (n - 1) * subDivisions
    val vertices = FloatArray(vertexWidth * vertexHeight * 4)

    val subDivM1 = subDivisions - 1
    val tW = subDivM1 * (m - 1)
    val tH = subDivM1 * (n - 1)
    val invSubDivM1 = 1.0 / subDivM1
    val invTH = 1.0 / tH
    val invTW = 1.0 / tW
    val powers = DoubleArray(subDivisions * 4)
    for (i in 0 until subDivisions) {
        val norm = i * invSubDivM1
        powers[i * 4] = norm * norm * norm
        powers[i * 4 + 1] = norm * norm
        powers[i * 4 + 2] = norm
        powers[i * 4 + 3] = 1.0
    }

    fun coefficients(p00: ControlPoint, p01: ControlPoint, p10: ControlPoint, p11: ControlPoint, xAxis: Boolean): DoubleArray {
        val l = { p: ControlPoint -> if (xAxis) p.x else p.y }
        val u = { p: ControlPoint -> if (xAxis) p.uTanX else p.uTanY }
        val v = { p: ControlPoint -> if (xAxis) p.vTanX else p.vTanY }
        val raw = DoubleArray(16)
        raw[0] = l(p00); raw[1] = l(p01); raw[2] = v(p00); raw[3] = v(p01)
        raw[4] = l(p10); raw[5] = l(p11); raw[6] = v(p10); raw[7] = v(p11)
        raw[8] = u(p00); raw[9] = u(p01)
        raw[12] = u(p10); raw[13] = u(p11)
        // transpose, then multiply by the Hermite matrix on both sides
        return mul(hermiteT, mul(transpose(raw), hermite))
    }

    for (x in 0 until n - 1) {
        for (y in 0 until m - 1) {
            val p00 = cp(x, y)
            val p01 = cp(x, y + 1)
            val p10 = cp(x + 1, y)
            val p11 = cp(x + 1, y + 1)
            val accX = coefficients(p00, p01, p10, p11, true)
            val accY = coefficients(p00, p01, p10, p11, false)
            val sX = x.toDouble() / (n - 1)
            val sY = y.toDouble() / (m - 1)
            val baseVx = y * subDivisions
            val baseVy = x * subDivisions
            val ux = DoubleArray(4)
            val uy = DoubleArray(4)
            for (u in 0 until subDivisions) {
                for (r in 0 until 4) {
                    var sx = 0.0
                    var sy = 0.0
                    for (k in 0 until 4) {
                        sx += accX[k * 4 + r] * powers[u * 4 + k]
                        sy += accY[k * 4 + r] * powers[u * 4 + k]
                    }
                    ux[r] = sx
                    uy[r] = sy
                }
                for (v in 0 until subDivisions) {
                    var px = 0.0
                    var py = 0.0
                    for (k in 0 until 4) {
                        px += powers[v * 4 + k] * ux[k]
                        py += powers[v * 4 + k] * uy[k]
                    }
                    val index = ((baseVx + u) + (baseVy + v) * vertexWidth) * 4
                    vertices[index] = px.toFloat()
                    vertices[index + 1] = py.toFloat()
                    vertices[index + 2] = (sX + v * invTH).toFloat()
                    vertices[index + 3] = (1 - sY - u * invTW).toFloat()
                }
            }
        }
    }

    val indices = ShortArray((vertexWidth - 1) * (vertexHeight - 1) * 6)
    var i = 0
    for (y in 0 until vertexHeight - 1) {
        for (x in 0 until vertexWidth - 1) {
            val a = y * vertexWidth + x
            val b = a + 1
            val c = (y + 1) * vertexWidth + x
            val d = c + 1
            indices[i++] = a.toShort(); indices[i++] = b.toShort(); indices[i++] = c.toShort()
            indices[i++] = b.toShort(); indices[i++] = d.toShort(); indices[i++] = c.toShort()
        }
    }
    return MeshGeometry(vertices, indices)
}

/** The random preset of AMLL, 20% of the time instead of one of the hand made presets */
internal fun randomControlPointPreset(random: Random = Random.Default): ControlPointPreset =
    if (random.nextDouble() > 0.8) generateControlPoints(6, 6, random)
    else controlPointPresets[random.nextInt(controlPointPresets.size)]

private class Cp(var x: Double, var y: Double, var ur: Double, var vr: Double, var up: Double, var vp: Double)

private fun clamp01(x: Double) = min(max(x, 0.0), 1.0)

private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    val t = clamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3 - 2 * t)
}

private fun fract(x: Double) = x - floor(x)

private fun noise(x: Double, y: Double) = fract(sin(x * 12.9898 + y * 78.233) * 43758.5453)

private fun smoothNoise(x: Double, y: Double): Double {
    val x0 = floor(x)
    val y0 = floor(y)
    val xf = x - x0
    val yf = y - y0
    val u = xf * xf * (3 - 2 * xf)
    val v = yf * yf * (3 - 2 * yf)
    val nx0 = noise(x0, y0) * (1 - u) + noise(x0 + 1, y0) * u
    val nx1 = noise(x0, y0 + 1) * (1 - u) + noise(x0 + 1, y0 + 1) * u
    return nx0 * (1 - v) + nx1 * v
}

private fun noiseGradient(x: Double, y: Double, epsilon: Double = 0.001): Pair<Double, Double> {
    val dx = (smoothNoise(x + epsilon, y) - smoothNoise(x - epsilon, y)) / (2 * epsilon)
    val dy = (smoothNoise(x, y + epsilon) - smoothNoise(x, y - epsilon)) / (2 * epsilon)
    val len = Math.sqrt(dx * dx + dy * dy).let { if (it == 0.0) 1.0 else it }
    return dx / len to dy / len
}

private fun smoothifyControlPoints(grid0: List<MutableList<Cp>>, w: Int, h: Int, iterations: Int, factor: Double, factorModifier: Double): List<MutableList<Cp>> {
    var grid = grid0
    var f = factor
    val kernel = arrayOf(intArrayOf(1, 2, 1), intArrayOf(2, 4, 2), intArrayOf(1, 2, 1))
    repeat(iterations) {
        val next = List(h) { j -> MutableList(w) { i -> grid[j][i] } }
        for (j in 1 until h - 1) for (i in 1 until w - 1) {
            var sx = 0.0; var sy = 0.0; var sur = 0.0; var svr = 0.0; var sup = 0.0; var svp = 0.0
            for (dj in -1..1) for (di in -1..1) {
                val weight = kernel[dj + 1][di + 1]
                val nb = grid[j + dj][i + di]
                sx += nb.x * weight; sy += nb.y * weight
                sur += nb.ur * weight; svr += nb.vr * weight
                sup += nb.up * weight; svp += nb.vp * weight
            }
            val cur = grid[j][i]
            next[j][i] = Cp(
                cur.x * (1 - f) + sx / 16 * f, cur.y * (1 - f) + sy / 16 * f,
                cur.ur * (1 - f) + sur / 16 * f, cur.vr * (1 - f) + svr / 16 * f,
                cur.up * (1 - f) + sup / 16 * f, cur.vp * (1 - f) + svp / 16 * f
            )
        }
        grid = next
        f = clamp01(f + factorModifier)
    }
    return grid
}

/** Random control points on a width x height grid, ported from cp-generate.ts */
internal fun generateControlPoints(width: Int, height: Int, random: Random = Random.Default): ControlPointPreset {
    fun range(min: Double, max: Double) = random.nextDouble() * (max - min) + min
    val variationFraction = range(0.4, 0.6)
    val normalOffset = range(0.3, 0.6)
    val blendFactor = 0.8
    val smoothIterations = floor(range(3.0, 5.0)).toInt()
    val smoothFactor = range(0.2, 0.3)
    val smoothModifier = range(-0.1, -0.05)

    val dx = if (width == 1) 0.0 else 2.0 / (width - 1)
    val dy = if (height == 1) 0.0 else 2.0 / (height - 1)
    val grid = List(height) { MutableList(width) { Cp(0.0, 0.0, 0.0, 0.0, 1.0, 1.0) } }

    for (j in 0 until height) for (i in 0 until width) {
        val baseX = (if (width == 1) 0.0 else i.toDouble() / (width - 1)) * 2 - 1
        val baseY = (if (height == 1) 0.0 else j.toDouble() / (height - 1)) * 2 - 1
        val isBorder = i == 0 || i == width - 1 || j == 0 || j == height - 1
        val pertX = if (isBorder) 0.0 else range(-variationFraction * dx, variationFraction * dx)
        val pertY = if (isBorder) 0.0 else range(-variationFraction * dy, variationFraction * dy)
        var x = baseX + pertX
        var y = baseY + pertY
        val ur = if (isBorder) 0.0 else range(-60.0, 60.0)
        val vr = if (isBorder) 0.0 else range(-60.0, 60.0)
        val up = if (isBorder) 1.0 else range(0.8, 1.2)
        val vp = if (isBorder) 1.0 else range(0.8, 1.2)
        if (!isBorder) {
            val uNorm = (baseX + 1) / 2
            val vNorm = (baseY + 1) / 2
            val (nx, ny) = noiseGradient(uNorm, vNorm)
            val weight = smoothstep(0.0, 1.0, min(min(uNorm, 1 - uNorm), min(vNorm, 1 - vNorm)))
            val offsetX = nx * normalOffset * weight
            val offsetY = ny * normalOffset * weight
            x = x * (1 - blendFactor) + (x + offsetX) * blendFactor
            y = y * (1 - blendFactor) + (y + offsetY) * blendFactor
        }
        grid[j][i] = Cp(x, y, ur, vr, up, vp)
    }

    val smooth = smoothifyControlPoints(grid, width, height, smoothIterations, smoothFactor, smoothModifier)
    val conf = ArrayList<ControlPointConf>(width * height)
    for (j in 0 until height) for (i in 0 until width) {
        val c = smooth[j][i]
        conf.add(ControlPointConf(i, j, c.x, c.y, c.ur, c.vr, c.up, c.vp))
    }
    return ControlPointPreset(width, height, conf)
}

/** What AMLL does to a cover before it becomes the texture: contrast, saturation, brightness, then a blur */
internal object CoverTexture {
    const val SIZE = 32

    /** Mimics a write into a Uint8ClampedArray: clamp, then round half to even */
    private fun toByte(value: Double): Int {
        if (value.isNaN()) return 0
        if (value <= 0) return 0
        if (value >= 255) return 255
        val f = floor(value)
        val diff = value - f
        return when {
            diff < 0.5 -> f.toInt()
            diff > 0.5 -> f.toInt() + 1
            else -> if (f.toInt() % 2 == 0) f.toInt() else f.toInt() + 1
        }
    }

    /** [argb] holds SIZE x SIZE straight (not premultiplied) ARGB pixels, the result has the same layout */
    fun process(argb: IntArray, width: Int = SIZE, height: Int = SIZE): IntArray {
        require(argb.size == width * height)
        // RGBA bytes, like ImageData
        val data = IntArray(argb.size * 4)
        for (i in argb.indices) {
            val c = argb[i]
            var r = ((c shr 16) and 0xFF).toDouble()
            var g = ((c shr 8) and 0xFF).toDouble()
            var b = (c and 0xFF).toDouble()
            r = (r - 128) * 0.4 + 128; g = (g - 128) * 0.4 + 128; b = (b - 128) * 0.4 + 128
            val gray = r * 0.3 + g * 0.59 + b * 0.11
            r = gray * -2.0 + r * 3.0; g = gray * -2.0 + g * 3.0; b = gray * -2.0 + b * 3.0
            r = (r - 128) * 1.7 + 128; g = (g - 128) * 1.7 + 128; b = (b - 128) * 1.7 + 128
            data[i * 4] = toByte(r * 0.75)
            data[i * 4 + 1] = toByte(g * 0.75)
            data[i * 4 + 2] = toByte(b * 0.75)
            data[i * 4 + 3] = (c ushr 24) and 0xFF
        }
        blur(data, width, height, 2, 4)
        return IntArray(argb.size) { i ->
            (data[i * 4 + 3] shl 24) or (data[i * 4] shl 16) or (data[i * 4 + 1] shl 8) or data[i * 4 + 2]
        }
    }

    /** Port of blurImage in img.ts, a stack blur on RGBA bytes */
    private fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, quality: Int) {
        val wm = width - 1
        val hm = height - 1
        val rad1x = radius + 1
        val divx = radius + rad1x
        val rad1y = radius + 1
        val divy = radius + rad1y
        val div2 = 1.0 / (divx * divy)
        val total = width * height
        val r = IntArray(total)
        val g = IntArray(total)
        val b = IntArray(total)
        val a = IntArray(total)
        val vmin = IntArray(max(width, height))
        val vmax = IntArray(max(width, height))
        repeat(quality) {
            var yw = 0
            var yi = 0
            for (y in 0 until height) {
                var rsum = pixels[yw] * rad1x
                var gsum = pixels[yw + 1] * rad1x
                var bsum = pixels[yw + 2] * rad1x
                var asum = pixels[yw + 3] * rad1x
                for (i in 1..radius) {
                    val p = yw + (min(i, wm) shl 2)
                    rsum += pixels[p]; gsum += pixels[p + 1]; bsum += pixels[p + 2]; asum += pixels[p + 3]
                }
                for (x in 0 until width) {
                    r[yi] = rsum; g[yi] = gsum; b[yi] = bsum; a[yi] = asum
                    if (y == 0) {
                        vmin[x] = min(x + rad1x, wm) shl 2
                        vmax[x] = max(x - radius, 0) shl 2
                    }
                    val p1 = yw + vmin[x]
                    val p2 = yw + vmax[x]
                    rsum += pixels[p1] - pixels[p2]
                    gsum += pixels[p1 + 1] - pixels[p2 + 1]
                    bsum += pixels[p1 + 2] - pixels[p2 + 2]
                    asum += pixels[p1 + 3] - pixels[p2 + 3]
                    yi++
                }
                yw += width shl 2
            }
            for (x in 0 until width) {
                var yp = x
                var rsum = r[yp] * rad1y
                var gsum = g[yp] * rad1y
                var bsum = b[yp] * rad1y
                var asum = a[yp] * rad1y
                for (i in 1..radius) {
                    yp += if (i > hm) 0 else width
                    rsum += r[yp]; gsum += g[yp]; bsum += b[yp]; asum += a[yp]
                }
                yi = x shl 2
                for (y in 0 until height) {
                    pixels[yi] = toByte(floor(rsum * div2 + 0.5))
                    pixels[yi + 1] = toByte(floor(gsum * div2 + 0.5))
                    pixels[yi + 2] = toByte(floor(bsum * div2 + 0.5))
                    pixels[yi + 3] = toByte(floor(asum * div2 + 0.5))
                    if (x == 0) {
                        vmin[y] = min(y + rad1y, hm) * width
                        vmax[y] = max(y - radius, 0) * width
                    }
                    val p1 = x + vmin[y]
                    val p2 = x + vmax[y]
                    rsum += r[p1] - r[p2]
                    gsum += g[p1] - g[p2]
                    bsum += b[p1] - b[p2]
                    asum += a[p1] - a[p2]
                    yi += width shl 2
                }
            }
        }
    }
}
