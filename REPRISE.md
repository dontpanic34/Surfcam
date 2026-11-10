# Reprise SurfCam — état au 10/11/2026

À lire en premier par la conversation qui reprend le projet. Le cahier des charges est dans `PROMPT.md` (même branche) :
c'est la référence de ce que l'appli doit faire. Ce fichier-ci dit où on en est, où sont les données et par quoi commencer.

## En une phrase

Un « SoloShot sans balise » : le Pixel 10 Pro sur un gimbal DJI Osmo Mobile 4 suit l'utilisateur (manchon orange au bras)
et n'enregistre que les vagues surfées. Deux versions existent, aucune n'est fiable. Décision prise avec l'utilisateur :
repartir sur une bonne base, à partir du code de la V1 et du cahier des charges, en mesurant tout sur des sessions réelles.

## Où sont les choses

| Quoi | Où |
|---|---|
| Cahier des charges | `PROMPT.md`, branche `claude/dazzling-mendel-1s5glk` |
| V1 « SurfCam » (v0.96), celle qui a servi à l'eau | branche `main`, commit `cdd5d07` |
| V2 « SurfCam 2 » (`com.surfcam.app2`), jamais validée à l'eau | branche `claude/vibrant-gates-viyb5r`, voir son `REFONTE.md` |
| Banc du 05/11 | branche `banc-2026-11-05`, dossier `banc/` (lire `banc/LISEZMOI.md`) |
| Historique complet de la V1 (35 Ko) | `banc/HANDOFF-SurfCam.md` sur la branche du banc |
| Session du 06/11 (vagues, rejets, film en morceaux de 2 min, rapports) | Drive, dossier `1DzVuDEImGaTb7xAhazf85BLRWm2_yq_i` |
| Film et rapport du 05/11 | Drive, dossier `15B4a0wAL5zwlmEonvmxlNxiof4tIlxH5` |
| APK de test V1 | release GitHub `debug-latest` (compilée à chaque envoi sur `main`) |

Télécharger une vidéo du Drive (vérifié le 10/11 sur un clip de 27 Mo) : trouver l'identifiant du fichier avec le connecteur
Google Drive (`search_files`, `parentId = '<dossier>'`), puis
`curl -L -o clip.mp4 "https://drive.usercontent.google.com/download?id=<ID>&export=download&confirm=t"`.
`ffmpeg` et `ffprobe` sont installés. Ne pas lire une vidéo ou un gros rapport par le connecteur Drive : le contenu arrive
en entier dans la conversation.

Sur le Drive, la session du 06/11 est incomplète (constat du 08/11) : session de 12h20, 12 morceaux complets sur 31 ;
plusieurs clips à 0 octet. Les 8 rapports sont complets.

