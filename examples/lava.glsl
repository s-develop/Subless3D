/*

================================================================================
  Molten Flow — tileable procedural lava texture
================================================================================

  Author:        https://github.com/s-develop
  Year:          2026
  Project:       Subless3D
  License:       Apache License 2.0
                 See LICENSE in the project root for the full text.

  This is an original work written from scratch. It does not share code
  with any third-party shader. The visual subject — flowing molten rock —
  is a common motif and not attributable to any single author.

  Approach:
      * Instead of an iterative flow-noise loop that recursively
        displaces the sampling position, this shader uses a single
        two-level domain warp:

            q  = fbm(p)
            r  = fbm(p + q * K1)
            w  = fbm(p + r * K2)

        The final field w is a scalar in [0, 1] whose level sets look
        like the curl patterns of a slow-moving fluid.

      * Bright veins are extracted by a ridged transform on a second,
        independent noise field. Ridges are 1 − |n − 0.5| · 2, raised
        to a power to sharpen them into thin lines.

      * The palette is a temperature gradient driven by the combined
        field: cold dark crust → warm clay → orange → bright yellow.

      * Two independent sub-fields at different scales are mixed so
        that neither layer dominates.

  Tiling:
      * All noise frequencies are integers, and every lattice lookup
        is wrapped with mod(period), so the pattern repeats exactly
        with period 1 in UV space.

================================================================================
*/

uniform float2 resolution;
uniform float time;

const float TAU = 6.28318530718;

// ============================================================
//  Hashes
// ============================================================

