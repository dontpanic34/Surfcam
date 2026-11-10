package com.surfcam.app.domain.detection

/**
 * Couleur fluo d'un manchon porté par l'utilisateur pour le retrouver parmi les surfeurs. Reconnue par sa teinte
 * (saturée et lumineuse), ce qui exclut le sable, la mer, le ciel et le néoprène.
 */
enum class MarkerColor(val label: String, private val hueFrom: Float, private val hueTo: Float) {
    YELLOW("Jaune", 55f, 77f),
    GREEN("Vert", 77f, 130f),
    ORANGE("Orange", 8f, 36f),
    ;

    fun matches(r: Int, g: Int, b: Int): Boolean {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < MIN_VALUE) return false
        val delta = max - min
        if (delta.toFloat() / max < MIN_SATURATION) return false
        val hue = when (max) {
            r -> 60f * (((g - b).toFloat() / delta) % 6f)
            g -> 60f * ((b - r).toFloat() / delta + 2f)
            else -> 60f * ((r - g).toFloat() / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return hue >= hueFrom && hue < hueTo
    }

    companion object {
        private const val MIN_VALUE = 140
        private const val MIN_SATURATION = 0.55f

        fun fromName(name: String?): MarkerColor? = entries.firstOrNull { it.name == name }

        /** Une silhouette porte le manchon si assez de ses pixels ont la teinte : au moins [MIN_PIXELS] et [MIN_FRACTION] de la boîte. */
        fun isWorn(matchingPixels: Int, totalPixels: Int): Boolean =
            totalPixels > 0 && matchingPixels >= MIN_PIXELS && matchingPixels.toFloat() / totalPixels >= MIN_FRACTION

        private const val MIN_PIXELS = 4
        private const val MIN_FRACTION = 0.003f
    }
}
