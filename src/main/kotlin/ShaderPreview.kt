package com.example.subless3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.FilterTileMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.jetbrains.skia.PaintMode

// ============================================================================
//  MESHES
// ============================================================================

internal class Mesh(
    val positions: FloatArray,  // 3 floats per vertex
    val uvs: FloatArray,        // 2 floats per vertex
    val indices: IntArray       // 3 indices per triangle
)

private fun buildCube(): Mesh {
    data class F(val n: FloatArray, val r: FloatArray, val u: FloatArray)
    val faces = listOf(
        F(floatArrayOf(1f, 0f, 0f),  floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f)), // +X
        F(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f),  floatArrayOf(0f, 1f, 0f)), // -X
        F(floatArrayOf(0f, 1f, 0f),  floatArrayOf(1f, 0f, 0f),  floatArrayOf(0f, 0f, -1f)),// +Y
        F(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f),  floatArrayOf(0f, 0f, 1f)), // -Y
        F(floatArrayOf(0f, 0f, 1f),  floatArrayOf(1f, 0f, 0f),  floatArrayOf(0f, 1f, 0f)), // +Z
        F(floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))  // -Z
    )

    val pos = mutableListOf<Float>()
    val uvs = mutableListOf<Float>()
    val idx = mutableListOf<Int>()

    for (f in faces) {
        val base = pos.size / 3
        // Углы грани в локальных координатах (right, up)
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
        for ((a, b) in corners) {
            val px = f.n[0] + f.r[0] * a + f.u[0] * b
            val py = f.n[1] + f.r[1] * a + f.u[1] * b
            val pz = f.n[2] + f.r[2] * a + f.u[2] * b
            pos.add(px); pos.add(py); pos.add(pz)
            // UV из box-проекции по нормали грани
            val (uu, vv) = toUV01(boxProject(px, py, pz, f.n))
            uvs.add(uu); uvs.add(vv)
        }
        idx.add(base); idx.add(base + 1); idx.add(base + 2)
        idx.add(base); idx.add(base + 2); idx.add(base + 3)
    }
    return Mesh(pos.toFloatArray(), uvs.toFloatArray(), idx.toIntArray())
}

private fun buildPlane(): Mesh {
    // Two-sided plane: two coincident quads with opposite winding.
    // The renderer culls back faces, so we duplicate the geometry and flip
    // the index order. From above only the "front" set survives culling;
    // from below only the "back" set does. No z-fighting because only one
    // set is ever drawn per pixel.
    val verts = listOf(
        Triple(-1f, 0f, 1f) to (0f to 1f),
        Triple(1f, 0f, 1f) to (1f to 1f),
        Triple(1f, 0f, -1f) to (1f to 0f),
        Triple(-1f, 0f, -1f) to (0f to 0f)
    )
    val pos = mutableListOf<Float>()
    val uvs = mutableListOf<Float>()
    for ((p, uv) in verts) {
        pos.add(p.first); pos.add(p.second); pos.add(p.third)
        uvs.add(uv.first); uvs.add(uv.second)
    }
    val indices = intArrayOf(
        0, 1, 2, 0, 2, 3,   // front (as before)
        0, 2, 1, 0, 3, 2    // back  (reversed winding)
    )
    return Mesh(pos.toFloatArray(), uvs.toFloatArray(), indices)
}

private fun buildSphere(segments: Int = 48, rings: Int = 24): Mesh {
    val pos = mutableListOf<Float>()
    val uvs = mutableListOf<Float>()
    val idx = mutableListOf<Int>()

    for (r in 0..rings) {
        val theta = r.toFloat() / rings * PI.toFloat()
        val sinT = sin(theta); val cosT = cos(theta)
        for (s in 0..segments) {
            val phi = s.toFloat() / segments * 2f * PI.toFloat()
            pos.add(sinT * cos(phi)); pos.add(cosT); pos.add(sinT * sin(phi))
            uvs.add(s.toFloat() / segments); uvs.add(r.toFloat() / rings)
        }
    }

    for (r in 0 until rings) {
        for (s in 0 until segments) {
            val a = r * (segments + 1) + s
            val b = a + segments + 1
            if (r != 0) { idx.add(a); idx.add(b); idx.add(a + 1) }
            if (r != rings - 1) { idx.add(a + 1); idx.add(b); idx.add(b + 1) }
        }
    }
    return Mesh(pos.toFloatArray(), uvs.toFloatArray(), idx.toIntArray())
}