Anciennes conversations, interrogeables par message (elles répondent, mais ne poussent rien sans accord direct de
l'utilisateur dans leur propre fil) : V1 `session_01993e2P1d3vrwTQEGDFk2PC`, V2 `session_01QwNRUGVemnPnmJFv9cUtgm`.

## Matériel et contraintes

- Pixel 10 Pro, Android 17. Téléobjectif ×5 optique (caméra physique id 6, 30 i/s au plus d'après le code). Pas de ×4 optique.
- Gimbal DJI Osmo Mobile 4, Bluetooth reconstitué (services FFF0/FFF5/FFF4, paquets DUML) : rotations relatives seulement,
  aucune lecture de position. Lacet fidèle (écart ≤ 0,3° mesuré au gyroscope), ordres de 1,0 à 1,6 s. Roulis jamais confirmé.
- Hub USB-C avec batterie externe et carte SD. Le téléphone a peu de place : enregistrer sur la carte (dans la V1, `SdMover`
  écrit d'abord sur le téléphone puis copie ; non confirmé à l'eau).
- Distance au pic 50 à 150 m (estimation). De l'utilisateur seul à une dizaine de surfeurs à l'eau.
- Manchon orange au bras, porté pour la première fois le 06/11. L'utilisateur est le seul à en porter un.

## Ce qui est mesuré

- Analyse V1 : YOLOv8n, ONNX Runtime sur processeur (4 fils), entrée 1280×736. Inférence 220 à 450 ms ; une image analysée
  toutes les 0,6 à 0,9 s en session ; décision 330 à 630 ms après l'image.
- 05/11 (banc) : 35 vraies vagues étiquetées, seulement parmi les déclenchements de l'appli (le rappel sur le film entier est
  inconnu). Couvertes par un clip gardé : 17 le jour même, 25 avec la v0.91. Sur les 10 restantes : 8 déclenchées puis
  coupées après 1,1 à 1,7 s, 2 jamais déclenchées. YOLO voit le surfeur sur plus de 90 % des images.
- 05/11 (rapport) : 41 rejets sur 44 sont des pistes perdues environ 1 s après le premier ordre au gimbal.
- 06/11 : 74 à 82 clips gardés en 1 h, surtout faux ; vraies vagues non comptées. Réglages : 1080p, 30 i/s, téléobjectif non
  forcé, HDR coupé. Surfeurs de 15 à 30 px de haut pour le détecteur.
- 06/11, une image du clip « 12h25 - Vague 1 » regardée : rendu mou, horizon penché d'environ 3°, surfeur d'environ 65 px
  de haut en 1080p. Le surfeur de ce clip n'est pas l'utilisateur.

## Ce qui est supposé, à vérifier

- Le 06/11, le ×5 était numérique (objectif principal recadré). Le rapport de session principal
  (`SurfCam-rapport-2026-11-06_12h20.txt`, 1,8 Mo) doit le dire.
- Le Pixel bascule seul sur le téléobjectif quand on règle le zoom à ×5 sur la caméra logique.
- La caméra et le gyroscope partagent la même horloge ; le signe du cap gyroscope par rapport à l'axe x de l'image.
- Le manchon orange est repérable à cette distance. Jamais vérifié sur une image réelle : c'est le premier contrôle à faire.

## Diagnostic

Les ratés de la V1 viennent de la conception, pas des seuils :

1. L'analyse est trop lente (moins de 2 images par seconde) pour suivre un surfeur qui traverse le champ ×5 en 2 à 3 s.
2. Le suivi (`SurferTracker`) raisonne en pixels sans tenir compte de la rotation du gimbal : les vitesses qui décident
   « c'est une vague » sont faussées dès que le gimbal bouge.
3. Le gimbal (`GimbalFollower`) avance par un pas puis une pause, et son cap est déduit des ordres envoyés, pas mesuré.
4. La détection de vague (`WaveDetector`) est une suite de seuils sur la vitesse en « longueurs de corps », réglés un par un.

La V2 corrige le point 2 sur le papier (module `:core` : pistes en degrés, cap gyroscope horodaté, pilote, journal de
capteurs) mais n'a été validée que sur un simulateur réglé à la main. Son seul essai à l'eau (08/11) : restée dézoomée, ×5
qui repasse en ×2, pas de mise à niveau. Un correctif a été livré le 08/11 et n'a jamais été testé. L'analyse y est restée
à 2 images par seconde. Sa branche contient par erreur des fichiers compilés (`core/build/`).

À reprendre de la V2 : l'idée du repère en degrés, `HeadingTimeline`, `FrameJournal`, la mise à niveau en boucle fermée.
À ne pas reprendre tel quel : le simulateur comme preuve, les changements de zoom du pilote.

## Plan proposé

1. **Vérifier le manchon.** Télécharger des clips et morceaux de session du 06/11, trouver l'utilisateur, mesurer la taille et
   la couleur du manchon à l'image. Repère pour le trouver : ce jour-là il surfait en backside (dos à la vague).
2. **Remettre le banc en marche** et retrouver la référence v0.91 : vraies [25, 8, 2], fausses [2, 2, 3] avec `ov.py`.
   Étiqueter ensuite le film entier, pas seulement les déclenchements, pour connaître le vrai rappel.
3. **Accélérer l'analyse** : viser 8 à 10 images par seconde (détecteur sur la puce graphique, analyse de la seule zone utile).
4. **Suivi en degrés** avec le gyroscope lu à chaque image. La V1 l'a essayé sans gain (25 → 26 vraies, 2 → 4 fausses) parce
   que le cap n'était connu que toutes les 0,6 s.
5. **Gimbal en continu**, corrigé par le cap mesuré.
6. **Zoom** : téléobjectif forcé pendant le suivi ; dézoom seulement pour retrouver le pic ou un surfeur perdu ; jamais
   pendant un clip.
7. **Vague jugée sur son trajet entier**, sans critère de durée.
8. Ensuite : horizon, carte SD, batterie, export.

Le film complet de la session est gardé en secours sur la carte SD (proposé à l'utilisateur, pas encore confirmé).

## Ce qui marche et qu'il ne faut pas casser

Capture et encodage HEVC, tampon et marges avant et après le clip, Bluetooth du gimbal et journal ORDRE/MESURE, rapports par
session et par clip, clips rejetés gardés et récupérables, export d'un jour relançable et sans suppression.

## Essayé puis abandonné dans la V1

- Pilotage du gimbal en vitesse : oscillations.
- Zone libre minuscule (mouvement perpétuel) ou large (attend le bord).
- Apprentissage automatique des seuils : il a écarté de vraies vagues. Il règle encore vitesse, posture et confiance : à couper.
- Mode éco batterie : refusé par l'utilisateur.
- Recherche du manchon en dézoomant : jamais vue marcher, elle recréait la session caméra.
- Durée minimale d'une vague : retirée à la demande de l'utilisateur (une chute reste une vague).
- Portée d'association élargie sans plafond : confusions entre deux personnes.

## Ce que l'utilisateur attend de la méthode

- Manquer une vague est le pire ; un clip en trop est acceptable.
- Ne jamais supprimer une vidéo. Un export a effacé des fichiers le 08/11.
- Ne pas retirer une fonction qui existait, ne pas dégrader l'image ou le suivi pour la batterie.
- Dire pour chaque livraison ce qui a été vérifié : banc, téléphone, ou rien. « À chaque mise à jour ça filme de moins en
  moins bien » est son constat sur la V1.
- Vérifier avant d'affirmer : le 10/11, un clip pris au hasard lui a été présenté alors que ce n'était pas lui.

## État de cette conversation

Aucun code de l'appli modifié. Livré : `PROMPT.md` et ce fichier, sur `claude/dazzling-mendel-1s5glk`. Les deux
conversations précédentes ont été lues par leur fin et par un bilan demandé à chacune, pas en entier.
