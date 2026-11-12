# SurfCam V2 — dossier pour un second avis (12/11/2026)

Tu relis le travail fait sur la branche `work/surfcam-v2-reprise-2026-11-10` du dépôt `dontpanic34/Surfcam` (commit `6c6c517`
ou plus récent). La référence fonctionnelle est `SPEC_SURFCAM_V2.md` à la racine : lis surtout « Décision du 11/11/2026 » et
« Pistes gardées en tête », qui remplacent des parties plus anciennes du document.

Réponds en français, de façon concrète. Pour chaque proposition : ce qu'il faut changer, pourquoi, et comment le mesurer sur
les données décrites plus bas.

## Le but en une phrase

Un Pixel 10 Pro sur un gimbal DJI Osmo Mobile 4, sur trépied à 50-150 m du pic, filme automatiquement au téléobjectif ×5
optique chaque surfeur qui part sur une vague, sans balise. Pire échec : une vague manquée.

## Décisions déjà prises (ne pas les remettre en cause sans raison forte)

- Tournage toujours au téléobjectif ×5 optique, 1080p 30 i/s ; aucun zoom numérique dans ce qui est enregistré.
- Le manchon de couleur est abandonné (illisible dans l'eau) : l'appli filme tout le monde ; le tri par surfeur se fait après
  la session (prénoms choisis par l'utilisateur, vignette de silhouette par vague, classement par ressemblance de couleurs).
- L'Osmo Mobile 4 n'accepte aucun ordre de roulis (mesuré : le champ 2 de l'ordre ne fait rien, le champ 3 incline de haut en
  bas). L'horizon est tenu par le gimbal lui-même ; l'appli n'envoie plus d'ordre de roulis.

## Architecture (lire le code)

- `core/` (Kotlin pur, testable sur ordinateur) : `SurfBrain` → `Tracker` (Kalman + association hongroise, repère fixe en
  degrés grâce au cap gyroscope horodaté), `RideDetector` (votes de départ puis décision en fin de course), `ClipPlanner`
  (marges 5 s avant / 3 s après), `Director` + `GimbalPlanner` (ordres de rotation relatifs au gimbal).
- `app/` : caméra Camera2 (`Camera2Source`), encodeur HEVC, détecteur YOLOv8n ONNX 1280×736 (`OnnxPersonDetector`, XNNPACK
  retenu après chronométrage : 273 ms/image, NNAPI 1 629 ms), `AnalysisPipeline`, `RecordingEngine`, écrans Compose.
- Rejeu : `core/src/test/kotlin/com/surfcam/core/ReplayJournalTest.kt` rejoue un journal de capteurs réel (variables
  SURFCAM_JOURNAL, SURFCAM_HFOV, SURFCAM_VOTES). Prototype hors téléphone : `outils/prototype-vague/`.

## Ce qui a été mesuré

1. **Session du 12/11** (36 min, 2 à 3 surfeurs, l'utilisateur estime une vingtaine de vagues) : 3 vagues gardées.
   - Cause principale trouvée dans le journal : le champ de vision transmis au cerveau était celui de l'objectif principal
     (70,7°) au lieu du téléobjectif (≈ 18°). Le gimbal tournait 4 à 5 fois trop (vitesse médiane 14°/s contre 3°/s en V1),
     et chaque rotation faisait croire que les surfeurs filaient (217 pistes pour 2-3 personnes). Corrigé.
   - Rejeu avec le bon champ : 11 départs avec l'ancien seuil (3 votes), 21 avec 2 votes (environ 12 vraies vagues vérifiées
     à l'image). Le seuil est passé à 2 votes étalés sur 0,3 s et le trajet minimal de 4 à 2 longueurs de corps.
   - Limite : rejeu en boucle ouverte (la caméra a réellement bougé avec l'ancien défaut).
2. **Film complet du 05/11** (V1, 53 min, 35 vraies vagues étiquetées parmi les déclenchements, rappel réel inconnu) :
   prototype « point sombre collé à l'écume » : 22-23/35, mais 1,3 à 3,7 alertes par minute hors des vagues connues ; avec
   confirmation par l'IA, ≈ 70 alertes en 53 min dont 2-3 sur 12 vérifiées à l'image étaient de vraies vagues. La V1 couvrait
   25/35. Le prototype a aussi trouvé de vraies vagues jamais déclenchées par la V1.
3. **Surfeurs minuscules** : 20 à 60 pixels de haut sur 1080 au ×5 ; confiance du détecteur souvent sous 0,5.
4. **Batterie** : 49 %/h (36 min).
5. **Champ** : la zone du pic tracée par l'utilisateur fait ≈ 44° ; le téléobjectif en voit ≈ 18°. La caméra attend au
   centre : les départs sur les côtés sont invisibles.

## Ce qui vient d'être ajouté, jamais essayé sur le téléphone

- **Témoin large** : l'objectif principal filme tout le pic en même temps que le téléobjectif (second flux physique de la même
  caméra logique, second encodeur HEVC 8 Mb/s). Retiré automatiquement si le téléphone refuse deux objectifs à la fois.
- Prénoms de surfeurs, suppression d'un jour depuis l'export, vitesse du gimbal calée sur la V1 (6°/s en vague).

## L'idée de l'utilisateur, à approfondir

Observer en permanence la zone du pic sur l'objectif principal (découpe de la zone en pleine résolution, analyse seulement),
repérer les vagues (face verte qui lève, écume blanche qui casse) et un point sombre qui file le long de la vague, puis
tourner le gimbal et déclencher le ×5 sur ce surfeur. Combinaison envisagée : guetteur sans IA sur la vue large + IA sur le
téléobjectif pour confirmer et suivre.

## Questions

1. Le guetteur « vague + point sombre » : quelles caractéristiques ou méthodes (flux optique, soustraction de fond stabilisée
   par le gyroscope, détection des lignes de déferlement, petit modèle entraîné…) te paraissent les plus prometteuses pour
   séparer « surfe » de « rame / attend / canard / écume », avec des cibles de 5 à 20 pixels ?
2. Analyse de la vue large : découper la zone du pic en tuiles à pleine résolution (cadence divisée par deux) ou réduire
   l'image ? Autre approche ?
3. Détecteur : vaut-il mieux convertir YOLOv8n vers LiteRT avec le délégué GPU du Pixel, passer à un modèle plus récent ou à
   une entrée plus grande, ou entraîner un modèle sur des silhouettes de surfeurs ? Dans quel ordre ?
4. `RideDetector` : le passage à 2 votes augmente le rappel mais une fausse alerte longue occupe le détecteur (une seule course
   à la fois) et peut masquer une vraie vague. Que proposes-tu (plusieurs courses en parallèle, durée maximale d'une course
   sans progression, autre) ?
5. Le témoin large : risques (bande passante, chauffe, refus de configuration, synchronisation des deux films) et façons de
   les limiter.
6. Qu'est-ce qui te paraît encore faux ou fragile dans le code actuel, en priorité pour une prochaine session à l'eau ?
