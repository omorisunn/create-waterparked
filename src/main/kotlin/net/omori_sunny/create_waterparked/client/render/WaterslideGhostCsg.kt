package net.omori_sunny.create_waterparked.client.render
// CSG primitives: prism solids, convex difference and the regenerated cut face.

import net.minecraft.world.phys.Vec3

object WaterslideGhostCsg {

    private const val CLIP_EPS = 1.0E-4
    private const val MIN_AREA = 1.0E-3
    private const val SIMPLIFY_TOL = 2.0E-3
    private const val CUT_FACE_OUTSET = 0.005f

    class Vertex(
        var x: Float, var y: Float, var z: Float,
        var nx: Float, var ny: Float, var nz: Float,
        var u: Float, var v: Float,
        var color: Int,
        var light: Int,
        var overlay: Int
    ) {
        fun interpolate(o: Vertex, t: Float): Vertex = Vertex(
            x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t,
            nx + (o.nx - nx) * t, ny + (o.ny - ny) * t, nz + (o.nz - nz) * t,
            u + (o.u - u) * t, v + (o.v - v) * t,
            color, light, overlay
        )

        fun copy(): Vertex = Vertex(x, y, z, nx, ny, nz, u, v, color, light, overlay)
    }

    class Polygon(
        val vertices: MutableList<Vertex>,
        var fromSolid: Boolean
    ) {
        private var planeCache: Plane? = null
        val plane: Plane
            get() = planeCache ?: computePlane().also { planeCache = it }

        private fun computePlane(): Plane {
            val a = vertices[0]
            val b = vertices[1]
            val c = vertices[2]
            val v0 = Vec3(b.x - a.x.toDouble(), b.y - a.y.toDouble(), b.z - a.z.toDouble())
            val v1 = Vec3(c.x - a.x.toDouble(), c.y - a.y.toDouble(), c.z - a.z.toDouble())
            val n = v0.cross(v1)
            val len = n.length()
            val nx = if (len < 1.0E-12) 0f else (n.x / len).toFloat()
            val ny = if (len < 1.0E-12) 1f else (n.y / len).toFloat()
            val nz = if (len < 1.0E-12) 0f else (n.z / len).toFloat()
            return Plane(nx, ny, nz, nx * a.x + ny * a.y + nz * a.z)
        }

        fun flip() {
            vertices.reverse()
            for (v in vertices) {
                v.nx = -v.nx; v.ny = -v.ny; v.nz = -v.nz
            }
            planeCache?.flip()
        }
    }

    class Plane(var nx: Float, var ny: Float, var nz: Float, var w: Float) {
        fun flip() {
            nx = -nx; ny = -ny; nz = -nz; w = -w
        }

        fun copy(): Plane = Plane(nx, ny, nz, w)

        fun distance(v: Vertex): Double = (nx * v.x + ny * v.y + nz * v.z - w).toDouble()

        fun keepOutside(polygon: Polygon, out: MutableList<Polygon>) =
            clipPlane(polygon, keepInside = false, out)

        fun keepInside(polygon: Polygon, out: MutableList<Polygon>) =
            clipPlane(polygon, keepInside = true, out)

        private fun clipPlane(polygon: Polygon, keepInside: Boolean, out: MutableList<Polygon>) {
            val verts = polygon.vertices
            val f = ArrayList<Vertex>(verts.size + 2)
            for (i in verts.indices) {
                val j = (i + 1) % verts.size
                val vi = verts[i]
                val vj = verts[j]
                val di = distance(vi)
                val dj = distance(vj)
                val bi = kotlin.math.abs(di) < CLIP_EPS
                val bj = kotlin.math.abs(dj) < CLIP_EPS
                val inI = if (keepInside) bi || di <= CLIP_EPS else bi || di >= -CLIP_EPS
                val inJ = if (keepInside) bj || dj <= CLIP_EPS else bj || dj >= -CLIP_EPS
                if (inI) f += vi
                if (inI != inJ && !(bi || bj)) {
                    val t = (di / (di - dj)).coerceIn(0.0, 1.0)
                    f += vi.interpolate(vj, t.toFloat())
                }
            }
            if (f.size >= 3) out += Polygon(f, polygon.fromSolid)
        }
    }

    class Solid(val polygons: List<Polygon>, val facets: List<Polygon> = polygons) {
        val planes: List<Plane> = polygons.map { it.plane }
        private var aabbCache: DoubleArray? = null

