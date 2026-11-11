# SurfCam — ce que l'appli doit faire

Version 2 du 10/11/2026, rédigée avec l'utilisateur. C'est la référence : toute décision de conception s'y rapporte.

## But

Une appli Android qui remplace un caméraman de surf, comme un SoloShot mais sans balise électronique. Le téléphone est sur
un gimbal motorisé, sur la plage. Uniquement par l'image, l'appli retrouve les surfeurs, les suit, et n'enregistre un clip
que lorsque quelqu'un surfe une vague.

## Matériel

- Pixel 10 Pro, téléobjectif ×5 optique. Le suivi et les clips se font en ×5 optique, jamais en zoom numérique.
- Gimbal DJI Osmo Mobile 4, piloté en Bluetooth. Il accepte des ordres de rotation mais ne renvoie pas sa position : le cap
  réel est mesuré par le gyroscope du téléphone.
- Trépied sur la plage, à environ 50 à 150 m du pic (estimation, à mesurer).
- Hub USB-C branché au téléphone, avec une batterie externe et une carte SD. Le téléphone a peu de place libre : les vidéos
  doivent finir sur la carte SD.
- L'utilisateur porte un manchon orange au bras ; il est le seul à l'eau à en porter un.
- À l'eau : de l'utilisateur seul jusqu'à une dizaine de surfeurs.

## Qui filmer

- Par défaut, tout le monde : l'appli filme n'importe quel surfeur qui part sur une vague.
- Dès qu'elle reconnaît l'utilisateur à son manchon, elle se concentre sur lui : elle le suit et filme ses vagues en priorité.
- Si elle le perd durablement, elle revient au mode tout le monde en continuant à le chercher.

## Décision du 11/11/2026 : le manchon est abandonné

Cette décision de l'utilisateur remplace tout ce qui, dans ce document, parle du manchon (matériel, « Qui filmer », point 2 de
la liste ci-dessous, « Démarrage d'une session »).

- L'appli filme tout le monde : n'importe quel surfeur qui part sur une vague. Elle ne cherche plus à reconnaître l'utilisateur
  pendant la session, et il n'y a plus de réglage « Qui filmer ».
- L'utilisateur retrouve ses vagues après la session, dans « Trier mes vagues » : l'appli montre les surfeurs (silhouettes) et
  il désigne lequel il est. Cible : un seul appui par surfeur, comme sur le site SurfBuddy, qui trie par l'image seule des
  vidéos déposées, sans manchon ni brassard.

## Ce que l'appli doit savoir faire, par ordre d'importance

1. **Ne pas manquer une vague.** C'est le pire échec. Un clip en trop est acceptable, une vague manquée ne l'est pas.
2. **Reconnaître l'utilisateur** grâce au manchon, parmi des surfeurs tous en combinaison sombre, et ne pas le confondre
   avec un autre quand ils se croisent.
3. **Le suivre en permanence**, dès sa mise à l'eau : marche dans l'eau, passage de la barre, rame, attente assis, vague.
   Le mouvement du gimbal est fluide, comme celui d'un caméraman : pas d'à-coups, pas de balayage permanent.
4. **Savoir si quelqu'un surfe.** Un clip démarre quelques secondes avant le départ et s'arrête quelques secondes après la
   fin de la vague. Ramer, attendre, faire un canard ou marcher ne déclenche rien. Une vague courte ou terminée par une
   chute reste une vague : la durée n'est pas un critère.
5. **Se replacer.** L'utilisateur disparaît souvent (canard, écume, chute, sortie du cadre). L'appli vise d'abord là où il
   doit ressortir, puis le recherche en balayant la zone du pic avec le gimbal, toujours en ×5, sans dézoomer. Le pic se
   déplace avec la marée : sa position est réestimée pendant la session.
6. **Ne jamais changer de zoom d'elle-même.** Voir « Zoom » ci-dessous.

## Zoom (ajout du 10/11/2026 au soir, décidé par l'utilisateur)

- Aucun zoom numérique, nulle part dans ce qui est enregistré. Tout ce qui est filmé l'est en ×5 sur le téléobjectif optique.
- Si le téléobjectif ne peut pas être ouvert, l'appli ne démarre pas l'enregistrement et le dit, au lieu de recadrer
  l'objectif principal sans prévenir.
- Le ×1 ou ×2 ne sert qu'à l'utilisateur, à la main, avant de lancer l'enregistrement, pour viser en regardant l'écran. Dès
  que l'enregistrement démarre, l'appli passe en ×5 et n'en bouge plus.
- L'objectif réellement utilisé est écrit dans le rapport de session et visible à l'écran.
- Tournage en 1080p, 30 images par seconde.

## Zone du pic (ajout du 10/11/2026 au soir, décidé par l'utilisateur)

- Avant de lancer, en vue large, l'utilisateur appuie sur « Définir le pic » et trace un rectangle sur l'image : où le pic
  commence et où il finit. L'appli convertit ce rectangle en directions (degrés).
- L'appli attend au centre de cette zone et, quand elle cherche l'utilisateur, balaie cette zone et pas au-delà.
- La zone suit le pic quand il se déplace (marée) : son centre se recale sur les départs de vague observés. L'utilisateur
  peut aussi la retracer.

## Démarrage d'une session

L'utilisateur lance l'appli sur la plage, puis va à l'eau. L'appli le voit entrer dans l'eau avec son manchon et le suit à
partir de là. Si elle ne l'a pas vu entrer, elle le cherche au pic.

## Qualité et endurance

- La définition (1080p ou 4K) est libre : ce qui compte est que les vagues soient nettes, avec un horizon droit. Mise à
  niveau automatique au démarrage.
