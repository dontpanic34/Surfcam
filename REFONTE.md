# Refonte SurfCam — état au 05/11/2026 (soir)

## Décidé avec l'utilisateur
- Refonte complète autorisée. Nouvelle appli « SurfCam 2 » installée À CÔTÉ de l'actuelle (applicationId à part, release séparée).
- Film de session complète : option, coupée par défaut. Tous les écrans seront refaits.
- But : retrouver l'utilisateur (manchon jaune/orange), dézoomer pour chercher, zoomer, suivre, ne filmer que ses vagues.

## Fait (module `:core`, Kotlin pur, testé sur JVM : `./gradlew :core:test`)
- `geometry/CameraModel` : pixels ↔ degrés. `heading/HeadingTimeline` : cap à l'heure de l'image (+ reconstruction depuis un rapport).
- `tracking/Tracker` : pistes en repère fixe (degrés), Kalman, affectation hongroise, contrôle de taille, preuve du manchon
  révocable, une seule piste « moi », seconde chance pour la piste « moi ».
- `ride/RideDetector` : vote sur plusieurs images (insensible aux images ratées), décision sur la course entière, seuils fixes versionnés (plus d'apprentissage en ligne).
- `director/Director` : attente au pic → recherche large → accrochage → suivi → perdu ; jamais de zoom pendant un clip. `GimbalPlanner`, `PeakEstimator`.
- `clip/ClipPlanner`, `SurfBrain` (façade), `sim/Simulator` (session simulée avec diagnostic des vagues ratées).

## Résultats du simulateur (5 sessions de 30 min)
- Mode moi : ≥ 70 % des vagues de l'utilisateur filmées, toujours retrouvé, 0 zoom pendant un clip. ✅
- Mode tout le monde : 86/99 vagues « filmables » filmées (87 %), précision 98 %. ✅ Correction : le pilote tourne déjà vers
  une piste qui a un vote de départ (avant confirmation), sinon un surfeur parti près du bord sortait du téléobjectif.

## Constat sur la session réelle du 05/11 (rapport complet lu)
- 41 des 44 rejets : déclenchement → pas de gimbal immédiat (médiane 4,4°) → piste perdue ~1 s après → rejet (seuil appris 4,8 s).
- HDR coupé, chaleur toujours niveau 0, seuils appris actifs (4,8 s, confiance 0,44), surfeurs 47 px en médiane.

## Mode caméraman (demandé le 05/11 au soir)
- Mode tout le monde : regarde le line-up au zoom large (`watchZoom` ×2), tourne dès un départ possible, zoome à ×5 quand la
  vague part (le clip commence large et se resserre), suit par panoramique continu (`GimbalStyle.RIDE`, ordres courts enchaînés).
- Simulateur : 120/139 vagues filmables (86 %), 164/299 vagues filmées, précision 88 % (était 98 % : à surveiller).
- Limite : le passage ×2 → ×5 change d'objectif (coupure ~0,5–1 s au début du clip). Piste : rester sur l'objectif principal
  jusqu'à ×4 numérique, ou deux objectifs dans la même session (à tester sur le Pixel).

## Outils de mesure
- `journal/FrameJournal` : journal de capteurs par session (chaque image : cap, champ, zoom, détections, taches ; événements).
  À écrire par l'appli à chaque session puis à rejouer dans le banc — c'est l'outil principal pour améliorer l'appli.

## Demandes utilisateur en attente
- Refonte du rendu de l'appli (toutes les pages). Mise à niveau du téléphone (roulis). Batterie : 80-90 % en 1h20, à réduire
  après avoir obtenu le meilleur suivi/déclenchement.

## Appli (début d'intégration)
- `applicationId = com.surfcam.app2`, nom « SurfCam 2 », dépend de `:core` ; l'APK se compile (moteur encore l'ancien).
- `GyroYaw.timeline` : historique horodaté du cap (µs, horloge capteur) pour le cap à l'heure de chaque image.

## Reste à faire
1. Brancher `:core` dans l'appli : remplacer SurferTracker/WaveDetector/ClipPlanner/GimbalFollower par SurfBrain dans
   AnalysisPipeline + RecordingEngine (cap via GyroYaw.timeline.at(horodatage image), vérifier la base de temps caméra),
   journal de capteurs par session, stockage téléphone puis copie SD silencieuse), nouvelle interface, CI « SurfCam 2 ».
2. Récupérer le banc de l'ancienne session (demande envoyée : branche `banc-2026-11-05`) et rejouer le 05/11.
3. Test réel : deux objectifs dans la même session (Pixel 10 Pro), détection du manchon en vue large.

## Environnement
SDK Android dans /opt/android-sdk (environnement « Android (SurfCam + SurfForecast) », dl.google.com autorisé).
Miroir Maven Google via ~/.gradle/init.d/mirror.gradle.kts (Maven Central renvoie parfois 429).