/**
 * Signed distance to a rounded axis-aligned box.
 *   p        — sample position
 *   halfExt  — half-extent of the box (same for all axes)
 *   r        — corner/edge rounding radius, must be < halfExt
 */
private fun sdRoundBox(px: Float, py: Float, pz: Float, halfExt: Float, r: Float): Float {
    val qx = kotlin.math.abs(px) - halfExt + r
    val qy = kotlin.math.abs(py) - halfExt + r
    val qz = kotlin.math.abs(pz) - halfExt + r
    val mx = maxOf(qx, 0f); val my = maxOf(qy, 0f); val mz = maxOf(qz, 0f)
    val outside = sqrt(mx * mx + my * my + mz * mz)
    val inside = minOf(maxOf(qx, qy, qz), 0f)
    return outside + inside - r
}

/**
 * For a given direction (x, y, z) from the origin, finds the surface point of
 * the rounded box of half-extent 1 and radius `rounding` along that ray.
 * Uses bisection between t = 0 (inside) and t = 2 (outside).
 */
private fun projectToRoundedBox(x: Float, y: Float, z: Float, rounding: Float): FloatArray {
    val len = sqrt(x * x + y * y + z * z)
    if (len < 1e-6f) return floatArrayOf(0f, 0f, 0f)
    val dx = x / len; val dy = y / len; val dz = z / len

    var tLo = 0f
    var tHi = 2f
    repeat(24) {
        val tMid = (tLo + tHi) * 0.5f
        val sd = sdRoundBox(tMid * dx, tMid * dy, tMid * dz, 1f, rounding)
        if (sd < 0f) tLo = tMid else tHi = tMid
    }
    val t = (tLo + tHi) * 0.5f
    return floatArrayOf(t * dx, t * dy, t * dz)
}

/**
 * Builds a rounded box mesh.
 *
 * Starts from a subdivided cube (with per-face UVs), then pushes every vertex
 * onto the surface of a rounded box along its radial direction. As a result
 * every exterior edge becomes a cylinder segment and every corner becomes a
 * spherical patch — a true "rounded box", not a plain cube.
 */
/**
 * Builds a rounded box mesh.
 *
 * Starts from a subdivided cube (with per-face UVs of the subdivided grid),
 * then pushes every vertex onto the surface of a rounded box along its radial
 * direction. Every exterior edge becomes a cylindrical fillet, every corner a
 * spherical patch.
 *
 * UVs are taken from a spherical projection of the *final* position, so the
 * shader spans the whole primitive continuously across all rounded faces.
 */
