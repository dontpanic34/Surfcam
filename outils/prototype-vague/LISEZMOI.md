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
