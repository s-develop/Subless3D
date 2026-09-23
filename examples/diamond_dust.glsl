/*

================================================================================
  Shimmering Dust — a standalone SkSL shader for Subless3D
================================================================================

  Author:        https://github.com/s-develop
  Year:          2026
  Project:       Subless3D
  License:       Apache License 2.0
                 See LICENSE in the project root for the full text.

  This shader is a complete, ground-up rework. It is NOT a port of any
  existing multi-pass shader, and it shares no code with any of them.

  Visual inspiration:
      "Diamond Dust" by Tonny Espeset, 2026
      https://www.shadertoy.com/view/ffKXRw

  The original "Diamond Dust" is a four-pass raymarching pipeline with
  PBR lighting, real-time depth of field, bloom, chromatic aberration,
  film grain, and an evolving style system built on integer hashing.
  It cannot run in a single-pass SkSL fragment shader.

  What this shader does instead:

      * Replaces the 3D raymarched scene with a 2D domain-warped
        flow field, so it fits a single pass.
      * Replaces the spherical particle grid with three independent
        2D sparkle layers at different scales.
      * Approximates the four-point star spikes of bright particles
        directly in the sparkle function.
      * Approximates the depth-of-field bokeh with a per-particle
        "eff" value that blends between sharp point, star spike,
        soft halo, and out-of-focus ring.
      * Replaces the full bloom pipeline with an in-place ridge glow
        modulated by a ridged FBM.
      * Replaces the ACES + grade curve + grain + sRGB chain with a
        simplified filmic curve and edge-only chromatic aberration.
      * Drops the DDStyle system, the camera path, and the sound
        synchronization entirely.

  If you want the original four-pass pipeline, run it on ShaderToy.
  This file is not it. It is a separate shader that borrows the mood.

  Attribution:
      Visual concept: Tonny Espeset (ShaderToy, ffKXRw).
      The original author's permission line was:

          "Feel free to use this code, but please keep this credit
           and link."

      This file keeps the credit and the link. No code was copied.

  License of this rework:
      Apache License 2.0, as part of the Subless3D project.
      See the LICENSE file in the project root for the full text.

  Note on downstream use:
      If you redistribute this shader as part of a commercial product,
      keep this header intact. The credit to Tonny Espeset stays.
      The rest of the file is under Apache 2.0 — use, modify, and
      redistribute freely.

================================================================================
*/
uniform float2 resolution;
uniform float time;

// ============================================================
//  Hash & noise
// ============================================================

float hash21(float2 p) {
    p = fract(p * float2(127.1, 311.7));
    p += dot(p, p + 34.56);
    return fract(p.x * p.y);
}

float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(11.7, 5.3);
        a *= 0.5;
    }
    return v;
}

// ============================================================
//  4-point star spike — that "diamond" look
// ============================================================

float star4(float2 d, float size, float thin) {
    float2 a = abs(d);
    float horizontal = exp(-a.y / thin) * (1.0 - smoothstep(0.0, size, a.x));
    float vertical   = exp(-a.x / thin) * (1.0 - smoothstep(0.0, size, a.y));
    return horizontal + vertical;
}

// ============================================================
//  Sparkle layer
//
//  layerDepth in [0, 1]:
//      0  → near, sharp core + star spikes
//      1  → far, big soft bokeh disc
// ============================================================

float3 sparkleLayer(
    float2 p,
    float seed,
    float layerDepth,
    float bokehScale,
    float brightness
) {
    float2 id = floor(p);
    float2 gv = fract(p) - 0.5;
    float3 sum = float3(0.0);

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float2 off = float2(float(x), float(y));
            float2 cellId = id + off;

            float2 rnd = hash22(cellId + seed);
            float2 pos = off + (rnd - 0.5) * 0.85;

            float2 d = gv - pos;
            float d2 = dot(d, d);

            // Per-particle depth, blended with layer bias
            float perParticle = hash21(cellId + seed * 1.7 + 7.3);
            float eff = clamp(mix(layerDepth, perParticle, 0.55), 0.0, 1.0);

            // -- Sharp core (near particles only)
            float coreSize = 0.004 + eff * 0.008;
            float core = exp(-d2 / (coreSize * coreSize + 1e-8));
            core *= 1.0 - smoothstep(0.45, 0.95, eff);

            // -- Star spikes (near particles only)
            float spikeSize  = 0.020 + eff * 0.020;
            float spikeThin  = 0.0015 + eff * 0.003;
            float star = star4(d, spikeSize, spikeThin);
            star *= (1.0 - smoothstep(0.25, 0.75, eff)) * 0.75;

            // -- Soft halo
            float haloSize = coreSize * 5.0;
            float halo = exp(-d2 / (haloSize * haloSize + 1e-8)) * 0.22;

            // -- Bokeh ring (far particles only)
            float bokehRadius = bokehScale * (0.7 + eff * 1.4);
            float bokehRing   = abs(sqrt(d2) - bokehRadius * 0.68);
            float bokehWidth  = bokehRadius * 0.30;
            float bokeh = exp(-bokehRing * bokehRing
                              / (bokehWidth * bokehWidth + 1e-8));
            bokeh *= smoothstep(0.45, 0.95, eff);

            float spark = core + star + halo + bokeh;

            // Brightness: near particles brighter, far ones dimmer
            float bright = 0.30 + 1.80 * hash21(cellId + seed + 23.1);
            bright *= mix(1.80, 0.30, eff);
            bright *= brightness;

            // Jewel-tone palette
            float choice = hash21(cellId + seed + 89.7);
            float3 tint = float3(0.90, 1.00, 0.90);            // pale mint
            if (choice > 0.70) tint = float3(0.55, 1.00, 0.68); // bright green
            if (choice > 0.86) tint = float3(1.00, 0.92, 0.62); // warm gold
            if (choice > 0.94) tint = float3(1.00, 0.75, 0.45); // deep amber
            if (choice < 0.10) tint = float3(0.55, 0.82, 1.00); // cool blue

            sum += tint * spark * bright;
        }
    }
    return sum;
}

