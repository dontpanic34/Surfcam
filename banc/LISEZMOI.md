# Banc d'évaluation hors ligne — session du 05/11 (film de 53 min)

## Contenu
- `harness/det_full.csv` : détections YOLOv8n (ONNX, entrée 1280×736) sur le film entier, ~3 images/s. Une ligne par boîte `t,score,x1,y1,x2,y2` (pixels 1280×720) ; `score = -1` marque une image analysée. `t` = temps du film (s). Produit par `harness/detect.py` sur le film fourni par l'utilisateur, décodé en SDR 8 bits (l'appli, elle, lit le flux P010/HLG : écart possible avec le terrain).
- `harness/det_fixm.csv` / `det_fixp.csv` : mêmes boîtes décalées en x par le cap gyroscope du rapport (≈ 75 px/°), signe − (physiquement correct) / signe +. `gyro.npy` : (temps film, cap gyro en °).
- `harness/truth.py` : étiquettes. Lit `rapport-session-2026-11-05.txt` (décisions Start/Stop/Discard) et marque les rejets vrais/faux par numéro (ensembles `real`, `seen_real`, `seen_false`). 35 vraies, 7 fausses, le reste « inconnu ». ATTENTION : étiquetées seulement parmi les déclenchements de l'appli, pas sur le film entier : le vrai rappel est inconnu.
- `jvmtest/` : projet Kotlin JVM qui rejoue le code de domaine de l'appli (copie de `domain/`) sur un CSV. `Replay.kt` lit des paramètres `clé=valeur` : speed, stop, minride, conf, share, aspect, endgrace, lostgrace, leaveride, gap, jump, extra, missing, mode=modern|legacy. La copie de `domain/` peut différer de main (elle contient l'essai « vitesse sur le trajet » dans followRide) : recopier `app/src/main/java/com/surfcam/app/domain/` avant un nouveau test.
- `rapport-session-2026-11-05.txt` : rapport de session de l'appli.
- `HANDOFF-SurfCam.md` : historique complet du projet.

## Calage
temps film = temps rapport (« +x s ») − 1,7 s (constante `OFFSET` / `OFF` dans evaluate.py / ov.py).

## Lancer (depuis banc/harness)
    python3 ov.py      # compare session, v0.89, v0.90, v0.90+gap 1 s
    python3 sweep.py   # ancienne métrique (départ à ±3 s)
Il faut gradle et un JDK 21 : evaluate.py appelle `gradle run -Pmain=ReplayKt` dans `../jvmtest`.

## Métrique (ov.py, recommandée)
Une vraie vague étiquetée est « couverte » si un déclenchement GARDÉ (START puis STOP, pas DISCARD) recouvre son intervalle élargi de ±1 s. Sortie : vraies [couvertes, déclenchées mais rejetées, rien] et fausses [gardées, rejetées, rien]. Référence v0.91 (gap 1 s) : vraies [25, 8, 2], fausses [2, 2, 3]. evaluate.py / sweep.py = ancienne métrique (début à ±3 s), trop pessimiste.

## Non inclus
Les vidéos (film de 53 min, extraits de plus de 50 Mo) : sur le Drive de l'utilisateur.
