# SurfCam

Application Android qui filme un spot de surf sur trépied et n'enregistre que les vagues surfées.

## Principe

- La caméra filme en continu en 4K (60 i/s si le téléphone le permet), mise au point et exposition verrouillées.
- Les 8 dernières secondes encodées (H.265) sont gardées en mémoire, rien n'est écrit sur le disque tant qu'aucune vague n'est détectée.
- Quand une vague est confirmée, le clip commence 5 s avant le take-off et se termine 3 s après la fin de la vague.
- Les clips sont enregistrés dans `Movies/SurfCam`.

## État d'avancement

| Étape | Contenu | État |
|---|---|---|
| 1 | Capture 4K, tampon circulaire, clips sur commande manuelle, service d'arrière-plan | en cours de test |
| 2 | Détection et suivi d'un surfeur (vidéos enregistrées d'abord) | à faire |
| 3 | Détection automatique des vagues, recadrage zoomé 16:9 | à faire |

## Installer sur le téléphone

Chaque envoi sur `main` compile l'APK et le publie dans la release
[debug-latest](../../releases/tag/debug-latest). Ouvrir cette page depuis le téléphone, télécharger `app-debug.apk`, l'installer.

## Architecture

```
domain/   modèles et tampon circulaire (Kotlin pur, testé)
data/     caméra Camera2, encodeur H.265, écriture MP4, moteur de capture
service/  service de premier plan (type caméra)
presentation/  écran Compose
```
