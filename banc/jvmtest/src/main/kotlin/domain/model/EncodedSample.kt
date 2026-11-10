package com.surfcam.app.domain.model

/** Une image vidéo déjà encodée (H.265), telle que sortie de l'encodeur. Immuable une fois créée. */
class EncodedSample(
    val data: ByteArray,
    val ptsUs: Long,
    val isKeyFrame: Boolean,
)
