# Aerial Views Launcher

Plugin Kiosk Satellite qui utilise l'app [Aerial Views](https://github.com/theothernt/AerialViews) comme screensaver.

- Quand le screensaver de Kiosk Satellite démarre, Aerial Views s'ouvre par-dessus, directement en mode screensaver.
- Quand quelqu'un arrive, le kiosque revient au premier plan. Le retour se déclenche sur mouvement ou visage (caméra de Kiosk Satellite), présence, mot d'activation, conversation vocale, appel intercom ou un capteur Home Assistant de votre choix.
- Toucher Aerial Views le ferme et ramène le kiosque, une fois le geste réglé dans Aerial Views (voir plus bas).
- En option, l'écran s'éteint après un délai passé dans Aerial Views.

## Installation

Installez d'abord [Aerial Views](https://github.com/theothernt/AerialViews) sur la tablette.

### Bouton d'installation

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=sonic1200/ks-aerial-views)

### Manuelle

1. Ouvrez **Paramètres > Plugin Manager** dans Kiosk Satellite et activez **Enable Plugins**.
2. Choisissez **Add plugin**, collez `https://github.com/sonic1200/ks-aerial-views` et choisissez **Preview**.
3. Relisez le plugin, choisissez **Trust and install**, puis activez **Aerial Views Launcher** depuis sa ligne.

## Réglages conseillés dans Kiosk Satellite

| Réglage | Pourquoi |
| --- | --- |
| **Screensaver** activé, mode **Black** | Le screensaver de KS ne sert qu'à déclencher Aerial Views. Black évite un flash avant l'ouverture. |
| **Caméra** : détection de mouvement et/ou de visage activée | Ce sont les sources de réveil principales. |
| **Écoute en arrière-plan** (background listening) activée | Pendant qu'Aerial Views est affiché, Kiosk Satellite est en arrière-plan. Ce réglage garde la caméra et le mot d'activation actifs. |
| Permission Android **« Afficher par-dessus les autres apps »** pour Kiosk Satellite | Android 10 et plus empêche une app en arrière-plan de revenir au premier plan sans cette permission. |
| *Optionnel* : **Shizuku** avec accès accordé à Kiosk Satellite | Retour garanti même si Android bloque l'autre méthode. Le plugin l'utilise tout seul en secours (méthode **Auto**). |
| Mode kiosque : autoriser Aerial Views | Si le mode kiosque verrouille l'écran sur KS, Android refuse d'ouvrir une autre app. |

## Réglage indispensable dans Aerial Views

Par défaut, Aerial Views **ignore** les touchers à l'écran. Pour qu'un toucher le ferme et ramène le kiosque :

**Aerial Views > D-Pad/Remote, Tap & Swipe Gestures > Screen Swipe & Tap Gestures > Single Tap** : choisissez l'action de sortie du screensaver (**Exit**), pas « Exit to settings ».

Il n'est pas nécessaire d'activer « Start screensaver on launch » : le plugin ouvre directement l'activité du screensaver (`TestActivity`).

## Réglages du plugin

| Réglage | Effet |
| --- | --- |
| Ouvrir Aerial Views avec le screensaver | Interrupteur principal. Éteint, Aerial Views ne s'ouvre que depuis l'action **Ouvrir Aerial Views**. |
| Activité à ouvrir | Par défaut `com.neilturner.aerialviews/.ui.screensaver.TestActivity`. |
| Revenir sur mouvement / visage / présence / mot d'activation | Sources de réveil de Kiosk Satellite. |
| Capteur Home Assistant | Le kiosque revient quand ce capteur passe à `on`. C'est le plus fiable si la tablette coupe la caméra en arrière-plan. |
| Ignorer les détections après l'ouverture | Délai de grâce, 5 s par défaut, pour qu'un mouvement juste au départ ne referme pas Aerial Views. |
| Méthode de retour au kiosque | **Auto** (Android, puis Shizuku), **Android** ou **Shizuku**. |
| Éteindre l'écran après | Après ce délai, revient au kiosque et éteint l'écran. Aerial Views revient au screensaver suivant. 0 = jamais. |

Actions disponibles pour les gestes, le tiroir du kiosque et les boutons Home Assistant : **Ouvrir Aerial Views** et **Revenir au kiosque**.

L'état du plugin s'affiche sur sa page dans Plugin Manager : prêt, ouvert, retour et pourquoi, ou ce qui bloque.

## Données et permissions

Le plugin n'envoie rien sur le réseau. Il lit l'état du screensaver, de l'écran et des détections de Kiosk Satellite (`host.read`), utilise ses commandes temporaires comme réinitialiser le délai du screensaver ou éteindre l'écran (`host.control`), et lance au besoin `am start` via Shizuku si vous l'avez configuré (`shizuku`). Le capteur Home Assistant optionnel est lu par la connexion existante de Kiosk Satellite.

## Limites

- Le réveil par la caméra suppose que Kiosk Satellite garde la caméra en arrière-plan. C'est le cas avec l'écoute en arrière-plan sur la plupart des appareils, mais pas sur tous (certains Samsung, par exemple). Dans ce cas, utilisez un capteur Home Assistant.
- Les alarmes de Kiosk Satellite ne sont pas visibles par les plugins : le plugin ne revient pas tout seul au kiosque quand une alarme sonne.
- Le plugin utilise des API Android publiques en plus du SDK Kiosk Satellite (ouvrir une activité, suivre le premier plan). Il ne touche à aucune classe interne de Kiosk Satellite.

## Compiler

JDK 17+ et un SDK Android avec `platforms;android-35` et `build-tools` :

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

Le ZIP est créé dans `dist/`.

## Avertissement

Ce plugin n'est affilié ni à Aerial Views ni à Kiosk Satellite. Il ouvre simplement l'app Aerial Views installée sur l'appareil.
