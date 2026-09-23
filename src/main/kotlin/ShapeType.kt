package com.example.subless3d

/**
 * Primitives available for the preview and for baking.
 *
 * ROUNDED_BOX uses a true rounded SDF-generated mesh — every exterior edge
 * is a cylindrical fillet, every corner is spherical. See buildRoundedCube().
 */
enum class ShapeType(val label: String) {
    CUBE("Cube"),
    SPHERE("Sphere"),
    PLANE("Plane"),
    ROUNDED_BOX("Rounded box")
}