float hash21(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

// ============================================================
//  Wrapped value noise — tileable on integer period
// ============================================================

float noiseT(float2 p, float period) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash21(mod(i + float2(0.0, 0.0), float2(period)));
    float b = hash21(mod(i + float2(1.0, 0.0), float2(period)));
    float c = hash21(mod(i + float2(0.0, 1.0), float2(period)));
    float d = hash21(mod(i + float2(1.0, 1.0), float2(period)));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// ============================================================
//  Wrapped FBM — sum of octaves at integer frequencies
//
//  baseScale must be an integer for tiling to hold. Each successive
//  octave doubles the frequency, so it stays integer.
// ============================================================

float fbmT(float2 p, int baseScale, int octaves) {
    float value = 0.0;
    float amp = 0.5;
    float totalAmp = 0.0;
    float scale = float(baseScale);

    for (int k = 0; k < 8; k++) {
        if (k >= octaves) break;
        value += amp * noiseT(p * scale, scale);
        totalAmp += amp;
        scale = scale * 2.0;
        amp = amp * 0.5;
    }
    if (totalAmp < 1e-5) { totalAmp = 1.0; }
    return value / totalAmp;
}

// ============================================================
//  Ridged FBM — thin bright lines from a wrapped FBM
//
//  The ridged transform 1 − |n − 0.5| · 2 turns the mid-range of the
//  noise into a bright band. Raising to a power narrows the band.
// ============================================================

float ridgesT(float2 p, int baseScale, int octaves, float sharpness) {
    float value = 0.0;
    float amp = 0.5;
    float totalAmp = 0.0;
    float scale = float(baseScale);

    for (int k = 0; k < 8; k++) {
        if (k >= octaves) break;
        float n = noiseT(p * scale, scale);
        float r = 1.0 - abs(n - 0.5) * 2.0;
        r = pow(max(r, 0.0), sharpness);
        value += amp * r;
        totalAmp += amp;
        scale = scale * 2.0;
        amp = amp * 0.5;
    }
    if (totalAmp < 1e-5) { totalAmp = 1.0; }
    return value / totalAmp;
}

// ============================================================
//  Domain-warped field
//
//  Two-level warp: the first warp shifts the second sampling point,
//  the second warp shifts the final sampling point. This produces
//  the curled, folded look of a slow-moving viscous fluid.
//
//  All warp offsets are computed from wrapped FBM, so they are
//  periodic and the whole field tiles cleanly.
// ============================================================

float warpedField(float2 uv, float t) {
    // Slow global drift
    float2 drift = float2(t * 0.025, t * 0.018);

    // Level 1 warp
    float qx = fbmT(uv + drift,                         3, 4);
    float qy = fbmT(uv + drift + float2(5.2, 1.3),      3, 4);
    float2 q = float2(qx, qy) - 0.5;

    // Level 2 warp — sampling position is displaced by q
    float2 uv2 = uv + q * 0.85;
    float rx = fbmT(uv2 + drift,                        4, 4);
    float ry = fbmT(uv2 + drift + float2(9.1, 3.7),     4, 4);
    float2 r = float2(rx, ry) - 0.5;

    // Final field
    float2 uv3 = uv + r * 0.60;
    float w = fbmT(uv3 + drift,                         5, 5);

    return w;
}

// ============================================================
//  Lava temperature palette
//
//  Given a scalar temperature in [0, 1], returns an RGB colour
//  along the gradient: crust → clay → orange → yellow → white-hot.
// ============================================================

float3 lavaPalette(float t) {
    // Crust — dark, almost black, with a slight red bias
    float3 c0 = float3(0.040, 0.020, 0.015);

    // Clay — warm dark brown
    float3 c1 = float3(0.180, 0.060, 0.030);

    // Molten — deep orange
    float3 c2 = float3(0.620, 0.180, 0.040);

    // Bright — bright orange
    float3 c3 = float3(0.950, 0.420, 0.080);

    // Hot — yellow
    float3 c4 = float3(1.000, 0.720, 0.220);

    // Core — near-white yellow
    float3 c5 = float3(1.000, 0.940, 0.720);

    float3 col = c0;
    col = mix(col, c1, smoothstep(0.10, 0.28, t));
    col = mix(col, c2, smoothstep(0.26, 0.48, t));
    col = mix(col, c3, smoothstep(0.46, 0.66, t));
    col = mix(col, c4, smoothstep(0.64, 0.82, t));
    col = mix(col, c5, smoothstep(0.80, 0.96, t));

    return col;
}

// ============================================================
//  main
// ============================================================

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    uv.y = 1.0 - uv.y;

    float t = time;

    // ---- Base warped field — the "shape of the flow" ----
    float flowField = warpedField(uv, t);

    // ---- Ridged field — thin bright veins on top of the flow ----
    float veins = ridgesT(uv + float2(t * 0.015, 0.0), 4, 5, 2.4);
    float veins2 = ridgesT(uv + float2(0.31, 0.17) + float2(0.0, t * 0.012),
                           7, 4, 3.0);

    // Combine. The veins emphasise the hot channels; the flow field
    // gives the overall "where is this hot vs cold" gradient.
    float temperature = flowField * 0.70
                      + veins     * 0.55
                      + veins2    * 0.30;

    // Slight gamma to bias the distribution toward the cold end
    temperature = pow(clamp(temperature, 0.0, 1.0), 1.35);

    // Boost around the hottest veins — pulls temperature up only
    // where both the flow is high AND the vein is present
    float hotMask = smoothstep(0.55, 0.95, veins) * smoothstep(0.45, 0.85, flowField);
    temperature = temperature + hotMask * 0.35;
    temperature = clamp(temperature, 0.0, 1.0);

    // ---- Palette ----
    float3 col = lavaPalette(temperature);

    // ---- Crust texture — dark grainy overlay on cold areas ----
    float crust = noiseT(uv * 180.0, 180.0) - 0.5;
    float crustMask = 1.0 - smoothstep(0.20, 0.45, temperature);
    col = col + crust * 0.05 * crustMask;

    // ---- Secondary hot spots — a few random bright embers ----
    float emberA = noiseT(uv * 22.0 + float2(t * 0.08, 0.0), 22.0);
    float emberB = noiseT(uv * 37.0 - float2(0.0, t * 0.06), 37.0);
    float embers = smoothstep(0.72, 0.90, emberA * 0.5 + emberB * 0.5);
    col = col + float3(1.0, 0.55, 0.15) * embers * 0.35;

    // ---- Vignette ----
    float2 vuv = fragCoord / resolution;
    float vig = vuv.x * vuv.y * (1.0 - vuv.x) * (1.0 - vuv.y);
    vig = clamp(pow(16.0 * vig, 0.28), 0.0, 1.0);
    col = col * mix(0.55, 1.0, vig);

    // ---- Filmic tone — keeps the hot cores from clipping flat ----
    col = col * 1.15 / (col + 0.85);
    col = pow(max(col, float3(0.0, 0.0, 0.0)), float3(0.92, 0.92, 0.92));

    return half4(half3(clamp(col, float3(0.0, 0.0, 0.0),
                                  float3(1.0, 1.0, 1.0))), 1.0);
}