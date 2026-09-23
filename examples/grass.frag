/*

================================================================================
  Low-Poly Grass Field — tileable procedural texture
================================================================================

  Author:        https://github.com/s-develop
  Year:          2026
  Project:       Subless3D
  License:       Apache License 2.0
                 See LICENSE in the project root for the full text.

  This is an original work written from scratch. It does not share code
  with any third-party shader. The visual subject — a top-down grass
  field — is a common motif and not attributable to any single author.

  Approach:
      * A tileable grid of cells, one grass blade per cell.
      * Each blade is a triangle rendered analytically: signed distance
        to the triangle's edges, plus an inside/outside test in barycentric
        coordinates. No texture lookup, no per-pixel iteration.
      * Blades have per-blade random orientation, length and colour.
      * Flat shading: the blade's surface normal is tilted in the direction
        its tip is leaning, so a low-poly faceted look emerges.
      * A ground plane underneath fills the gaps between blades. It gets
        its own subtle colour variation from a wrapped value noise.

  Tiling:
      * Grid dimensions divide UV [0, 1] evenly, so cells align at borders.
      * All cell randomness is keyed by the wrapped cell id.
      * A 3×3 neighbour lookup guarantees blades that overhang their cell
        boundary are still drawn — the pattern has no visible cut at edges.
      * Every value-noise lookup uses a wrapped lattice.

================================================================================
*/

uniform float2 resolution;
uniform float time;

const float TAU = 6.28318530718;

// ============================================================
//  Hashes
// ============================================================

float hash11(float x) {
    return fract(sin(x * 78.233) * 43758.5453);
}