        fun aabb(): DoubleArray {
            aabbCache?.let { return it }
            var loX = Double.MAX_VALUE; var loY = Double.MAX_VALUE; var loZ = Double.MAX_VALUE
            var hiX = -Double.MAX_VALUE; var hiY = -Double.MAX_VALUE; var hiZ = -Double.MAX_VALUE
            for (p in polygons) for (v in p.vertices) {
                if (v.x < loX) loX = v.x.toDouble(); if (v.x > hiX) hiX = v.x.toDouble()
                if (v.y < loY) loY = v.y.toDouble(); if (v.y > hiY) hiY = v.y.toDouble()
                if (v.z < loZ) loZ = v.z.toDouble(); if (v.z > hiZ) hiZ = v.z.toDouble()
            }
            return doubleArrayOf(loX, loY, loZ, hiX, hiY, hiZ).also { aabbCache = it }
        }

        fun intersectionOf(piece: Polygon): List<Polygon> {
            var cur: List<Polygon> = listOf(piece)
            for (plane in planes) {
                if (cur.isEmpty()) break
                val next = ArrayList<Polygon>()
                for (p in cur) plane.keepInside(p, next)
                cur = next
            }
            return cur
        }

        fun subtractFrom(input: List<Polygon>): List<Polygon> {
            val out = ArrayList<Polygon>()
            for (piece in input) {
                var insidePieces = listOf(piece)
                for (plane in planes) {
                    if (insidePieces.isEmpty()) break
                    val next = ArrayList<Polygon>()
                    for (p in insidePieces) plane.keepInside(p, next)
                    insidePieces = next
                }
                if (insidePieces.isEmpty()) {
                    out += piece
                    continue
                }
                for (inner in insidePieces) {
                    if (inner.vertices.size < 3) continue
                    val pn = inner.plane
                    val cx = inner.vertices.fold(0.0) { acc, v -> acc + v.x } / inner.vertices.size
                    val cy = inner.vertices.fold(0.0) { acc, v -> acc + v.y } / inner.vertices.size
                    val cz = inner.vertices.fold(0.0) { acc, v -> acc + v.z } / inner.vertices.size
                    for (i in inner.vertices.indices) {
                        val a = inner.vertices[i]
                        val b = inner.vertices[(i + 1) % inner.vertices.size]
                        val ex = b.x - a.x
                        val ey = b.y - a.y
                        val ez = b.z - a.z
                        var nX = pn.ny * ez - pn.nz * ey
                        var nY = pn.nz * ex - pn.nx * ez
                        var nZ = pn.nx * ey - pn.ny * ex
                        val len = kotlin.math.sqrt(nX * nX + nY * nY + nZ * nZ)
                        if (len < 1.0E-9) continue
                        nX /= len; nY /= len; nZ /= len
                        val dot = nX * (cx - a.x) + nY * (cy - a.y) + nZ * (cz - a.z)
                        if (dot > 0.0) {
                            nX = -nX; nY = -nY; nZ = -nZ
                        }
                        val edgePlane = Plane(nX.toFloat(), nY.toFloat(), nZ.toFloat(),
                            (nX * a.x + nY * a.y + nZ * a.z).toFloat())
                        edgePlane.keepOutside(piece, out)
                    }
                }
            }
            return out
        }

        fun rayEntry(eye: Vec3, dir: Vec3): Double? {
            if (planes.isEmpty()) return null
            var tIn = 0.0
            var tOut = Double.POSITIVE_INFINITY
            for (p in planes) {
                val d0 = (p.nx * eye.x + p.ny * eye.y + p.nz * eye.z - p.w).toDouble()
                val d1 = (p.nx * dir.x + p.ny * dir.y + p.nz * dir.z).toDouble()
                if (kotlin.math.abs(d1) < 1.0E-8) {
                    if (d0 <= CLIP_EPS) return null
                    continue
                }
                val t = -d0 / d1
                if (d1 < 0.0) {
                    if (t > tIn) tIn = t
                } else {
                    if (t < tOut) tOut = t
                }
            }
            if (tOut < tIn) return null
            return if (tIn > 0.0) tIn else if (tOut.isFinite()) tOut else null
        }
    }

    fun difference(a: List<Polygon>, b: List<Solid>): List<Polygon> {
        if (a.isEmpty()) return emptyList()
        val blockPolys = a.map { copyPolygon(it) }
        val centroid = blockCentroid(blockPolys)
        for (p in blockPolys) if (planePointsInward(p, centroid)) p.flip()
        val blockAabb = aabbOf(blockPolys)

        val result = ArrayList<Polygon>()
        var faces: List<Polygon> = blockPolys
        for (s in b) {
            if (!aabbIntersects(blockAabb, s.aabb())) continue
            faces = s.subtractFrom(faces)
        }
        for (f in faces) finalize(f)?.let { result += it }
        return result
    }