// ============================================================
//  main
// ============================================================

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * resolution) / resolution.y;
    uv *= 1.40;

    // ----- Domain warping (double FBM chain) -----
    float2 q = float2(
        fbm(uv * 0.55 + float2(time * 0.020, 0.0)),
        fbm(uv * 0.55 + float2(4.7, 1.3) + float2(0.0, time * 0.015))
    );

    float2 r = float2(
        fbm(uv * 1.05 + q * 1.90 + float2(time * 0.032, 0.0)),
        fbm(uv * 1.05 + q * 1.90 + float2(8.3, 2.7) + float2(0.0, time * 0.024))
    );

    // ----- Refraction offset for sparkles -----
    // Sparkles are displaced slightly differently from the base flow,
    // so they feel suspended inside the liquid rather than painted on top.
    float2 refraction = (r - 0.5) * 0.15;
    float2 sparkleUV = uv + (r - 0.5) * 1.10 + refraction;

    // ----- Density field -----
    float base = fbm(uv * 1.15 + q * 1.30 + float2(time * 0.015, time * 0.010));

    float ridgeRaw = fbm(uv * 1.70 + r * 1.10 + float2(time * 0.025, 0.0));
    float ridges = 1.0 - abs(ridgeRaw - 0.5) * 2.0;
    ridges = pow(clamp(ridges, 0.0, 1.0), 1.7);

    float density = base * 0.45 + ridges * 0.95;
    density = smoothstep(0.32, 0.85, density);

    // Sharper ridge mask for glowing accents
    float glow = pow(ridges, 2.5) * smoothstep(0.40, 0.90, base);

    // ----- Palette -----
    float3 darkBackground = float3(0.001, 0.005, 0.003);
    float3 tealDeep       = float3(0.008, 0.120, 0.100);
    float3 emeraldPigment = float3(0.015, 0.200, 0.075);
    float3 brightGreen    = float3(0.200, 0.600, 0.280);
    float3 goldShine      = float3(0.720, 0.750, 0.420);

    float3 bg = mix(darkBackground, tealDeep, base * 0.7);
    bg = mix(bg, emeraldPigment, smoothstep(0.20, 0.60, base));
    bg = mix(bg, brightGreen,    glow * 0.55);
    bg = mix(bg, goldShine,      density * density * 0.35);

    // ----- Sparkle layers -----
    // near: fine grid, sharp points + star spikes
    // mid:  medium grid, mixed
    // far:  coarse grid, big soft bokeh
    float3 near = sparkleLayer(sparkleUV * 68.0 + float2(11.7, 2.3),
                               13.7, 0.00, 0.010, 1.55);
    float3 mid  = sparkleLayer(sparkleUV * 36.0 + float2(3.1, 7.9),
                               5.3,  0.45, 0.028, 0.95);
    float3 far  = sparkleLayer(sparkleUV * 18.0 + float2(0.0, 0.0),
                               0.0,  0.85, 0.060, 0.55);

    float dm = density * density;

    // Per-layer density masks:
    //   near particles cluster on ridges (follow the flow),
    //   mid particles moderately clustered,
    //   far particles spread almost evenly (they're "behind" everything).
    float3 sparkles = near * (0.05 + dm * 3.20)
                    + mid  * (0.15 + dm * 2.00)
                    + far  * (0.30 + dm * 1.20);

    // ----- Bloom-like core glow on the ridge field -----
    float3 coreGlow = brightGreen * glow * 0.25;

    // ----- Composite -----
    float3 col = bg + coreGlow + sparkles;

    // ----- Chromatic aberration (edge-only) -----
    float2 cc = fragCoord / resolution - 0.5;
    float edge = length(cc);
    col.r *= 1.0 + edge * 0.15;
    col.b *= 1.0 - edge * 0.12;

    // ----- Filmic tone + slight lift -----
    col = col * 1.35 / (col + 0.85);
    col = pow(max(col, float3(0.0)), float3(0.96));

    // ----- Vignette -----
    float2 vuv = fragCoord / resolution;
    float vig = vuv.x * vuv.y * (1.0 - vuv.x) * (1.0 - vuv.y);
    vig = clamp(pow(16.0 * vig, 0.32), 0.0, 1.0);
    col *= mix(0.38, 1.0, vig);

    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}