private fun buildRoundedCube(subdivisions: Int = 12, rounding: Float = 0.22f): Mesh {
    data class Face(val normal: FloatArray, val right: FloatArray, val up: FloatArray)

    val faces = listOf(
        Face(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f)),
        Face(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f)),
        Face(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f)),
        Face(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
        Face(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
        Face(floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
    )

    val n = subdivisions
    val pos = mutableListOf<Float>()
    val uvs = mutableListOf<Float>()
    val idx = mutableListOf<Int>()

    for (face in faces) {
        val base = pos.size / 3
        for (j in 0..n) {
            val v = j.toFloat() / n * 2f - 1f
            for (i in 0..n) {
                val u = i.toFloat() / n * 2f - 1f
                val px = face.normal[0] + face.right[0] * u + face.up[0] * v
                val py = face.normal[1] + face.right[1] * u + face.up[1] * v
                val pz = face.normal[2] + face.right[2] * u + face.up[2] * v
                val rp = projectToRoundedBox(px, py, pz, rounding)
                pos.add(rp[0]); pos.add(rp[1]); pos.add(rp[2])
                // UV from spherical projection of the *rounded* position.
                val (uu, vv) = toUV01(boxProject(rp[0], rp[1], rp[2], face.normal))
                uvs.add(uu); uvs.add(vv)
            }
        }
        for (j in 0 until n) {
            for (i in 0 until n) {
                val a = base + j * (n + 1) + i
                val b = a + 1
                val c = a + (n + 1)
                val d = c + 1
                idx.add(a); idx.add(b); idx.add(c)
                idx.add(b); idx.add(d); idx.add(c)
            }
        }
    }
    return Mesh(pos.toFloatArray(), uvs.toFloatArray(), idx.toIntArray())
}

internal fun meshFor(shape: ShapeType): Mesh = when (shape) {
    ShapeType.CUBE -> buildCube()
    ShapeType.SPHERE -> buildSphere()
    ShapeType.PLANE -> buildPlane()
    ShapeType.ROUNDED_BOX -> buildRoundedCube()
}

// ============================================================================
//  PREVIEW COMPOSABLE
// ============================================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShaderPreview(
    shaderCode: String,
    shape: ShapeType,
    showWireframe: Boolean,
    modifier: Modifier = Modifier
) {
    var rotX by remember { mutableStateOf(15f) }
    var rotY by remember { mutableStateOf(30f) }
    var zoom by remember { mutableStateOf(1f) }
    var autoRotate by remember { mutableStateOf(true) }

    var shaderImage by remember { mutableStateOf<Image?>(null) }
    var compileError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shaderCode) {
        try {
            val img = withContext(Dispatchers.Default) {
                renderShaderToImage(shaderCode, 1024)
            }
            shaderImage = img
            compileError = null
        } catch (e: Throwable) {
            shaderImage = null
            compileError = e.message ?: "Shader compile error"
        }
    }

    LaunchedEffect(autoRotate) {
        while (autoRotate) {
            rotY = (rotY + 0.5f) % 360f
            delay(16)
        }
    }

    val mesh = remember(shape) { meshFor(shape) }

    Box(
        modifier
            .background(Color(0xFF1E1E1E))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    autoRotate = false
                    rotY = (rotY + pan.x * 0.5f) % 360f
                    rotX = (rotX + pan.y * 0.5f).coerceIn(-89f, 89f)
                    zoom = (zoom * zoomChange).coerceIn(0.3f, 4f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { autoRotate = !autoRotate })
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) {
                    autoRotate = false
                    zoom = (zoom * (1f - dy * 0.1f)).coerceIn(0.3f, 4f)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val img = shaderImage ?: return@Canvas
            drawTexturedMesh(mesh, rotX, rotY, zoom, img, showWireframe)
        }

        compileError?.let { msg ->
            Text(
                text = "Shader error:\n$msg",
                color = Color(0xFFFF6666),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            )
        }

        Text(
            text = "drag — rotate • wheel — zoom • double-click — toggle auto-rotate",
            color = Color(0xFF888888),
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        )
    }
}

// ============================================================================
//  MESH RASTERIZATION
// ============================================================================

