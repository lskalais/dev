# Africa Legacy Market

Marketplace confidentielle de mise en relation entre cédants, acquéreurs/investisseurs et courtiers M&A.

## Stack

- Java 17+
- Maven WAR
- Jakarta Faces 4 / CDI (Weld)
- Jakarta Persistence avec Hibernate 6
- MariaDB Connector/J
- Déploiement sur Apache Tomcat 10.1

## Fonctionnalités livrées

- Création de compte et authentification pour cédant, acquéreur et courtier; les mots de passe sont hachés avec BCrypt.
- Inscription des acquéreurs comme personne physique ou personne morale, avec coordonnées, capacité indicative, secteurs recherchés ou renseignements d’immatriculation et de représentation.
- Page d'accueil publique avec accès aux offres validées, informations pour les cédants et connexion.
- Articles et actualités rédigés depuis l’administration, enregistrés en brouillon puis publiés sur le site.
- Formulaire public de contact et de réclamation; les coordonnées et le contenu du message sont chiffrés au repos et consultables dans l’administration SUPER. Les conversations privées des transactions restent réservées à leurs participants; aucun courriel de réponse automatique n'est envoyé.
- Administration des annonces : accès SUPER à l'ensemble des annonces, vérification des informations et pièces, publication ou suspension; un courtier mandaté peut gérer la publication de ses dossiers.
- Basculement entre modes clair et sombre, avec préférence conservée dans le navigateur.
- Teasers publics limités aux informations non identifiantes : secteur, domaines, zone, ancienneté par tranche et effectif approximatif par tranche. La fourchette de chiffre d’affaires n’est affichée que si le cédant l’autorise; description libre, montant exact, identité et adresse précise restent masqués.
- Publication immédiate des teasers anonymisés, même sans courtier correspondant; le cédant peut les modifier jusqu'à ce qu'un courtier accepte le dossier.
- Prise en charge des dossiers publiés par un courtier mandaté ou par un courtier qui revendique un dossier sans attribution; l'acceptation verrouille les modifications du cédant.
- Profil d'acquéreur, filtres par secteur/région/prix et classement selon la compatibilité des critères.
- Recherche acquéreur par secteur, domaine, zone, budgets, chiffre d'affaires, rentabilité, ancienneté, effectif et type de transaction; les critères financiers ne produisent des résultats qu'après signature bilatérale du NDA.
- Favoris privés des acquéreurs et notifications internes pour les opportunités compatibles, demandes de mise en relation et propositions/réponses de rendez-vous.
- Demande d'introduction, acceptation/refus par le cédant ou le courtier, signatures bilatérales nominatives de l'accord de confidentialité; un acquéreur doit avoir un profil KYC validé pour signer.
- Révélation des informations complémentaires, du nom et de la localisation précise uniquement après validation du profil acquéreur et signature bilatérale.
- Messagerie et propositions de rendez-vous réservées aux parties des dossiers signés; un document de data room n’est accessible à l’acquéreur qu’après autorisation du cédant et validation du courtier mandaté ou d’un administrateur.
- Le cédant fixe pour chaque document une durée d’accès de 1 à 365 jours; le délai commence après la dernière approbation. Les téléchargements sont horodatés et journalisés, et le cédant, le courtier mandaté et l’administration peuvent consulter l’historique.
- Dépôt par les cédants de pièces justificatives de vérification du compte (RCCM, document fiscal, statuts, identité du représentant, mandat ou autre pièce), chiffrées et accessibles uniquement aux rôles autorisés.
- Suivi de vérification du cédant : documents en attente, en vérification, pièces validées, complément demandé et compte validé; les courtiers et administrateurs disposent d'une file de revue pour consulter les pièces protégées, demander des corrections et valider les documents ou le compte.
- KYC des acquéreurs personnes physiques et morales : dossier créé à l'inscription, dépôt chiffré des justificatifs (identité pour les personnes physiques; immatriculation, statuts, identité du représentant légal et pouvoirs pour les personnes morales), revue réservée aux courtiers et administrateurs, vérification manuelle du courriel et du téléphone. Les justificatifs d'origine des fonds et les informations sur les bénéficiaires effectifs ne sont exigés que si le réviseur l'indique. La vérification des coordonnées est manuelle dans cette version; aucun code OTP par courriel/SMS n'est envoyé.
- Envoi de notifications par courriel facultatif via SMTP; sans SMTP configuré, les notifications restent disponibles dans l'application.
- Diagnostic financier confidentiel du dossier (résultat net, actifs, dettes, immobilisations et perspectives), consultable après NDA; le courtier mandaté peut calculer une valorisation indicative à partir de l'EBITDA et d'un multiple explicite. Toute modification ultérieure des données du dossier invalide cette validation.
- Suivi partagé du processus de cession par annonce, de l'inscription du cédant à la clôture post-cession; le cédant consulte l'étape et son historique, tandis que le courtier mandaté ou l'administration ALP fait avancer les étapes.
- Annuaire des experts-comptables, avocats, notaires, conseillers fiscaux, évaluateurs, banques et consultants, géré par l'administration et réservé aux comptes validés; les fiches peuvent être suspendues sans suppression.
- Chiffrement AES-256-GCM des messages, documents, noms de fichiers, identité et localisation précise; mots de passe BCrypt.
- Autorisations contrôlées dans la couche service et documents stockés hors du répertoire web.
- Administration des comptes réservée au rôle SUPER, avec création de comptes, attribution de mots de passe temporaires et changement obligatoire au prochain accès. La liste affiche aussi le type et le statut KYC des acquéreurs, sans exposer leurs pièces; la revue détaillée reste réservée aux rôles autorisés.
- Tableau de bord administratif réservé au rôle SUPER : comptes, KYC acquéreurs et vérifications cédants à finaliser, annonces actives ou suspendues, opérations en cours et nouveaux messages à traiter.

