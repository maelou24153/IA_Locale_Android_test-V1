# IA Studio VM (Android)

Application Android qui installe des IA en local (chat + génération d'images), chacune dans une
« machine virtuelle » (conteneur isolé : modèles, sorties et système Linux proot optionnel).

## Compiler l'APK avec GitHub
1. Crée un dépôt GitHub et envoie-y tout le contenu de ce dossier (y compris `.github/`).
2. Onglet **Actions** → workflow **Build APK** → il se lance seul au push (≈ 20-40 min la 1re fois,
   car il compile llama.cpp et stable-diffusion.cpp pour Android).
3. Télécharge l'artefact **IA-Studio-VM-apk**, décompresse, installe l'APK sur le téléphone.

## Fonctions
- **Machines** : conteneurs isolés ; catalogue d'IA (Qwen2.5, Qwen3, Phi-3.5, Llama 3.2, Gemma 2, DeepSeek-R1,
  Qwen2.5-Coder 1.5B/3B/7B, SD 1.5, SD-Turbo) avec avertissement selon la RAM détectée ;
  **navigateur Hugging Face** (recherche et installation de n'importe quel modèle GGUF, jeton pour les modèles réservés) ;
  réglages (créativité, mémoire de conversation, threads).
- **Chat** : modes Assistant, Code et Prompt image ; blocs de code avec bouton Copier ; jointure de fichiers ; bouton Arrêter.
- **Projet** : génération d'un logiciel complet (plan, un fichier à la fois, relecture optionnelle), aperçu des
  fichiers, **export en .zip dans Téléchargements**, amélioration d'un projet existant, liste de projets.
- **Images** : création, modification d'une photo (image à image), prompt négatif, galerie, enregistrement dans Photos.
- **Aide** : la notice complète (`NOTICE.md`) est intégrée à l'appli.
- Style visuel chaleureux (tons crème et terracotta, titres en serif, mode sombre automatique).

## Limites à connaître
- Compatible ARM64 (presque tous les téléphones récents). Prévois 4 Go de RAM minimum, 6 Go+ conseillés.
- Génération d'images sur CPU : SD-Turbo ≈ 1-3 min en 512×512, SD 1.5 bien plus lent. Commence en 256×256.
- Le moteur s'arrête si Android ferme l'appli en arrière-plan : garde-la au premier plan.
- Une vraie machine virtuelle matérielle (QEMU) serait 10 à 50 fois plus lente pour l'IA : l'appli
  utilise donc des conteneurs (proot) et des moteurs natifs.
- Les liens de modèles du fichier `app/src/main/assets/catalog.json` pointent vers Hugging Face ;
  si l'un est introuvable, corrige l'URL dans ce fichier.
- Ce projet n'a pas pu être compilé ici : si le build GitHub échoue, envoie-moi le log de l'erreur.