    fun cutFace(blockPolys: List<Polygon>, solids: List<Solid>): List<Polygon> {
        if (blockPolys.isEmpty()) return emptyList()
        val oriented = blockPolys.map { copyPolygon(it) }
        val centroid = blockCentroid(oriented)
        for (p in oriented) if (planePointsInward(p, centroid)) p.flip()
        val blockSolid = Solid(oriented)
        val out = ArrayList<Polygon>()
        for (s in solids) {
            for (p in s.facets) {
                for (piece in blockSolid.intersectionOf(p)) {
                    val fin = finalize(piece) ?: continue
                    fin.flip()
                    val n = fin.plane
                    for (v in fin.vertices) {
                        v.x += n.nx * CUT_FACE_OUTSET
                        v.y += n.ny * CUT_FACE_OUTSET
                        v.z += n.nz * CUT_FACE_OUTSET
                    }
                    out += fin
                }
            }
        }
        return inheritCutFaceAttributes(out, blockPolys)
    }

    private fun aabbOf(polys: List<Polygon>): DoubleArray {
        var loX = Double.MAX_VALUE; var loY = Double.MAX_VALUE; var loZ = Double.MAX_VALUE
        var hiX = -Double.MAX_VALUE; var hiY = -Double.MAX_VALUE; var hiZ = -Double.MAX_VALUE
        for (p in polys) for (v in p.vertices) {
            if (v.x < loX) loX = v.x.toDouble(); if (v.x > hiX) hiX = v.x.toDouble()
            if (v.y < loY) loY = v.y.toDouble(); if (v.y > hiY) hiY = v.y.toDouble()
            if (v.z < loZ) loZ = v.z.toDouble(); if (v.z > hiZ) hiZ = v.z.toDouble()
        }
        return doubleArrayOf(loX, loY, loZ, hiX, hiY, hiZ)
    }