- Une session dure 1 h à 1 h 30, parfois plus. Aucun mode ne dégrade l'image ou le suivi pour économiser la batterie.
- L'enregistrement se fait sur la carte SD. Si la carte décroche, l'appli continue sur le téléphone et le dit clairement.
- L'appli ne supprime jamais une vidéo d'elle-même.

## Méthode imposée

- Chaque changement du suivi ou de la détection est mesuré sur des sessions réelles enregistrées (film étiqueté du 05/11 et
  suivantes) avant d'être livré. Pas de réglage à l'aveugle, pas de validation sur simulateur seul.
- Chaque session produit un journal complet (images analysées, cap, ordres au gimbal, décisions) qui permet de la rejouer
  et de comprendre chaque raté.
- Une livraison dit ce qui a été vérifié et sur quoi : banc, téléphone, ou rien.

## Hors du cœur

Bibliothèque, lecteur, montage, export vers le Drive, récupération des clips rejetés : on garde ce qui existe et marche,
sans y passer de temps tant que les points 1 à 5 ne sont pas fiables.

## Données de référence

- Banc du 05/11 : branche `banc-2026-11-05`, dossier `banc/` (détections sur le film entier, 35 vraies vagues étiquetées,
  rejeu du code, rapport de session, historique complet du projet).
- Session du 06/11 (manchon orange porté) : vagues, rejets, film de session et rapports sur le Drive de l'utilisateur,
  téléchargeables depuis le dossier partagé.

## Constats des versions précédentes à ne pas reproduire

- L'analyse tournait à moins de 2 images par seconde : trop lent pour suivre un surfeur à ×5.
- Le suivi raisonnait en pixels sans tenir compte de la rotation du gimbal.
- Le gimbal avançait par pas suivis d'une pause, sans mesure du cap réel.
- La détection de vague a été réglée seuil par seuil, sans mesure d'ensemble ; un apprentissage automatique des seuils a
  écarté de vraies vagues.
- Le 06/11, l'image des clips est molle et l'horizon penche d'environ 3° : le téléobjectif n'était pas forcé, le ×5 était
  très probablement numérique.
- La V2 a été livrée après validation sur simulateur uniquement, sans essai sur le téléphone.

## Pistes gardées en tête (pas encore décidées)

- **Vue large en coulisse** (idée de l'utilisateur, 11/11/2026) : pendant que le téléobjectif filme en ×5, l'objectif principal
  analyse tout le pic (×1 ou ×2, jamais enregistré) pour repérer un départ hors du champ du téléobjectif et y tourner le gimbal.
  Ne sert pas à reconnaître le manchon (moins d'un pixel en vue large). À faire seulement après : l'essai de l'appli au
  téléobjectif, la mesure de la vitesse réelle du modèle accéléré, et un test sur le téléphone montrant que les deux objectifs
  s'ouvrent en même temps.
- **Qualité d'image** : si l'image au téléobjectif reste en dessous de l'appli Google, remettre le HDR 10 bits et/ou la 4K
  (le code caméra sait encore les faire, seuls les réglages ont été retirés).
- **Modèle de détection** : le remplacer par un modèle entraîné sur les films de l'utilisateur (surfeurs petits, allongés, dans
  l'écume). Chantier à part.
- **Manchon** : sa couleur vire au rouge-rosé dans l'eau et l'appli ne le reconnaît plus ; à régler sur le film entier du 06/11,
  ou remplacer par un repère plus grand et toujours hors de l'eau.
- **Reeve** (reeve-app.com, iPhone, lu le 11/11/2026) : même principe que SurfCam (trépied à 50-150 m, gimbals DJI dont l'OM 4,
  sans bracelet ni montre, tout sur le téléphone). Deux choix à retenir : elle filme tout le monde et trie les vagues par
  surfeur après la session (l'utilisateur associe les siennes en quelques gestes) au lieu de reconnaître quelqu'un en direct ;
  et elle conseille un champ un peu large plutôt que serré pour mieux suivre chaque vague.
- **Montre** : pas de liaison directe possible à 100 m (Bluetooth trop court, coupé par l'eau). Utilisable après coup (heures
  des vagues enregistrées par la montre, recoupées avec les clips) ou en direct seulement avec une montre 4G qui envoie sa
  position GPS par le réseau mobile.
- **Présentation visée pour « Trier mes vagues »** (capture SurfBuddy montrée par l'utilisateur le 11/11/2026) : une rangée
  « Choisis ton surfeur », une case par surfeur avec sa silhouette détourée (fond retiré), son nombre de vagues et leur durée
  totale, plus une case « non classées » ; un seul appui désigne toutes les vagues de ce surfeur. Chez SurfBuddy le
  regroupement vient de brassards GPS, pas de l'image. À construire chez nous à partir des vignettes et des étiquettes de la
  première session exportée (dossier Silhouettes + fichier mes-vagues), en mesurant la qualité du regroupement dessus.
- **Horizon et Osmo Mobile 4** (mesuré le 11/11/2026, rapport 22h51) : l'ordre de rotation relative a trois champs ; le 1er
  tourne à gauche/droite, le 3e incline de haut en bas, le 2e ne fait rien. Le gimbal n'accepte donc aucun ordre de roulis :
  c'est lui qui tient l'horizon (mode Suivi), et sa référence se corrige par « Réglage horizontal » dans DJI Mimo. L'appli
  n'envoie plus d'ordre de roulis. Piste restante si l'image reste penchée : redresser l'image elle-même (rotation, donc
  léger recadrage), à décider par l'utilisateur.
