package com.surfcam.app.data.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.surfcam.app.domain.detection.Letterbox
import com.surfcam.app.domain.detection.YoloDecoder
import com.surfcam.app.domain.tracking.Detection
import java.nio.FloatBuffer

/**
 * YOLOv8 nano exporté en ONNX (entrée 1280×736), exécuté sur le téléphone avec ONNX Runtime.
 *
 * Le modèle peut tourner de trois façons : sur le processeur (comme avant), sur le processeur avec la bibliothèque XNNPACK, ou
 * sur les accélérateurs du téléphone (NNAPI : puce graphique ou puce IA). Au premier lancement, les trois sont chronométrées sur
 * la même image d'essai ; la plus rapide dont le résultat concorde avec celui du processeur est gardée pour les fois suivantes.
 * Le choix et les temps mesurés sont écrits dans le rapport de session.
 */
class OnnxPersonDetector(context: Context) : AutoCloseable {

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val decoder = YoloDecoder()
    private val output = FloatArray(OUTPUT_ROWS * ANCHORS)

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val prefs = context.applicationContext.getSharedPreferences("detecteur", Context.MODE_PRIVATE)
        var chosen: OrtSession? = null
        // Garde-fou : si l'appli s'est arrêtée net pendant l'essai d'un accélérateur (plantage qu'on ne peut pas rattraper), une
        // marque est restée. On ne réessaie plus jamais cet accélérateur : retour définitif au processeur.
        prefs.getString(KEY_TRYING, null)?.let { crashed ->
            prefs.edit().remove(KEY_TRYING).putString(KEY_BACKEND, CPU).putString(KEY_REPORT, "$crashed a fait planter l'appli pendant son essai : écarté, modèle sur le processeur").commit()
        }
        val saved = prefs.getString(KEY_BACKEND, null)
        if (saved != null && saved != CPU) {
            // Choix déjà fait lors d'un lancement précédent : on l'ouvre directement, sans rechronométrer.
            prefs.edit().putString(KEY_TRYING, saved).commit()
            chosen = try { environment.createSession(model, optionsFor(saved)) } catch (_: Throwable) { null }
            prefs.edit().remove(KEY_TRYING).commit()
            if (chosen == null) prefs.edit().putString(KEY_BACKEND, CPU).putString(KEY_REPORT, "$saved ne s'ouvre plus : retour au processeur").apply()
        }
        if (chosen == null && saved == null) chosen = benchmark(model, prefs)
        session = chosen ?: environment.createSession(model, optionsFor(CPU))
        inputName = session.inputNames.first()
    }

    private fun optionsFor(backend: String): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        when (backend) {
            NNAPI -> addNnapi()
            XNNPACK -> {
                setIntraOpNumThreads(1)
                addXnnpack(mapOf("intra_op_num_threads" to THREADS.toString()))
            }
            else -> setIntraOpNumThreads(THREADS)
        }
    }

    /**
     * Chronomètre chaque façon de faire tourner le modèle sur la même image d'essai et rend la session la plus rapide, à condition
     * que son résultat concorde avec celui du processeur. Toute erreur écarte le candidat : au pire on reste comme avant.
     */
    private fun benchmark(model: ByteArray, prefs: android.content.SharedPreferences): OrtSession? {
        val shape = longArrayOf(1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
        // Image d'essai reproductible (dégradés et motifs), pour comparer les résultats d'un candidat à l'autre.
        val probe = FloatArray(3 * MODEL_HEIGHT * MODEL_WIDTH) { i -> ((i * 2654435761L ushr 8) and 0xFF).toFloat() / 255f * 0.5f + (i % MODEL_WIDTH).toFloat() / MODEL_WIDTH * 0.5f }
        fun time(s: OrtSession, out: FloatArray): Long {
            val name = s.inputNames.first()
            var best = Long.MAX_VALUE
            repeat(3) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(probe), shape).use { tensor ->
                    s.run(mapOf(name to tensor)).use { r -> (r.get(0) as OnnxTensor).floatBuffer.get(out) }
                }
                best = minOf(best, android.os.SystemClock.elapsedRealtime() - t0)
            }
            return best
        }
        val notes = ArrayList<String>()
        val reference = FloatArray(OUTPUT_ROWS * ANCHORS)
        val cpu = try { environment.createSession(model, optionsFor(CPU)) } catch (_: Throwable) { return null }
        val cpuMs = try { time(cpu, reference) } catch (_: Throwable) { cpu.close(); return null }
        notes += "processeur $cpuMs ms"
        var bestSession = cpu
        var bestName = CPU
        var bestMs = cpuMs
        for (backend in listOf(XNNPACK, NNAPI)) {
            var s: OrtSession? = null
            prefs.edit().putString(KEY_TRYING, backend).commit()
            try {
                s = environment.createSession(model, optionsFor(backend))
                val out = FloatArray(OUTPUT_ROWS * ANCHORS)
                val ms = time(s, out)
                // Concordance : les scores « personne » (ligne 4) doivent être les mêmes qu'au processeur, à 0,05 près.
                var worst = 0f
                for (i in 4 * ANCHORS until 5 * ANCHORS) {
                    val d = kotlin.math.abs(out[i] - reference[i])
                    if (d.isNaN()) { worst = Float.MAX_VALUE; break }
                    if (d > worst) worst = d
                }
                val agrees = worst <= 0.05f
                notes += "$backend $ms ms" + if (agrees) "" else " (résultat différent du processeur, écart %.2f : écarté)".format(worst)
                // Il faut un vrai gain (au moins 20 %) pour quitter le réglage connu.
                if (agrees && ms < bestMs * 0.8) {
                    bestSession.close()
                    bestSession = s
                    bestName = backend
                    bestMs = ms
                    s = null
                }
            } catch (t: Throwable) {
                notes += "$backend indisponible (${t.javaClass.simpleName})"
            } finally {
                s?.close()
                prefs.edit().remove(KEY_TRYING).commit()
            }
        }
        prefs.edit().putString(KEY_BACKEND, bestName).putString(KEY_REPORT, "essai du modèle : ${notes.joinToString(" · ")} → retenu : $bestName ($bestMs ms par image)").apply()
        return bestSession
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
        private const val CPU = "processeur"
        private const val XNNPACK = "XNNPACK"
        private const val NNAPI = "accélérateur NNAPI"

        // Le nom change avec le modèle : un nouveau modèle sera rechronométré.
        private const val KEY_BACKEND = "moteur_$MODEL_ASSET"
        private const val KEY_REPORT = "essai_$MODEL_ASSET"
        private const val KEY_TRYING = "essai_en_cours"

        /** Ce qui a été mesuré et retenu pour faire tourner le modèle, pour le rapport de session ; null avant le premier essai. */
        fun choiceReport(context: Context): String? =
            context.applicationContext.getSharedPreferences("detecteur", Context.MODE_PRIVATE).getString(KEY_REPORT, null)

        /** Oublie le choix : le prochain lancement rechronomètre tout (à faire après une mise à jour du téléphone, par exemple). */
        fun forgetChoice(context: Context) {
            context.applicationContext.getSharedPreferences("detecteur", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