    private fun aabbIntersects(a: DoubleArray, b: DoubleArray): Boolean =
        a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1] && a[2] <= b[5] && a[5] >= b[2]

    private fun copyPolygon(p: Polygon): Polygon =
        Polygon(p.vertices.map { it.copy() }.toMutableList(), p.fromSolid)

    private fun blockCentroid(polys: List<Polygon>): Vec3 {
        var x = 0.0; var y = 0.0; var z = 0.0; var n = 0
        for (p in polys) {
            for (v in p.vertices) {
                x += v.x; y += v.y; z += v.z; n++
            }
        }
        return if (n == 0) Vec3.ZERO else Vec3(x / n, y / n, z / n)
    }

    private fun planePointsInward(p: Polygon, center: Vec3): Boolean {
        val cx = p.vertices.fold(0.0) { acc, v -> acc + v.x } / p.vertices.size
        val cy = p.vertices.fold(0.0) { acc, v -> acc + v.y } / p.vertices.size
        val cz = p.vertices.fold(0.0) { acc, v -> acc + v.z } / p.vertices.size
        val nx = p.plane.nx.toDouble(); val ny = p.plane.ny.toDouble(); val nz = p.plane.nz.toDouble()
        return (nx * (center.x - cx) + ny * (center.y - cy) + nz * (center.z - cz)) > 0.0
    }

    private fun finalize(p: Polygon): Polygon? {
        var s = sanitize(p) ?: return null
        s = simplify(s)
        return if (polygonArea(s) >= MIN_AREA) s else null
    }

    private fun simplify(p: Polygon): Polygon {
        val vs = p.vertices
        if (vs.size <= 4) return p
        val keep = BooleanArray(vs.size) { true }
        var changed = true
        while (changed) {
            changed = false
            var kept = keep.count { it }
            if (kept <= 3) break
            for (i in vs.indices) {
                if (!keep[i]) continue
                var prev = i
                var next = i
                do { prev = (prev - 1 + vs.size) % vs.size } while (!keep[prev])
                do { next = (next + 1) % vs.size } while (!keep[next])
                if (prev == next) continue
                if (distToSeg(vs[prev], vs[next], vs[i]) < SIMPLIFY_TOL) {
                    keep[i] = false
                    changed = true
                    kept--
                    if (kept <= 3) break
                }
            }
        }
        val out = ArrayList<Vertex>()
        for (i in vs.indices) if (keep[i]) out += vs[i]
        return if (out.size >= 3) Polygon(out, p.fromSolid) else p
    }

    private fun distToSeg(a: Vertex, b: Vertex, v: Vertex): Double {
        val abx = (b.x - a.x).toDouble()
        val aby = (b.y - a.y).toDouble()
        val abz = (b.z - a.z).toDouble()
        val len2 = abx * abx + aby * aby + abz * abz
        if (len2 < 1.0E-12) {
            val dx = (v.x - a.x).toDouble()
            val dy = (v.y - a.y).toDouble()
            val dz = (v.z - a.z).toDouble()
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        var t = ((v.x - a.x) * abx + (v.y - a.y) * aby + (v.z - a.z) * abz) / len2
        t = t.coerceIn(0.0, 1.0)
        val px = a.x + (abx * t).toFloat()
        val py = a.y + (aby * t).toFloat()
        val pz = a.z + (abz * t).toFloat()
        val dx = (v.x - px).toDouble()
        val dy = (v.y - py).toDouble()
        val dz = (v.z - pz).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun sanitize(p: Polygon): Polygon? {
        val vs = p.vertices
        if (vs.size < 3) return null
        val clean = ArrayList<Vertex>(vs.size)
        for (v in vs) {
            if (clean.isNotEmpty() && distSq(clean.last(), v) < 1.0E-12) continue
            clean += v
        }
        if (clean.size >= 2 && distSq(clean.last(), clean[0]) < 1.0E-12) {
            clean.removeAt(clean.size - 1)
        }
        if (clean.size < 3) return null
        var i = 0
        while (i < clean.size) {
            if (clean.size < 3) return null
            val a = clean[(i - 1 + clean.size) % clean.size]
            val b = clean[i]
            val c = clean[(i + 1) % clean.size]
            if (crossMag(a, b, c) < 1.0E-9) {
                clean.removeAt(i)
            } else {
                i++
            }
        }
        if (clean.size < 3) return null
        return Polygon(clean, p.fromSolid)
    }

    private fun distSq(a: Vertex, b: Vertex): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val dz = (b.z - a.z).toDouble()
        return dx * dx + dy * dy + dz * dz
    }

    private fun crossMag(a: Vertex, b: Vertex, c: Vertex): Double {
        val abx = (b.x - a.x).toDouble(); val aby = (b.y - a.y).toDouble(); val abz = (b.z - a.z).toDouble()
        val acx = (c.x - a.x).toDouble(); val acy = (c.y - a.y).toDouble(); val acz = (c.z - a.z).toDouble()
        val cx = aby * acz - abz * acy
        val cy = abz * acx - abx * acz
        val cz = abx * acy - aby * acx
        return kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
    }

    private fun polygonArea(p: Polygon): Double {
        val n = p.vertices.size
        if (n < 3) return 0.0
        var ax = 0.0; var ay = 0.0; var az = 0.0
        for (i in 0 until n) {
            val a = p.vertices[i]
            val b = p.vertices[(i + 1) % n]
            ax += a.y.toDouble() * b.z - a.z.toDouble() * b.y
            ay += a.z.toDouble() * b.x - a.x.toDouble() * b.z
            az += a.x.toDouble() * b.y - a.y.toDouble() * b.x
        }
        return 0.5 * kotlin.math.sqrt(ax * ax + ay * ay + az * az)
    }

    private fun clipToInsideBlock(p: Polygon, blockPolys: List<Polygon>): Polygon? {
        var cur: MutableList<Polygon> = mutableListOf(copyPolygon(p))
        for (bp in blockPolys) {
            if (cur.isEmpty()) return null
            val next = ArrayList<Polygon>(cur.size)
            for (poly in cur) bp.plane.keepInside(poly, next)
            cur = next
        }
        return cur.firstOrNull()
    }

    fun prismSolid(ring0: List<Vec3>, ring1: List<Vec3>, c0: Vec3, c1: Vec3): Solid {
        val polys = ArrayList<Polygon>()
        val facets = ArrayList<Polygon>()
        if (ring0.size < 3) return Solid(emptyList())
        if (c1.subtract(c0).lengthSqr() < 1.0E-12) return Solid(emptyList())
        val dir = c1.subtract(c0).normalize()
        val center = Vec3(
            ring0.fold(0.0) { acc, p -> acc + p.x } / ring0.size,
            ring0.fold(0.0) { acc, p -> acc + p.y } / ring0.size,
            ring0.fold(0.0) { acc, p -> acc + p.z } / ring0.size
        )
        for (i in ring0.indices) {
            val j = (i + 1) % ring0.size
            val poly = Polygon(
                mutableListOf(
                    vert(ring0[i]), vert(ring0[j]), vert(ring1[j]), vert(ring1[i])
                ),
                fromSolid = false
            )
            val mid = Vec3(
                (ring0[i].x + ring0[j].x + ring1[j].x + ring1[i].x) / 4.0,
                (ring0[i].y + ring0[j].y + ring1[j].y + ring1[i].y) / 4.0,
                (ring0[i].z + ring0[j].z + ring1[j].z + ring1[i].z) / 4.0
            )
            if (Vec3(poly.plane.nx.toDouble(), poly.plane.ny.toDouble(), poly.plane.nz.toDouble())
                    .dot(center.subtract(mid)) > 0.0
            ) {
                poly.flip()
            }
            polys += poly
            facets += poly
        }
        polys += ringCap(ring0, dir.scale(-1.0))
        polys += ringCap(ring1, dir)
        return Solid(polys, facets)
    }

    private fun ringCap(ring: List<Vec3>, outDir: Vec3): Polygon {
        val poly = Polygon(ring.map { vert(it) }.toMutableList(), fromSolid = false)
        if (Vec3(poly.plane.nx.toDouble(), poly.plane.ny.toDouble(), poly.plane.nz.toDouble())
                .dot(outDir) < 0.0
        ) {
            poly.flip()
        }
        return poly
    }

    private fun vert(p: Vec3) = Vertex(
        p.x.toFloat(), p.y.toFloat(), p.z.toFloat(),
        0f, 1f, 0f, 0f, 0f, 0xFFFFFFFF.toInt(), 0, 0
    )

    fun inheritCutFaceAttributes(polygons: List<Polygon>, blockFaces: List<Polygon>): List<Polygon> {
        if (blockFaces.isEmpty()) return polygons
        for (polygon in polygons) {
            if (polygon.fromSolid) continue
            val pn = Vec3(polygon.plane.nx.toDouble(), polygon.plane.ny.toDouble(), polygon.plane.nz.toDouble())
            var best: Polygon? = null
            var bestDot = 2.0
            for (f in blockFaces) {
                val d = pn.dot(Vec3(f.plane.nx.toDouble(), f.plane.ny.toDouble(), f.plane.nz.toDouble()))
                if (d < bestDot) {
                    bestDot = d
                    best = f
                }
            }
            val face = best ?: continue
            if (face.vertices.size < 4) continue
            val q0 = face.vertices[0]
            val q1 = face.vertices[1]
            val q3 = face.vertices[3]
            val e1x = (q1.x - q0.x).toDouble(); val e1y = (q1.y - q0.y).toDouble(); val e1z = (q1.z - q0.z).toDouble()
            val e2x = (q3.x - q0.x).toDouble(); val e2y = (q3.y - q0.y).toDouble(); val e2z = (q3.z - q0.z).toDouble()
            val e1l = e1x * e1x + e1y * e1y + e1z * e1z
            val e2l = e2x * e2x + e2y * e2y + e2z * e2z
            if (e1l < 1.0E-12 || e2l < 1.0E-12) continue
            for (v in polygon.vertices) {
                val dx = (v.x - q0.x).toDouble(); val dy = (v.y - q0.y).toDouble(); val dz = (v.z - q0.z).toDouble()
                val a = ((dx * e1x + dy * e1y + dz * e1z) / e1l).coerceIn(0.0, 1.0)
                val b = ((dx * e2x + dy * e2y + dz * e2z) / e2l).coerceIn(0.0, 1.0)
                val af = a.toFloat(); val bf = b.toFloat()
                v.u = q0.u + (q1.u - q0.u) * af + (q3.u - q0.u) * bf
                v.v = q0.v + (q1.v - q0.v) * af + (q3.v - q0.v) * bf
                v.color = q0.color
                var nearest: Vertex = face.vertices[0]
                var nd = Double.MAX_VALUE
                for (fv in face.vertices) {
                    val d2 = (v.x - fv.x).toDouble() * (v.x - fv.x) +
                        (v.y - fv.y).toDouble() * (v.y - fv.y) +
                        (v.z - fv.z).toDouble() * (v.z - fv.z)
                    if (d2 < nd) {
                        nd = d2
                        nearest = fv
                    }
                }
                v.light = nearest.light
                v.overlay = nearest.overlay
            }
        }
        return polygons
    }
}