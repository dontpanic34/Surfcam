package com.surfcam.app.data.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.surfcam.app.domain.detection.Letterbox
import com.surfcam.app.domain.detection.YoloDecoder
import com.surfcam.app.domain.tracking.Detection
import java.nio.FloatBuffer

/** YOLOv8 nano exporté en ONNX (entrée 1280×736), exécuté sur le téléphone avec ONNX Runtime. */
class OnnxPersonDetector(context: Context) : AutoCloseable {

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val decoder = YoloDecoder()
    private val output = FloatArray(OUTPUT_ROWS * ANCHORS)

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) }
        session = environment.createSession(model, options)
        inputName = session.inputNames.first()
    }

    /** [input] : tenseur RGB [3][736][1280] déjà préparé ; renvoie les personnes trouvées, en pixels de l'image source. */
    fun detect(input: FloatArray, letterbox: Letterbox, timestampUs: Long): List<Detection> {
        val shape = longArrayOf(1, 3, letterbox.inputHeight.toLong(), letterbox.inputWidth.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val raw = result.get(0) as OnnxTensor
                raw.floatBuffer.get(output)
                return decoder.decode(output, ANCHORS, letterbox, timestampUs)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_ASSET = "yolov8n_736x1280.onnx"
        const val MODEL_WIDTH = 1280
        const val MODEL_HEIGHT = 736

        // 4 coordonnées + 80 classes, et le nombre d'ancres d'une entrée de 1280×736.
        private const val OUTPUT_ROWS = 84
        private const val ANCHORS = 19320
        private const val THREADS = 4
    }
}
