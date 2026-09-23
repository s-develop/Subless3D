package com.example.subless3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.math.sqrt

/**
 * Renders the current shader into PBR-style texture maps and writes them
 * to the selected directory.
 *
 * Files written:
 *   subless_albedo_<res>.png  — the shader output as-is
 *   subless_normal_<res>.png  — a normal map derived from the luminance
 */
suspend fun bakeShader(state: AppState): Result<Unit> = withContext(Dispatchers.IO) {
    val dirPath = state.saveDirectory
        ?: return@withContext Result.failure(
            IllegalStateException("No output folder selected")
        )

    val outDir = File(dirPath)
    if (!outDir.isDirectory) {
        return@withContext Result.failure(
            IllegalStateException("Output folder does not exist: $dirPath")
        )
    }

    val resolution = state.bakeResolution
    val shader = state.appliedShader

    try {
        val image = renderShaderToImage(shader, resolution)

        val albedoFile = File(outDir, "subless_albedo_${resolution}.png")
        val pngBytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: return@withContext Result.failure(
                IllegalStateException("PNG encoding failed (albedo)")
            )
        albedoFile.writeBytes(pngBytes)

        writeNormalMap(image, resolution, File(outDir, "subless_normal_${resolution}.png"))

        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

/**
 * Builds a tangent-space normal map from the luminance of [source].
 * Simple sobel-style gradient, output encoded as RGB = (x, y, z) * 0.5 + 0.5.
 */
private fun writeNormalMap(source: Image, resolution: Int, outFile: File) {
    val srcPixmap = source.peekPixels()
        ?: error("Could not read pixels from source image")

    val width = resolution
    val height = resolution
    val strength = 2.0f
    val pixels = ByteArray(width * height * 4)

    fun lumAt(xx: Int, yy: Int): Float {
        val x = xx.coerceIn(0, width - 1)
        val y = yy.coerceIn(0, height - 1)
        val px = srcPixmap.getColor(x, y)
        val r = (px shr 16 and 0xFF) / 255f
        val g = (px shr 8 and 0xFF) / 255f
        val b = (px and 0xFF) / 255f
        return (r + g + b) / 3f
    }

    for (y in 0 until height) {
        for (x in 0 until width) {
            val dx = (lumAt(x + 1, y) - lumAt(x - 1, y)) * strength
            val dy = (lumAt(x, y + 1) - lumAt(x, y - 1)) * strength
            val len = sqrt(dx * dx + dy * dy + 1f)

            val nx = ((-dx / len) * 0.5f + 0.5f)
            val ny = ((-dy / len) * 0.5f + 0.5f)
            val nz = ((1f / len) * 0.5f + 0.5f)

            val i = (y * width + x) * 4
            pixels[i]     = (nx * 255f).toInt().coerceIn(0, 255).toByte()
            pixels[i + 1] = (ny * 255f).toInt().coerceIn(0, 255).toByte()
            pixels[i + 2] = (nz * 255f).toInt().coerceIn(0, 255).toByte()
            pixels[i + 3] = 255.toByte()
        }
    }

    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val bmp = Bitmap().apply { installPixels(info, pixels, width * 4) }
    val img = Image.makeFromBitmap(bmp)
    outFile.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
}