private fun DrawScope.drawTexturedMesh(
    mesh: Mesh,
    rotXDeg: Float,
    rotYDeg: Float,
    zoom: Float,
    image: Image,
    showWireframe: Boolean
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val scale = min(size.width, size.height) * 0.35f * zoom
    val camZ = 3f

    val rx = rotXDeg * PI.toFloat() / 180f
    val ry = rotYDeg * PI.toFloat() / 180f
    val cosY = cos(ry); val sinY = sin(ry)
    val cosX = cos(rx); val sinX = sin(rx)

    val vertexCount = mesh.positions.size / 3
    val depth = FloatArray(vertexCount)
    val scrX = FloatArray(vertexCount)
    val scrY = FloatArray(vertexCount)

    for (i in 0 until vertexCount) {
        val x = mesh.positions[i * 3]
        val y = mesh.positions[i * 3 + 1]
        val z = mesh.positions[i * 3 + 2]

        val x1 = x * cosY + z * sinY
        val z1 = -x * sinY + z * cosY
        val y2 = y * cosX - z1 * sinX
        val z2 = y * sinX + z1 * cosX

        val d = camZ - z2
        depth[i] = d
        scrX[i] = cx + x1 * scale / d
        scrY[i] = cy + y2 * scale / d
    }

    // Painter's-algorithm sort: draw farthest triangle first.
    val triCount = mesh.indices.size / 3
    val order = IntArray(triCount) { it }
    val centroidDepth = FloatArray(triCount)
    for (t in 0 until triCount) {
        val i0 = mesh.indices[t * 3]
        val i1 = mesh.indices[t * 3 + 1]
        val i2 = mesh.indices[t * 3 + 2]
        centroidDepth[t] = (depth[i0] + depth[i1] + depth[i2]) / 3f
    }
    for (a in 1 until triCount) {
        val key = order[a]
        val kd = centroidDepth[key]
        var b = a - 1
        while (b >= 0 && centroidDepth[order[b]] < kd) {
            order[b + 1] = order[b]
            b--
        }
        order[b + 1] = key
    }

    val skia = drawContext.canvas.nativeCanvas
    val imgW = image.width.toFloat()
    val imgH = image.height.toFloat()
    val wirePaint = Paint().apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = 1f
        isAntiAlias = true
        mode = PaintMode.STROKE   // было Paint.Mode.STROKE
    }

    for (t in order) {
        val i0 = mesh.indices[t * 3]
        val i1 = mesh.indices[t * 3 + 1]
        val i2 = mesh.indices[t * 3 + 2]

        val x0 = scrX[i0]; val y0 = scrY[i0]
        val x1 = scrX[i1]; val y1 = scrY[i1]
        val x2 = scrX[i2]; val y2 = scrY[i2]

        // Back-face culling. In our screen space (y down) a front-facing
        // triangle has a positive signed area. Culling back faces eliminates
        // "interior" triangles that would otherwise bleed through the surface.
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (area <= 0.5f) continue

        val d0 = depth[i0]; val d1 = depth[i1]; val d2 = depth[i2]

        val u0 = mesh.uvs[i0 * 2] * imgW; val v0 = mesh.uvs[i0 * 2 + 1] * imgH
        val u1 = mesh.uvs[i1 * 2] * imgW; val v1 = mesh.uvs[i1 * 2 + 1] * imgH
        val u2 = mesh.uvs[i2 * 2] * imgW; val v2 = mesh.uvs[i2 * 2 + 1] * imgH

        // ---- FIX: perspective-correct texture mapping --------------------
        // The previous version used an affine matrix per triangle, which made
        // the texture visibly "float" / warp as the object rotated, because
        // affine interpolation does not match a perspective projection. We
        // now solve a full 3x3 projective matrix per triangle.
        val matrix = computeProjectiveMatrix(
            u0, v0, x0, y0, d0,
            u1, v1, x1, y1, d1,
            u2, v2, x2, y2, d2
        ) ?: computeAffineMatrix(
            floatArrayOf(u0, v0, u1, v1, u2, v2),
            floatArrayOf(x0, y0, x1, y1, x2, y2)
        )
        if (matrix == null) continue

        val shader = try {
            image.makeShader(
                FilterTileMode.CLAMP,
                FilterTileMode.CLAMP,
                SamplingMode.LINEAR,
                matrix
            )
        } catch (_: Throwable) { continue }

        val paint = Paint().apply {
            this.shader = shader
            isAntiAlias = true
        }

        val path = Path().apply {
            moveTo(x0, y0); lineTo(x1, y1); lineTo(x2, y2); closePath()
        }
        skia.drawPath(path, paint)

        // Optional wireframe overlay.
        if (showWireframe) {
            skia.drawPath(path, wirePaint)
        }
    }
}

/**
 * Solves the projective matrix M such that
 *     M * [u_i, v_i, 1]^T = w_i * [x_i, y_i, 1]^T
 * where w_i = 1 / depth_i.
 *
 * This gives true perspective-correct texturing: the texture "sticks" to the
 * surface instead of sliding as the object rotates.
 *
 * Returns null if the triangle is degenerate or if any depth is non-positive.
 */