Les routes publiques sont `/confidentia/` (accueil), `/confidentia/acheter.xhtml`, `/confidentia/vendre.xhtml`, `/confidentia/login.xhtml`, `/confidentia/register.xhtml`, `/confidentia/faq.xhtml` et `/confidentia/legal.xhtml`. La page légale rassemble les conditions, notices de confidentialité et de données personnelles, mentions légales et informations sur la conservation des documents. Les coordonnées de l’éditeur, de l’hébergeur et les durées de conservation doivent être complétées et validées avant une ouverture au public. Un teaser enregistré est publié anonymement aux acquéreurs, même si le courriel de courtier est vide ou ne correspond à aucun courtier. Un courtier peut alors le prendre en charge; à partir de cette acceptation, seul le courtier mandaté peut gérer le dossier et le cédant ne peut plus modifier le teaser. Les annonces déjà publiées avant la migration historique peuvent rester en attente de validation jusqu'à leur acceptation par le courtier assigné.

La signature MVP est un consentement électronique nominatif horodaté dans l'application. Elle ne constitue pas une signature électronique qualifiée et n'intègre pas encore DocuSign/Universign. Le branchement à un prestataire et l'audit de conformité juridique restent nécessaires avant une mise en production.

## Préparer MariaDB

Créer une base et un compte dédiés, puis accorder à ce compte uniquement les droits sur la base de l'application. Par défaut, l'application utilise `localhost:3306`, la base `confidentia`, l'utilisateur `root` et un mot de passe vide pour faciliter le démarrage local.

Les propriétés applicatives sont définies dans `src/main/webapp/META-INF/context.xml`, intégré au WAR et chargé par Tomcat comme contexte de l'application. Une valeur non vide de ce fichier est prioritaire; une valeur vide utilise la variable d'environnement correspondante, puis la valeur par défaut de l'application.

