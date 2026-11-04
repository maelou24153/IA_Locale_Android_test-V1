# Notice d'IA Studio

Tout fonctionne sur ton téléphone : tes discussions, ton code et tes images ne quittent jamais l'appareil. Internet sert seulement à télécharger les IA.

## Avant de commencer
- **Téléphone** : ARM64 (presque tous les récents), Android 8 ou plus. Android 10 ou plus pour enregistrer les images et les .zip dans le téléphone.
- **Mémoire** : 4 Go de RAM minimum, 6 Go ou plus conseillés. L'onglet Machines affiche la RAM détectée et le type de modèle adapté.
- **Espace** : prévois 2 à 8 Go libres. Wi-Fi conseillé (de 0,4 à 5 Go par IA).
- Garde l'appli ouverte au premier plan quand une IA travaille, sinon Android peut l'arrêter.

## Étape 1 : créer une machine
- Onglet **Machines**, écris un nom (par exemple « maison »), appuie sur **Créer**.
- Une machine est un espace isolé : ses IA, ses projets et ses images ne se mélangent pas avec ceux des autres.

## Étape 2 : installer une IA
- Appuie sur **Installer** à côté d'une IA et attends la fin du téléchargement (il reprend s'il est interrompu).
- **Pour discuter** : Qwen2.5 1.5B ou 3B, Llama 3.2 3B, Gemma 2 2B.
- **Pour réfléchir** : Qwen3 4B et DeepSeek-R1 1.5B écrivent leur raisonnement avant la réponse.
- **Pour le code et les logiciels** : Qwen2.5-Coder 3B (recommandé), 1.5B (léger) ou 7B (le plus fort, 12 Go de RAM).
- **Pour les images** : SD-Turbo (rapide) ou Stable Diffusion 1.5 (qualité).
- Un avertissement orange signale une IA trop lourde pour ta RAM.

## Étape 3 : trouver n'importe quelle autre IA
- Dans **Parcourir Hugging Face**, choisis Chat/code ou Images, tape un nom (« llama », « coder », « stable diffusion »), puis **Chercher**.
- Touche un résultat : la liste des fichiers apparaît avec leur taille. Petit = rapide, gros = plus intelligent.
- Choisis un fichier de taille **Q4_K_M** pour le meilleur compromis.
- Pour un modèle réservé, ajoute ton jeton Hugging Face dans Réglages.
- Les modèles découpés en plusieurs morceaux ne sont pas proposés.

## Étape 4 : discuter
- Onglet **Chat**, choisis l'IA en haut, écris ton message, **Envoyer**. Le premier message démarre le moteur (30 secondes à 2 minutes).
- Trois modes : **Assistant**, **Code** (blocs avec bouton Copier) et **Prompt image**.
- **Joindre un fichier** colle le contenu d'un fichier texte ou de code dans ton message.
- **Arrêter** coupe une réponse trop longue.

## Étape 5 : créer un logiciel complet
- Onglet **Projet**. Choisis un modèle Coder, décris ton logiciel avec précision.
- Exemple : « une calculatrice en page web avec historique des calculs et mode sombre ».
- Appuie sur **Générer le logiciel**. L'IA fait un plan, puis écrit chaque fichier un par un. Compte de 5 à 30 minutes.
- Touche un fichier de la liste pour le lire et le copier.
- Appuie sur **Télécharger le .zip** : l'archive est dans Téléchargements, dossier IA Studio VM.
- **Relecture automatique** fait relire chaque fichier par l'IA : plus fiable, deux fois plus long.
- **Améliorer ce projet** : décris un changement, l'IA choisit les fichiers concernés et les réécrit. Retélécharge ensuite le .zip.
- Tes projets restent dans « Mes projets ».
- Pour lancer un logiciel web, décompresse le .zip et ouvre index.html. Pour Python, lis le README du projet.
- Demande des logiciels simples : une page web, un script, un petit jeu, une calculatrice. Une IA de téléphone se perd sur les gros projets.
- Teste toujours le résultat : le code peut contenir des erreurs. Dans ce cas, utilise « Améliorer ce projet » en décrivant l'erreur.

## Étape 6 : créer une image
- Onglet **Images**, choisis le modèle, décris l'image **en anglais** (plus fiable).
- Exemple : « a cozy cabin in snowy mountains, warm light, detailed, digital painting ».
- Commence en **256×256** pour tester vite, puis passe à 512.
- Le champ **À éviter** retire les défauts (flou, déformations).
- **Enregistrer** envoie l'image dans Photos, dossier IA Studio VM.

## Étape 7 : modifier une image
- Appuie sur **Choisir une photo à modifier** et prends une image de ta galerie.
- Décris le résultat voulu, par exemple « the same portrait as an oil painting ».
- Règle la **force de la modification** : bas = proche de l'original, haut = très différent. Essaie 50 à 70 %.
- Appuie sur **Transformer la photo**. Sur un résultat, **Modifier** le reprend comme nouvelle base.
- La photo est recadrée en carré à la taille choisie.

## Réglages
- **Créativité** : bas (0,2) = réponses précises, idéal pour le code. Haut (0,9) = plus inventif.
- **Mémoire de conversation** : 8192 convient à la plupart des cas. 16384 retient plus de texte mais demande plus de RAM.
- **Threads CPU** : plus de threads = plus rapide mais le téléphone chauffe. Prends le nombre de cœurs moins 1.
- Le chat et les images ne tournent pas en même temps : l'appli arrête l'un pour libérer la mémoire de l'autre.

## Problèmes courants
- **Moteur absent** : l'APK a été compilé sans les moteurs. Relance le workflow GitHub et lis son journal.
- **Le moteur s'arrête** : le message affiche un code et les dernières lignes du moteur.
- Code 137 : mémoire insuffisante. Choisis une IA plus petite (Qwen2.5 1.5B sur un téléphone de 4 Go), mets la mémoire de conversation à 2048 dans Machines › Réglages, ferme les autres applis.
- Autre code : envoie-moi le message complet pour que je corrige.
- **Téléchargement refusé** : le lien a changé ou le modèle est réservé. Cherche-le dans Hugging Face ou ajoute ton jeton.
- **Plan illisible dans Projet** : reformule la demande en une phrase claire, ou prends un modèle plus grand.
- **Réponses lentes** : normal sur processeur. Les modèles 0,5B et 1,5B sont les plus rapides.
- **Réponses fausses** : une IA locale est bien moins puissante que les grandes IA en ligne. Vérifie les informations importantes.