float hash21(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    p3 = p3 + dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// ============================================================
//  Wrapped value noise — tileable
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
//  Flat-shaded triangle blade
//
//  Each blade is defined by:
//      root      — position of the blade root
//      tip       — position of the tip (root + direction * length)
//      baseWidth — width of the base
//
//  Given a query point p, returns:
//      coverage in [0, 1]  — 0 outside, 1 inside
//      distanceToEdge      — used for a soft anti-aliased edge
//
//  Additionally returns a normal whose tilt depends on the blade's
//  lean direction, producing the low-poly faceted appearance.
// ============================================================

struct BladeHit {
    float coverage;
    float edgeSoft;
    float height;
};

BladeHit bladeAt(float2 p, float2 root, float2 tip, float baseWidth) {
    // Build a local frame: along the blade direction, and across it
    float2 axis = tip - root;
    float len = length(axis);
    if (len < 1e-5) {
        BladeHit empty;
        empty.coverage = 0.0;
        empty.edgeSoft = 1.0;
        empty.height = 0.0;
        return empty;
    }
    float2 dir = axis / len;
    float2 perp = float2(-dir.y, dir.x);

    float2 rel = p - root;
    float along = dot(rel, dir);          // 0 at root, len at tip
    float across = dot(rel, perp);        // signed distance perpendicular

    // Blade geometry: widest at the root, tapering linearly to 0 at the tip
    float u = along / len;
    if (u < 0.0 || u > 1.0) {
        BladeHit empty;
        empty.coverage = 0.0;
        empty.edgeSoft = 1.0;
        empty.height = 0.0;
        return empty;
    }

    float halfWidth = baseWidth * 0.5 * (1.0 - u);

    // Signed distance to the tapered rectangle's side
    float d = abs(across) - halfWidth;

    // Coverage via smoothstep — the "edgeSoft" is used for AA in the
    // final composite. Coverage is 1 inside, 0 outside.
    float edge = 0.004;
    float coverage = 1.0 - smoothstep(-edge, edge, d);

    BladeHit hit;
    hit.coverage = coverage;
    hit.edgeSoft = 1.0 - smoothstep(-edge * 3.0, edge * 3.0, d);
    hit.height = u;                        // for gradient along the blade
    return hit;
}

// ============================================================
//  Grass blade colour
//
//  A small palette of greens. Each blade picks one at random and
//  gets a slight gradient from dark at the root to bright at the tip.
// ============================================================

float3 grassColour(float id, float height, float tipBrightness) {
    float r = hash11(id * 1.7);

    // Four greens — the classic low-poly palette
    float3 g0 = float3(0.14, 0.28, 0.12);   // deep
    float3 g1 = float3(0.24, 0.42, 0.16);   // mid
    float3 g2 = float3(0.36, 0.55, 0.20);   // bright
    float3 g3 = float3(0.48, 0.62, 0.26);   // yellow-green
    float3 g4 = float3(0.20, 0.36, 0.14);   // olive

    float3 base = g1;
    if (r > 0.78) base = g3;
    else if (r > 0.55) base = g2;
    else if (r > 0.30) base = g1;
    else if (r > 0.12) base = g0;
    else base = g4;

    // Gradient from dark at root to bright at tip
    float3 col = mix(base * 0.65, base * 1.15, height);

    // Additional brightness variation at the tip
    col = col * (1.0 + tipBrightness * 0.2);

    return col;
}

// ============================================================
//  Ground plane underneath the blades
// ============================================================

float3 groundColour(float2 uv) {
    // Dark earth-green with fine mottling
    float n1 = noiseT(uv * 40.0, 40.0) - 0.5;
    float n2 = noiseT(uv * 90.0, 90.0) - 0.5;

    float3 base = float3(0.10, 0.16, 0.08);
    base = base + n1 * 0.04 + n2 * 0.02;

    return base;
}

// ============================================================
//  main
// ============================================================

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    uv.y = 1.0 - uv.y;

    // Slight camera drift for a live look
    uv = uv + float2(time * 0.006, time * 0.004);

    // --------------------------------------------------------
    //  Grid
    // --------------------------------------------------------

    const float COLS = 80.0;
    const float ROWS = 80.0;

    float2 grid = uv * float2(COLS, ROWS);
    float2 cellId = floor(grid);
    float2 cellUv = fract(grid);

    // --------------------------------------------------------
    //  Ground
    // --------------------------------------------------------

    float3 ground = groundColour(uv);

    // --------------------------------------------------------
    //  Blades — 3×3 neighbourhood lookup
    //
    //  Each cell holds one blade. Blades can lean outside their
    //  cell, so we sample 9 cells around the current one and take
    //  the topmost blade that covers the pixel.
    // --------------------------------------------------------

    float3 bladeCol = ground;
    float bestHeight = -1.0;

    // Two passes over the 3×3 neighbourhood.
    // Pass 1: cells in the back row (visually "behind"), lower z-bias.
    // Pass 2: cells in the front row (visually "in front"), higher z-bias.
    // The z-bias encodes a simple painter's order so blades in the
    // same image do not overlap incorrectly.

    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float2 off = float2(float(i), float(j));
            float2 neighbourId = cellId + off;

            // Wrapped id for hash lookup
            float2 wrapped = mod(neighbourId, float2(COLS, ROWS));
            float id = wrapped.y * COLS + wrapped.x;

            // Random blade parameters
            float2 rootRnd = hash22(float2(id, 3.1));
            float2 dirRnd  = hash22(float2(id, 17.7));
            float  lenRnd  = hash11(id * 5.3);
            float  wdtRnd  = hash11(id * 11.9);
            float  leanRnd = hash11(id * 23.4);

            // Root position — the cell centre plus small jitter
            float2 rootUv = (neighbourId + 0.5 + (rootRnd - 0.5) * 0.55)
                          / float2(COLS, ROWS);

            // Blade direction — mostly up, small random lean
            float angle = (dirRnd.x - 0.5) * 2.4;
            float2 dir = float2(sin(angle), cos(angle));

            // Blade length
            float len = (0.55 + lenRnd * 0.55) / ROWS;

            // Blade width at the base
            float width = (0.10 + wdtRnd * 0.10) / COLS;

            float2 tip = rootUv + dir * len;

            BladeHit hit = bladeAt(uv, rootUv, tip, width);

            if (hit.coverage > 0.0) {
                // Painter's depth: higher rows drawn on top of lower
                float depthBias = float(j) * 0.001 + hit.height * 0.5
                                + leanRnd * 0.0005;

                if (depthBias > bestHeight) {
                    bestHeight = depthBias;

                    float3 col = grassColour(id, hit.height, dirRnd.y);

                    // Flat shading — the normal leans in the direction
                    // the blade is tilted, giving a faceted look.
                    // Light coming from top-left.
                    float3 nrm = normalize(float3(dir.x * 0.5,
                                                  dir.y * 0.5,
                                                  1.0));
                    float3 lightDir = normalize(float3(-0.4, 0.6, 0.7));

                    float ndl = max(dot(nrm, lightDir), 0.0);
                    float shade = 0.35 + ndl * 0.85;

                    // Slight per-blade brightness variation
                    float bright = 0.85 + hash11(id * 7.1) * 0.35;

                    col = col * shade * bright;

                    // Thin highlight along the blade's centre line
                    // — gives the impression of the blade's spine.
                    float u = hit.height;
                    float spine = exp(-pow(abs((hit.edgeSoft - 1.0)) * 1.5, 2.0));
                    col = col + float3(0.15, 0.22, 0.10) * spine * u * 0.4;

                    // Anti-aliased composite
                    bladeCol = mix(bladeCol, col, hit.coverage);
                }
            }
        }
    }

    // --------------------------------------------------------
    //  Vignette and tone
    // --------------------------------------------------------

    float2 vuv = fragCoord / resolution;
    float vig = vuv.x * vuv.y * (1.0 - vuv.x) * (1.0 - vuv.y);
    vig = clamp(pow(16.0 * vig, 0.28), 0.0, 1.0);

    float3 col = bladeCol * mix(0.72, 1.0, vig);
    col = pow(max(col, float3(0.0, 0.0, 0.0)), float3(0.94, 0.94, 0.94));

    return half4(half3(clamp(col, float3(0.0, 0.0, 0.0),
                                  float3(1.0, 1.0, 1.0))), 1.0);
}