| Variable | Valeur par défaut | Description |
| --- | --- | --- |
| `DB_HOST` | `localhost` | Hôte MariaDB |
| `DB_PORT` | `3306` | Port MariaDB |
| `DB_NAME` | `confidentia` | Nom de la base |
| `DB_USER` | `root` | Utilisateur SQL |
| `DB_PASSWORD` | vide | Mot de passe SQL |
| `DB_SCHEMA_MODE` | `update` | Mode Hibernate (`update` pour le développement local; préférer `validate` en production avec des migrations versionnées) |
| `APP_ENCRYPTION_KEY` | requis pour créer des données sensibles | Clé AES de 32 octets encodée en Base64 |
| `PRIVATE_STORAGE_DIR` | `./private-storage` | Répertoire de fichiers chiffrés, à placer hors du webroot |
| `SUPER_ADMIN_EMAIL` | non défini | Adresse du compte SUPER initial |
| `SUPER_ADMIN_PASSWORD` | non défini | Mot de passe initial, remplacé obligatoirement à la première connexion |
| `SUPER_ADMIN_NAME` | `Administrateur` | Nom du compte SUPER initial |
| `SMTP_HOST` | non défini | Hôte SMTP; requis pour activer l'envoi des courriels |
| `SMTP_PORT` | `587` | Port SMTP |
| `SMTP_FROM` | non défini | Adresse d'expédition; requise pour activer l'envoi |
| `SMTP_USERNAME` | non défini | Identifiant SMTP facultatif |
| `SMTP_PASSWORD` | non défini | Mot de passe SMTP, requis si `SMTP_USERNAME` est défini |
| `SMTP_STARTTLS` | `true` | Active STARTTLS pour SMTP |

Configurer `SUPER_ADMIN_EMAIL` et `SUPER_ADMIN_PASSWORD` ensemble dans l'environnement du processus Tomcat pour créer ou promouvoir le compte SUPER initial au démarrage. L'initialisation est appliquée une seule fois : les identifiants d'environnement ne réinitialisent pas le compte lors des redémarrages suivants. Le mot de passe initial et tout mot de passe temporaire attribué depuis l'administration doivent compter au moins 12 caractères et au plus 72 octets UTF-8. Les comptes temporaires sont forcés à choisir un nouveau mot de passe avant d'accéder au tableau de bord ou aux documents. Ne pas inscrire les mots de passe dans le dépôt ni dans les journaux.

Générer une clé aléatoire sous PowerShell, la conserver dans un gestionnaire de secrets et la réutiliser pour toutes les instances qui doivent lire ces données :

```powershell
$key = New-Object byte[] 32
$rng = New-Object Security.Cryptography.RNGCryptoServiceProvider
$rng.GetBytes($key)
$rng.Dispose()
$env:APP_ENCRYPTION_KEY = [Convert]::ToBase64String($key)
```

Les champs de secrets (`DB_USER`, `DB_PASSWORD`, `APP_ENCRYPTION_KEY`, `SUPER_ADMIN_EMAIL` et `SUPER_ADMIN_PASSWORD`) sont volontairement vides dans le fichier versionné. Les fournir par variables d'environnement, ou les renseigner seulement dans une copie locale non versionnée de `context.xml`; ne jamais committer des secrets ni distribuer un WAR qui les contient. Sous Windows, conserver `APP_ENCRYPTION_KEY` durablement dans l'environnement utilisateur du compte qui exécute Tomcat et redémarrer Tomcat après toute modification. Ne pas régénérer la clé après le chiffrement des données sans procédure de re-chiffrement : une nouvelle clé ne peut pas déchiffrer les anciennes identités, messages ou documents. Si la clé d'origine est perdue, ces données restent indisponibles; les sauvegardes de la base et du volume privé ne remplacent pas la clé. Configurer `PRIVATE_STORAGE_DIR` sur un volume privé persistant, et protéger les sauvegardes au même niveau.

## Construire et déployer

Depuis la racine du projet :

```powershell
mvn clean package
```

Le fichier `target/confidentia.war` est déployable dans Tomcat 10.1. Configurer les variables d'environnement dans le service Tomcat avant son démarrage et créer la base MariaDB. Dans l'installation locale `C:\devtool\apache-tomcat-10.1.61`, le connecteur HTTP est configuré sur le port `80`; ouvrir `http://localhost/confidentia/`. Les installateurs Windows et Linux utilisent leur configuration Tomcat par défaut sur le port `8080`.