private fun computeProjectiveMatrix(
    u0: Float, v0: Float, x0: Float, y0: Float, d0: Float,
    u1: Float, v1: Float, x1: Float, y1: Float, d1: Float,
    u2: Float, v2: Float, x2: Float, y2: Float, d2: Float
): Matrix33? {
    if (d0 <= 1e-4f || d1 <= 1e-4f || d2 <= 1e-4f) return null

    val det = u0 * (v1 - v2) - v0 * (u1 - u2) + (u1 * v2 - u2 * v1)
    if (kotlin.math.abs(det) < 1e-6f) return null
    val invDet = 1f / det

    // A⁻¹ (row-major).
    val a00 = (v1 - v2) * invDet
    val a01 = (v2 - v0) * invDet
    val a02 = (v0 - v1) * invDet
    val a10 = (u2 - u1) * invDet
    val a11 = (u0 - u2) * invDet
    val a12 = (u1 - u0) * invDet
    val a20 = (u1 * v2 - u2 * v1) * invDet
    val a21 = (u2 * v0 - u0 * v2) * invDet
    val a22 = (u0 * v1 - u1 * v0) * invDet

    // B[i][k]: row = vertex, col = output (x/d, y/d, 1/d).
    val b00 = x0 / d0; val b01 = y0 / d0; val b02 = 1f / d0
    val b10 = x1 / d1; val b11 = y1 / d1; val b12 = 1f / d1
    val b20 = x2 / d2; val b21 = y2 / d2; val b22 = 1f / d2

    // M[k][j] = Σᵢ A⁻¹[j][i] · B[i][k]
    // В конструкторе Matrix33 порядок: (m00,m01,m02, m10,m11,m12, m20,m21,m22),
    // где m_ij — строка i, столбец j.
    val m00 = a00 * b00 + a01 * b10 + a02 * b20
    val m01 = a10 * b00 + a11 * b10 + a12 * b20
    val m02 = a20 * b00 + a21 * b10 + a22 * b20

    val m10 = a00 * b01 + a01 * b11 + a02 * b21
    val m11 = a10 * b01 + a11 * b11 + a12 * b21
    val m12 = a20 * b01 + a21 * b11 + a22 * b21

    val m20 = a00 * b02 + a01 * b12 + a02 * b22
    val m21 = a10 * b02 + a11 * b12 + a12 * b22
    val m22 = a20 * b02 + a21 * b12 + a22 * b22

    return Matrix33(m00, m01, m02, m10, m11, m12, m20, m21, m22)
}

/**
 * Affine fallback for degenerate cases. Maps (u, v) -> (x, y) exactly at the
 * three vertices; ignores perspective.
 */
private fun computeAffineMatrix(src: FloatArray, dst: FloatArray): Matrix33? {
    val u0 = src[0]; val v0 = src[1]
    val u1 = src[2]; val v1 = src[3]
    val u2 = src[4]; val v2 = src[5]
    val x0 = dst[0]; val y0 = dst[1]
    val x1 = dst[2]; val y1 = dst[3]
    val x2 = dst[4]; val y2 = dst[5]

    val det = u0 * (v1 - v2) - u1 * (v0 - v2) + u2 * (v0 - v1)
    if (kotlin.math.abs(det) < 1e-6f) return null
    val invDet = 1f / det

    val a = (x0 * (v1 - v2) - x1 * (v0 - v2) + x2 * (v0 - v1)) * invDet
    val b = (u0 * (x1 - x2) - u1 * (x0 - x2) + u2 * (x0 - x1)) * invDet
    val c = (u0 * (v1 * x2 - v2 * x1) - u1 * (v0 * x2 - v2 * x0) +
            u2 * (v0 * x1 - v1 * x0)) * invDet
    val d = (y0 * (v1 - v2) - y1 * (v0 - v2) + y2 * (v0 - v1)) * invDet
    val e = (u0 * (y1 - y2) - u1 * (y0 - y2) + u2 * (y0 - y1)) * invDet
    val f = (u0 * (v1 * y2 - v2 * y1) - u1 * (v0 * y2 - v2 * y0) +
            u2 * (v0 * y1 - v1 * y0)) * invDet

    return Matrix33(a, b, c, d, e, f, 0f, 0f, 1f)
}

// ============================================================================
//  SHADER OFFSCREEN RENDER
// ============================================================================

internal fun renderShaderToImage(shaderCode: String, resolution: Int): Image {
    val skSL = glslToSksl(shaderCode)
    println("=== SkSL ===\n$skSL\n============")   // ← ВРЕМЕННО

    val effect = try {
        RuntimeEffect.makeForShader(skSL)
    } catch (e: Throwable) {
        error("SkSL compile error: ${e.message}")
    } ?: error("SkSL compile failed (no details from Skia)")

    val surface = Surface.makeRasterN32Premul(resolution, resolution)
    val canvas = surface.canvas

    val builder = RuntimeShaderBuilder(effect)

    // Skia вырезает неиспользуемые uniforms при компиляции.
    // Попытка установить такой uniform выбрасывает исключение,
    // поэтому каждый вызов — под своим try/catch.
    try {
        builder.uniform("resolution", resolution.toFloat(), resolution.toFloat())
    } catch (_: Throwable) { /* шейдер не использует resolution */ }

    try {
        builder.uniform("time", 0f)
    } catch (_: Throwable) { /* шейдер не использует time */ }

    val shader = builder.makeShader()
    val paint = Paint().apply { this.shader = shader }
    canvas.drawRect(Rect(0f, 0f, resolution.toFloat(), resolution.toFloat()), paint)

    return surface.makeImageSnapshot()
}

