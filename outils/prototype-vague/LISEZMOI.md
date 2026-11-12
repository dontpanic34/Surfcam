# Prototype « point sombre qui file le long d'une vague »

Essai hors du téléphone (Python, OpenCV) de l'idée de l'utilisateur du 12/11/2026 : repérer l'écume et la face de la vague,
puis un point sombre qui s'y déplace sur le côté = un surfeur qui surfe.

- `wavespot.py film.mp4 heure_de_debut_en_s` : une ligne JSON par départ trouvé.
- `wsviz.py film.mp4 t1 t2 sortie.jpg` : six images avec l'écume (bleu) et les points sombres (rouge près de l'écume).
- `evalws.py [trajet_min] [durée_min]` : compare aux vraies vagues du 12/11 repérées à l'image.

Premier résultat (12/11, 11 vraies vagues sur 22 minutes de film au téléobjectif) : 5 trouvées, mais environ 60 fausses
alertes. L'écume est bien repérée et le surfeur y apparaît bien comme un point sombre ; ce qui manque : suivre les points
quand la caméra bouge (sur le téléphone, avec le gyroscope) et écarter les creux sombres des vagues. À reprendre sur le film
témoin large, avec le journal de capteurs.

## Essai sur le 05/11 (film complet de 53 min, 35 vraies vagues étiquetées parmi les déclenchements de la V1)

- v3 (compensation par le cap du rapport) : 1 à 2 vraies sur 35. Le cap du rapport est trop grossier et décalé dans le temps,
  et le recalage par l'image échoue sur de l'eau qui bouge.
- v4 (point sombre collé à l'écume au moins 1,2 s, sans compensation) : 23/35, mais environ 200 autres alertes (3,7/min).
- v5 (silhouette sombre et haute seulement) : 21-22/35, environ 150 autres alertes (2,8/min).
- v5 + confirmation par l'IA (une personne détectée au même endroit) : 22/35, 2 fausses connues sur 7, 69 autres alertes
  (1,3/min). Échantillon de 12 vérifié à l'image : 2 à 3 vraies vagues que la V1 n'avait pas déclenchées, le reste des
  surfeurs qui rament ou attendent, de l'écume, un bateau.
- Pour comparaison, la V1 du 05/11 couvrait 25/35 avec peu de fausses alertes (banc, branche banc-2026-11-05).

Conclusion : l'idée est juste à l'image, mais ces règles ne font pas mieux que la détection actuelle. À reprendre sur un
film témoin large accompagné du gyroscope à pleine cadence (journal de capteurs de la V2).