### Installation automatisée de développement

Les installateurs suivants installent les dépendances, créent la base `confidentia` et un utilisateur SQL dédié, construisent le WAR et le déploient. Ils configurent `DB_SCHEMA_MODE=update` pour un environnement local; ne les utilisez pas comme configuration de production.

- **Windows 11** : ouvrir PowerShell en tant qu’administrateur à la racine du projet et lancer `.\install-windows.ps1`. `winget` installe Java 17 et MariaDB; le script télécharge Maven 3.9.16 et Tomcat 10.1.61 depuis Apache. L’installation MariaDB peut demander le mot de passe administrateur `root`. Le script génère le mot de passe de l’utilisateur SQL applicatif et la clé de chiffrement, puis démarre Tomcat au premier plan sur `http://localhost:8080/confidentia/`. Utiliser `-SkipStart` pour installer et déployer sans lancer Tomcat. MariaDB reste démarré comme service Windows; Tomcat peut ensuite être relancé avec le `catalina.bat` affiché par le script.
- **Debian/Ubuntu avec systemd** : lancer `sudo bash ./install-linux.sh`. Le script utilise `apt`, configure les services MariaDB et Tomcat 10, puis démarre Tomcat sur `http://localhost:8080/confidentia/`.

Les secrets générés restent hors du dépôt : sous Windows dans `%ProgramData%\Confidentia\confidentia.json` et sous Linux dans `/etc/confidentia/confidentia.env`. Ne supprimez pas ces fichiers ni le stockage privé associé : la clé AES doit rester identique pour relire les données chiffrées. Les fichiers de configuration sont protégés par des permissions système; protégez également les sauvegardes. Les installateurs ne créent pas de compte SUPER initial : configurez `SUPER_ADMIN_EMAIL` et `SUPER_ADMIN_PASSWORD` dans l’environnement du processus Tomcat si un administrateur initial est nécessaire.

### Démarrer Tomcat 10 et MariaDB sous Windows 11

Sous Windows, `start-local.bat` et `start-local.ps1` démarrent `mariadbd.exe` depuis `C:\devtool\mariadb-13.0.2-winx64`, en utilisant le répertoire de données `data13`, puis lancent Tomcat au premier plan. Le port du connecteur HTTP est défini dans `conf/server.xml` de Tomcat; le port MariaDB reste configurable séparément avec `DB_PORT`. Sous Linux, `start-local.sh` démarre le service MariaDB avec `systemd`.