/**
 * Equirectangular UV from an object-space position.
 *
 * u — longitude in [0, 1], wrap seam along the +X axis.
 * v — latitude in [0, 1], v = 0 at the south pole (-Y), v = 1 at the north pole (+Y).
 *
 * Because the (u) coordinate wraps, the pattern must tile in u to avoid a
 * visible seam. The bundled caustic shader already tiles, so it works out of
 * the box.
 */
private fun sphericalUV(x: Float, y: Float, z: Float): Pair<Float, Float> {
    val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
    val nx = x / len
    val ny = y / len
    val nz = z / len
    val u = (kotlin.math.atan2(nz, nx) / (2.0 * PI).toFloat()) + 0.5f
    val v = (kotlin.math.acos(ny.coerceIn(-1f, 1f)) / PI.toFloat())
    return u to v
}

/**
 * Box-проекция: для точки (x, y, z) на грани с внешней нормалью face
 * возвращает UV в диапазоне [-1, 1] (без нормализации к [0,1]).
 *
 * Знаки подобраны так, чтобы при взгляде снаружи все грани читались
 * не зеркально: right → +u, up → +v.
 */
private fun boxProject(x: Float, y: Float, z: Float, face: FloatArray): Pair<Float, Float> {
    return when {
        face[0] > 0.5f  -> -z to y       // +X
        face[0] < -0.5f ->  z to y       // -X
        face[1] > 0.5f  ->  x to -z      // +Y
        face[1] < -0.5f ->  x to  z      // -Y
        face[2] > 0.5f  ->  x to y       // +Z
        else            -> -x to y       // -Z
    }
}

private fun toUV01(uv: Pair<Float, Float>): Pair<Float, Float> =
    (uv.first * 0.5f + 0.5f) to (uv.second * 0.5f + 0.5f)

private const val ROUND_HELPER = """
float floor_round(float x) { return floor(x + 0.5); }
"""
// ============================================================================
//  GLSL -> SkSL PREPROCESSOR
// ============================================================================

