package com.example.subless3d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Single source of truth for the entire desktop window.
 *
 * Hoisted so that:
 *   - UI recomposition does not rebuild the renderer,
 *   - the bake pipeline reads exactly the same values as the preview.
 */
class AppState {

    /** Text currently in the editor (draft). */
    var shaderCode by mutableStateOf(DEFAULT_SHADER)

    /** Text last pushed to the preview via the "Update" button. */
    var appliedShader by mutableStateOf(DEFAULT_SHADER)

    /** Currently selected primitive. */
    var selectedShape by mutableStateOf(ShapeType.ROUNDED_BOX)

    /** Bake resolution in pixels (square). */
    var bakeResolution by mutableStateOf(1024)

    /** Absolute path of the output directory. Null until picked. */
    var saveDirectory by mutableStateOf<String?>(null)

    /** If true, a wireframe overlay is drawn on top of the shaded mesh. */
    var showWireframe by mutableStateOf(false)

    /** Short status string for the last bake. */
    var bakeStatus by mutableStateOf<String?>(null)

    companion object {
        /** Resolutions offered in the dropdown. 256 low, 4096/8192 high. */
        val BAKE_RESOLUTIONS = listOf(256, 512, 1024, 2048, 4096, 8192)
    }
}

val DEFAULT_SHADER = """
uniform float2 resolution;
uniform float time;
 
float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
 
float2 brickPattern(float2 uv, float2 brickSize) {
    float2 row = floor(uv / brickSize); 
    float offset = mod(row.y, 2.0) * 0.5;
    float2 local = uv / brickSize - float2(offset, 0.0);
    float2 cell = floor(local);
    float2 f = fract(local);
    float id = hash21(cell);
    float2 d = min(f, 1.0 - f) * brickSize;
    float edge = min(d.x, d.y);
    return float2(edge, id);
}

half4 main(float2 fragCoord) { 
    float2 uv = fragCoord / resolution.y;
    uv *= 4.0;
    uv.x += time * 0.05;
    float2 brickSize = float2(0.5, 0.22);
    float2 brick = brickPattern(uv, brickSize);
    float edge = brick.x;
    float id = brick.y;
    float3 brickColorA = float3(0.55, 0.18, 0.10);
    float3 brickColorB = float3(0.72, 0.30, 0.14);
    float3 brickColorC = float3(0.40, 0.12, 0.08);
    float3 brickColor = mix(
        mix(brickColorA, brickColorB, id),
        brickColorC,
        fract(id * 7.3)
    );
 
    float3 mortarColor = float3(0.55, 0.52, 0.48);
    float mortarWidth = 0.012;
    float mortar = 1.0 - smoothstep(0.0, mortarWidth, edge);
    float3 col = mix(brickColor, mortarColor, mortar);
    float ao = smoothstep(0.0, 0.05, edge);
    col *= mix(0.6, 1.0, ao);
    float2 grad = float2(-1.0, 1.0);
    float shade = 0.85 + 0.15 * dot(normalize(uv - 0.5 * resolution / resolution.y), grad);
    col *= shade;
    float noise = hash21(floor(uv * 60.0)) * 0.06 - 0.03;
    col += noise;
    col = pow(max(col, float3(0.0)), float3(0.85));
    return half4(half3(col), 1.0);
}
""".trimIndent()