1. Construire et déployer le WAR dans Tomcat (à refaire après chaque modification de l'application) :

   ```powershell
   mvn clean package
   Copy-Item .\target\confidentia.war 'C:\devtool\apache-tomcat-10.1.61\webapps\confidentia.war' -Force
   ```

2. Vérifier que les variables nécessaires, notamment `DB_USER`, `DB_PASSWORD` et `APP_ENCRYPTION_KEY`, sont définies. Si vous venez de les ajouter dans les paramètres Windows, ouvrez une nouvelle fenêtre PowerShell. Pour créer le compte administrateur initial, définissez également `SUPER_ADMIN_EMAIL` et `SUPER_ADMIN_PASSWORD`. Ne mettez pas de secrets dans le dépôt.

3. Depuis la racine du projet, lancer le script adapté à votre système d'exploitation. Sous Windows, ouvrez PowerShell ou l'invite de commandes. Une élévation des droits n'est nécessaire que si votre compte n'a pas accès au dossier de données MariaDB :

   ```powershell
   .\start-local.bat
   ```

   Les chemins MariaDB, son dossier de données, le chemin Tomcat et le port peuvent être adaptés au début de `start-local.bat`, ou définis dans les variables d'environnement `MARIADB_HOME`, `MARIADB_DATA`, `TOMCAT_HOME` et `DB_PORT`. Le script s'appuie sur PowerShell pour lancer `start-local.ps1`. Le dossier `data13` doit être initialisé et contenir le dossier système `mysql`; le script ne crée ni ne réinitialise les données. Si MariaDB écoute déjà sur le port configuré, aucun second serveur n'est lancé.

   Sous Linux avec `systemd`, rendez le script exécutable puis lancez-le depuis la racine du projet :

   ```sh
   chmod +x ./start-local.sh
   ./start-local.sh
   ```

   Le script Linux utilise le service `mariadb` par défaut et demande les droits nécessaires via `sudo`. Pour adapter les paramètres à votre installation :

   ```sh
   MARIADB_SERVICE=mariadb TOMCAT_HOME=/opt/tomcat DB_PORT=3306 ./start-local.sh
   ```

   Le script PowerShell peut aussi être lancé directement :

   ```powershell
   .\start-local.ps1 -MariaDbHome 'C:\devtool\mariadb-13.0.2-winx64' -MariaDbData 'C:\devtool\mariadb-13.0.2-winx64\data13'
   ```

Lorsque le Tomcat local démarre, ouvrir `http://localhost/confidentia/` (connecteur HTTP configuré sur le port `80`). Tomcat reste au premier plan; `Ctrl+C` l'arrête. Pour arrêter proprement Tomcat et MariaDB depuis la racine du projet, utiliser `.\stop-local.bat` sous Windows ou `./stop-local.sh` sous Linux :

```powershell
.\stop-local.bat
```

Le script Windows arrête Tomcat par son port d'arrêt Catalina, indépendant du port HTTP `80`, puis attend la fin de son processus avant d'arrêter MariaDB. Il utilise les mêmes variables d'environnement que `start-local.bat` (`MARIADB_HOME`, `MARIADB_DATA`, `TOMCAT_HOME` et `DB_PORT`) et n'arrête MariaDB que si le processus correspond au binaire, au répertoire de données et au port configurés. Une autre instance écoutant sur le port MariaDB n'est pas arrêtée. Si MariaDB requiert une authentification, définir `MARIADB_DEFAULTS_FILE` vers un fichier d'options client protégé contenant les droits nécessaires à `mariadb-admin`. Sous Linux, le script arrête le service `MARIADB_SERVICE` (par défaut `mariadb`) et utilise `sudo` si nécessaire; `TOMCAT_HOME` peut également être défini comme pour `start-local.sh`. Les scripts ne construisent ni ne copient le WAR : effectuer l'étape 1 après chaque changement de code. Ils héritent des variables d'environnement du processus qui les lance; ne mettez pas de secrets dans ces fichiers.

### Configuration locale de développement

Dans l'environnement local préparé pour ce projet, la base `confidentia` et l'utilisateur applicatif `confidentia_app` sont créés sur l'instance MariaDB 13.0.2 locale. Les paramètres `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SCHEMA_MODE` et `APP_ENCRYPTION_KEY` sont enregistrés dans les variables d'environnement de l'utilisateur Windows; aucun mot de passe ni clé n'est stocké dans le dépôt. L'utilisateur SQL est limité à cette base et à une connexion par loopback.

Redémarrer le processus Tomcat (ou le terminal/IDE qui le lance) après cette configuration pour qu'il hérite des variables. Conserver `APP_ENCRYPTION_KEY` de façon durable : perdre ou remplacer cette clé rend les messages, identités et documents chiffrés illisibles.

Pour un environnement de production, désactiver l'auto-évolution Hibernate (`DB_SCHEMA_MODE=validate`) et appliquer des migrations SQL versionnées, configurer TLS, limiter l'inscription de courtiers à un processus d'approbation, ajouter une analyse antivirus des fichiers et utiliser un gestionnaire de secrets. Le flux MVP n'inclut pas encore la récupération de mot de passe, MFA, notifications SMS ni intégration de signature tierce. Les rendez-vous saisis en `datetime-local` sont interprétés selon le fuseau horaire de la JVM Tomcat; configurer celui-ci conformément au fuseau d'exploitation.

Après l’ajout de l’expiration de la data room, les anciens documents déjà approuvés mais dépourvus de date d’expiration ne sont plus téléchargeables par les acquéreurs. Le cédant doit renouveler l’autorisation avec une durée et obtenir une nouvelle validation ALP.