fun glslToSksl(src: String): String {
    if (Regex("""half4\s+main\s*\(\s*float2\s""").containsMatchIn(src)) return src

    // --- 1. Собираем #define NAME value ---
    val defines = mutableMapOf<String, String>()
    for (m in Regex("""^\s*#define\s+(\w+)\s+(.+)$""", RegexOption.MULTILINE).findAll(src)) {
        defines[m.groupValues[1]] = m.groupValues[2].trim()
    }
    // #define без значения (флаг) — просто фиксируем имя
    for (m in Regex("""^\s*#define\s+(\w+)\s*$""", RegexOption.MULTILINE).findAll(src)) {
        defines[m.groupValues[1]] = "1.0"
    }

    // --- 2. Разворачиваем #ifdef / #ifndef / #endif ---
    var s = src
    s = Regex("""#ifdef\s+(\w+)([\s\S]*?)#endif""").replace(s) { m ->
        if (defines.containsKey(m.groupValues[1])) m.groupValues[2] else ""
    }
    s = Regex("""#ifndef\s+(\w+)([\s\S]*?)#endif""").replace(s) { m ->
        if (!defines.containsKey(m.groupValues[1])) m.groupValues[2] else ""
    }

    // --- 3. Убираем сами строки #define ---
    s = s.lines().filterNot { it.trimStart().startsWith("#define") }.joinToString("\n")

    // --- 4. Подставляем значения макросов ---
    // Идём от длинных имён к коротким, чтобы NUM_LIGHTS не съел NUM
    val names = defines.keys.sortedByDescending { it.length }
    for (k in names) {
        val v = defines[k]!!
        s = Regex("""\b${Regex.escape(k)}\b""").replace(s, "($v)")
    }

    // --- 5. Отсекаем то, что принципиально не поддержать ---
    if (Regex("""\biChannel[0-3]\b""").containsMatchIn(s) ||
        Regex("""\btexture\s*\(""").containsMatchIn(s)
    ) {
        error(
            "This shader uses iChannel / texture() input, " +
                    "which is not available in the preview. " +
                    "Only procedural shaders that compute color from fragCoord, " +
                    "resolution and time can be previewed."
        )
    }

    // --- 6. ShaderToy built-in uniforms ---
    s = s.replace(Regex("""\biResolution\b"""), "float3(resolution, 1.0)")
    s = s.replace(Regex("""\biTime\b"""), "time")
    s = s.replace(Regex("""\biMouse\b"""), "float4(0.0)")
    s = s.replace(Regex("""\biFrame\b"""), "0")

    // --- 7. Токен-замены GLSL -> SkSL ---
    s = s
        .replace(Regex("""\bvec2\b"""), "float2")
        .replace(Regex("""\bround\s*\("""), "floor_round(")
        .replace(Regex("""\bvec3\b"""), "float3")
        .replace(Regex("""\bvec4\b"""), "float4")
        .replace(Regex("""\bmat2\b"""), "float2x2")
        .replace(Regex("""\bmat3\b"""), "float3x3")
        .replace(Regex("""\bmat4\b"""), "float4x4")
        .replace(Regex("""\bgl_FragCoord\b"""), "fragCoord")
        .replace(Regex("""\bfract\b"""), "fract")

    // --- 8. Убираем строки, которых в SkSL нет ---
    s = s.lines().filterNot { line ->
        val t = line.trim()
        t.startsWith("precision ") || t.startsWith("attribute ") || t.startsWith("varying ")
    }.joinToString("\n")
    if (s.contains("floor_round(")) {
        s = ROUND_HELPER + "\n" + s
    }
    // --- 9. mainImage / main -> half4 main(float2) ---
    fun makeReturn(expr: String): String {
        val trimmed = expr.trim()
        val isVec4 = Regex("""\b(float4|half4)\s*\(""").containsMatchIn(trimmed)
        return if (isVec4) "return half4($trimmed);"
        else "return half4(half3($trimmed), 1.0);"
    }

    val shadertoyMain = Regex(
        """void\s+mainImage\s*\(\s*out\s+float4\s+(\w+)\s*,\s*in\s+float2\s+(\w+)\s*\)\s*\{"""
    ).find(s)
    if (shadertoyMain != null) {
        val (outName, inName) = shadertoyMain.destructured
        val openBrace = shadertoyMain.range.last
        val closeBrace = findMatchingBrace(s, openBrace) ?: return s
        val inner = s.substring(openBrace + 1, closeBrace)
        val lastAssign = Regex("""\b$outName\s*=\s*([^;]+);""").findAll(inner).lastOrNull()
        val newInner = if (lastAssign != null) {
            inner.substring(0, lastAssign.range.first) +
                    makeReturn(lastAssign.groupValues[1]) +
                    inner.substring(lastAssign.range.last + 1)
        } else inner
        val head = s.substring(0, shadertoyMain.range.first)
        return head + "half4 main(float2 $inName) {" + newInner + "}"
    }

    val classicMain = Regex("""void\s+main\s*\(\s*(?:void)?\s*\)\s*\{""").find(s)
    if (classicMain != null) {
        val openBrace = classicMain.range.last
        val closeBrace = findMatchingBrace(s, openBrace) ?: return s
        val inner = s.substring(openBrace + 1, closeBrace)
        val head = s.substring(0, classicMain.range.first)
        val lastAssign = Regex("""gl_FragColor\s*=\s*([^;]+);""").findAll(inner).lastOrNull()
        val newInner = if (lastAssign != null) {
            inner.substring(0, lastAssign.range.first) +
                    makeReturn(lastAssign.groupValues[1]) +
                    inner.substring(lastAssign.range.last + 1)
        } else inner
        return head + "half4 main(float2 fragCoord) {" + newInner + "}"
    }

    return s
}

private fun findMatchingBrace(src: String, openIndex: Int): Int? {
    var depth = 1
    var i = openIndex + 1
    while (i < src.length) {
        when (src[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
            '/' -> if (i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            }
        }
        i++
    }
    return null
}