package com.surfcam.core.tracking

/**
 * Affectation de coût minimal (algorithme hongrois, O(n³)) : chaque ligne reçoit au plus une colonne et inversement.
 * Les cases à [Double.POSITIVE_INFINITY] sont interdites. Renvoie, pour chaque ligne, l'indice de sa colonne ou -1.
 */
object Hungarian {
    fun solve(cost: Array<DoubleArray>): IntArray {
        val rows = cost.size
        if (rows == 0) return IntArray(0)
        val cols = cost[0].size
        if (cols == 0) return IntArray(rows) { -1 }
        val n = maxOf(rows, cols)
        // Coût fini mais très élevé pour les cases interdites et le remplissage : l'algorithme reste sur des nombres finis,
        // et on retire ensuite les affectations interdites.
        var finiteMax = 0.0
        for (r in cost) for (v in r) if (v.isFinite() && v > finiteMax) finiteMax = v
        val big = (finiteMax + 1.0) * (n + 1) * 10.0
        val a = Array(n + 1) { DoubleArray(n + 1) }
        for (i in 1..n) for (j in 1..n) {
            val v = if (i <= rows && j <= cols) cost[i - 1][j - 1] else big
            a[i][j] = if (v.isFinite()) v else big
        }
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..n) {
                    if (!used[j]) {
                        val cur = a[i0][j] - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                        if (minv[j] < delta) {
                            delta = minv[j]
                            j1 = j
                        }
                    }
                }
                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val result = IntArray(rows) { -1 }
        for (j in 1..n) {
            val i = p[j]
            if (i in 1..rows && j <= cols && cost[i - 1][j - 1].isFinite()) result[i - 1] = j - 1
        }
        return result
    }
}
