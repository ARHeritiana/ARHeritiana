package qcm.core

/** Transformation projective du plan : (x, y) -> (X, Y), ici millimètres de la zone -> pixels. */
class Homographie(private val h: DoubleArray) {
    fun x(x: Double, y: Double) = (h[0] * x + h[1] * y + h[2]) / (h[6] * x + h[7] * y + 1.0)
    fun y(x: Double, y: Double) = (h[3] * x + h[4] * y + h[5]) / (h[6] * x + h[7] * y + 1.0)

    /** Échelle locale (pixels par unité) autour du point (x, y). */
    fun echelle(x: Double, y: Double): Double {
        val ax = x(x + 1, y) - x(x, y); val ay = y(x + 1, y) - y(x, y)
        val bx = x(x, y + 1) - x(x, y); val by = y(x, y + 1) - y(x, y)
        return Math.sqrt(Math.abs(ax * by - ay * bx))
    }

    companion object {
        /** Homographie envoyant les 4 points src[i] = (x, y) sur dst[i] = (X, Y) ; null si dégénérée. */
        fun depuis4Points(src: Array<DoubleArray>, dst: Array<DoubleArray>): Homographie? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]; val (u, v) = dst[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (c in 0 until 8) {                      // pivot de Gauss
                var p = c
                for (r in c + 1 until 8) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r
                if (Math.abs(a[p][c]) < 1e-12) return null
                val t = a[c]; a[c] = a[p]; a[p] = t
                for (r in 0 until 8) if (r != c) {
                    val f = a[r][c] / a[c][c]
                    if (f != 0.0) for (k in c until 9) a[r][k] -= f * a[c][k]
                }
            }
            return Homographie(DoubleArray(8) { a[it][8] / a[it][it] })
        }
    }
}
