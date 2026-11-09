package fr.alp.confidentia.service;

import fr.alp.confidentia.config.AppConfiguration;
import fr.alp.confidentia.model.*;
import fr.alp.confidentia.persistence.Jpa;
import fr.alp.confidentia.security.CryptoService;
import fr.alp.confidentia.service.ViewModels.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Level;

@ApplicationScoped
public class MarketplaceService {
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final String SUPER_BOOTSTRAP_MIGRATION = "2026-10-super-user-bootstrap";
    private static final String UNAVAILABLE_ENCRYPTED_DATA =
            "Donnée chiffrée indisponible : l'ancienne clé de chiffrement n'est plus disponible.";
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(MarketplaceService.class.getName());
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "png", "jpg", "jpeg");
    private record AuthorizedDocument(Long id, Long requestId, String name, String contentType, String storageKey) {
    }

    private record ValidatedUpload(String safeName, String extension) {
    }

    private record NotificationDelivery(Long notificationId, String email) {
    }

    private record ListingRecipients(Long sellerId, Long brokerId) {
    }

    private record SavedListing(ListingSaveResult result, Listing listing) {
    }

    private static void validateLength(String value, int maximum, String label) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(label + " dépasse " + maximum + " caractères.");
        }
    }

    private static String requiredText(String value, int maximum, String label) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " est obligatoire.");
        }
        validateLength(normalized, maximum, label);
        return normalized;
    }

    private static String optionalText(String value, int maximum, String label) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        validateLength(normalized, maximum, label);
        return normalized;
    }

    private static void validateNonNegative(BigDecimal value, String label) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(label + " doit être positif ou nul.");
        }
    }

    private static void validateDecimal(BigDecimal value, int precision, int scale, String label) {
        if (value != null && (value.scale() > scale || value.precision() - value.scale() > precision - scale)) {
            throw new IllegalArgumentException(label + " accepte au maximum " + (precision - scale)
                    + " chiffres avant la virgule et " + scale + " après.");
        }
    }

    public UserView register(String email, String password, String name, String organization, Role role) {
        if (role == null || role == Role.SUPER) {
            throw new IllegalArgumentException("Le rôle SUPER ne peut être attribué que par un super utilisateur.");
        }
        return createUser(email, password, name, organization, role, false, null);
    }

    public UserView register(String email, String password, String name, String organization, Role role,
                             BuyerRegistration buyerRegistration) {
        if (role == null || role == Role.SUPER) {
            throw new IllegalArgumentException("Le rôle SUPER ne peut être attribué que par un super utilisateur.");
        }
        if (role == Role.BUYER) {
            validateBuyerRegistration(name, organization, buyerRegistration);
        } else if (buyerRegistration != null) {
            throw new IllegalArgumentException("Les informations acquéreur sont réservées au profil acquéreur.");
        }
        return createUser(email, password, name, organization, role, false, buyerRegistration);
    }

    public void bootstrapSuperUser() {
        LOGGER.info("Tentative de connexion super utilisateur");
        String email = AppConfiguration.get("SUPER_ADMIN_EMAIL", null);
        String password = AppConfiguration.get("SUPER_ADMIN_PASSWORD", null);
        if ((email == null || email.isBlank()) && (password == null || password.isBlank())) {
            java.util.logging.Logger.getLogger(MarketplaceService.class.getName()).warning(
                    "Aucun compte SUPER initial n'est configuré; définissez SUPER_ADMIN_EMAIL et SUPER_ADMIN_PASSWORD.");
            return;
        }
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "SUPER_ADMIN_EMAIL et SUPER_ADMIN_PASSWORD doivent être configurés ensemble.");
        }
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        String name = AppConfiguration.get("SUPER_ADMIN_NAME", "Administrateur").trim();
        if (name.isBlank() || name.length() > 160) {
            throw new IllegalStateException("SUPER_ADMIN_NAME doit contenir de 1 à 160 caractères.");
        }
        String passwordHash = PASSWORD_ENCODER.encode(password);
        Jpa.transaction(em -> {
            List<?> applied = em.createNativeQuery("SELECT version FROM app_migrations WHERE version = :version")
                    .setParameter("version", SUPER_BOOTSTRAP_MIGRATION).setMaxResults(1).getResultList();
            if (!applied.isEmpty()) {
                return null;
            }
            List<UserAccount> matchingUsers = em.createQuery(
                            "select u from UserAccount u where u.email = :email", UserAccount.class)
                    .setParameter("email", normalizedEmail).setMaxResults(1).getResultList();
            UserAccount administrator;
            if (matchingUsers.isEmpty()) {
                administrator = new UserAccount();
                administrator.email = normalizedEmail;
                administrator.passwordHash = passwordHash;
                administrator.role = Role.SUPER;
                administrator.fullName = name;
                administrator.createdAt = Instant.now();
                administrator.passwordChangeRequired = true;
                em.persist(administrator);
            } else {
                administrator = matchingUsers.get(0);
                administrator.role = Role.SUPER;
                administrator.passwordHash = passwordHash;
                administrator.passwordChangeRequired = true;
            }
            em.flush();
            em.createNativeQuery("INSERT INTO app_migrations (version) VALUES (:version)")
                    .setParameter("version", SUPER_BOOTSTRAP_MIGRATION).executeUpdate();
            return null;
        });
        java.util.logging.Logger.getLogger(MarketplaceService.class.getName()).info(
                "Compte SUPER initial configuré pour " + normalizedEmail + "; le changement du mot de passe sera exigé à la connexion.");
    }

    public List<AdminUserView> usersForAdministration(UserView administrator) {
        LOGGER.info("chargement des utilisateurs pour le super utilisateur");
        requireRole(administrator, Role.SUPER);
        return Jpa.transaction(em -> {
            List<UserAccount> accounts = em.createQuery(
                            "select u from UserAccount u order by u.createdAt desc, u.id desc", UserAccount.class)
                    .getResultList();
            List<Long> buyerIds = accounts.stream()
                    .filter(account -> account.role == Role.BUYER)
                    .map(account -> account.id)
                    .toList();
            Map<Long, BuyerProfile> profiles = new HashMap<>();
            Map<Long, BuyerKyc> kycRecords = new HashMap<>();
            if (!buyerIds.isEmpty()) {
                em.createQuery("select p from BuyerProfile p where p.userId in :ids", BuyerProfile.class)
                        .setParameter("ids", buyerIds).getResultList()
                        .forEach(profile -> profiles.put(profile.userId, profile));
                em.createQuery("select k from BuyerKyc k where k.userId in :ids", BuyerKyc.class)
                        .setParameter("ids", buyerIds).getResultList()
                        .forEach(kyc -> kycRecords.put(kyc.userId, kyc));
            }
            return accounts.stream()
                    .map(account -> adminUserView(account, profiles.get(account.id), kycRecords.get(account.id)))
                    .toList();
        });
    }

    public AdminDashboardView adminDashboardFor(UserView administrator) {
        requireRole(administrator, Role.SUPER);
        return Jpa.transaction(em -> new AdminDashboardView(
                ((Number) em.createQuery("select count(u) from UserAccount u").getSingleResult()).longValue(),
                ((Number) em.createQuery("select count(k) from BuyerKyc k where k.status <> :status")
                        .setParameter("status", BuyerKycStatus.VALIDATED).getSingleResult()).longValue(),
                ((Number) em.createQuery("select count(v) from SellerVerification v where v.status <> :status")
                        .setParameter("status", SellerVerificationStatus.ACCOUNT_VALIDATED).getSingleResult()).longValue(),
                ((Number) em.createQuery(
                                "select count(l) from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED')")
                        .getSingleResult()).longValue(),
                ((Number) em.createQuery("select count(l) from Listing l where l.status = 'SUSPENDED'")
                        .getSingleResult()).longValue(),
                ((Number) em.createQuery(
                                "select count(d) from DealRequest d where d.status not in "
                                        + "('DECLINED', 'COMPLETED', 'CANCELLED')")
                        .getSingleResult()).longValue(),
                ((Number) em.createQuery("select count(m) from ContactMessage m where m.status = 'NEW'")
                        .getSingleResult()).longValue()));
    }

    public List<ArticleView> articlesForAdministration(UserView administrator) {
        requireRole(administrator, Role.SUPER);
        return Jpa.transaction(em -> em.createQuery(
                        "select a from Article a order by a.updatedAt desc, a.id desc", Article.class)
                .getResultList().stream().map(MarketplaceService::articleView).toList());
    }

    public ArticleView saveArticle(UserView administrator, Long articleId, String title, String category,
                                   String summary, String content) {
        requireRole(administrator, Role.SUPER);
        String normalizedTitle = requiredText(title, 180, "Le titre");
        String normalizedCategory = requiredText(category, 80, "La catégorie");
        String normalizedSummary = requiredText(summary, 500, "Le résumé");
        String normalizedContent = requiredText(content, 20000, "Le contenu");
        Instant now = Instant.now();
        return Jpa.transaction(em -> {
            Article article;
            if (articleId == null) {
                article = new Article();
                article.createdBy = administrator.id();
                article.createdAt = now;
                article.status = "DRAFT";
                em.persist(article);
            } else {
                article = em.find(Article.class, articleId);
                if (article == null) {
                    throw new IllegalArgumentException("Article introuvable.");
                }
            }
            article.title = normalizedTitle;
            article.category = normalizedCategory;
            article.summary = normalizedSummary;
            article.content = normalizedContent;
            article.updatedAt = now;
            return articleView(article);
        });
    }

    public boolean hasPartnerDirectoryAccess(UserView user) {
        if (user == null) {
            return false;
        }
        if (Role.SUPER.name().equals(user.role()) || Role.BROKER.name().equals(user.role())) {
            return true;
        }
        if (!Role.BUYER.name().equals(user.role()) && !Role.SELLER.name().equals(user.role())) {
            return false;
        }
        return Jpa.transaction(em -> hasValidatedProfile(em, user));
    }

    public List<PartnerExpertView> partnerExpertsFor(UserView user) {
        return Jpa.transaction(em -> {
            if (!hasPartnerDirectoryAccess(em, user)) {
                throw new SecurityException("L'annuaire est réservé aux utilisateurs connectés dont le profil est validé.");
            }
            String jpql = Role.SUPER.name().equals(user.role())
                    ? "select p from PartnerExpert p order by p.category, p.name"
                    : "select p from PartnerExpert p where p.active = true order by p.category, p.name";
            return em.createQuery(jpql, PartnerExpert.class).getResultList().stream()
                    .map(MarketplaceService::partnerExpertView).toList();
        });
    }

    public PartnerExpertView savePartnerExpert(UserView administrator, Long partnerId, String name,
                                                PartnerExpertCategory category, String country, String location,
                                                String summary, String contactEmail, String phone, String website,
                                                boolean active) {
        requireRole(administrator, Role.SUPER);
        String normalizedName = requiredText(name, 180, "Le nom du partenaire");
        if (category == null) {
            throw new IllegalArgumentException("La catégorie du partenaire est obligatoire.");
        }
        String normalizedCountry = requiredText(country, 100, "Le pays");
        String normalizedLocation = optionalText(location, 160, "La ville ou région");
        String normalizedSummary = optionalText(summary, 2000, "La présentation");
        String normalizedEmail = contactEmail == null || contactEmail.isBlank()
                ? null : normalizeEmail(contactEmail);
        String normalizedPhone = optionalText(phone, 40, "Le téléphone");
        String normalizedWebsite = optionalText(website, 300, "Le site web");
        if (normalizedWebsite != null) {
            try {
                URI uri = URI.create(normalizedWebsite);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("Le site web doit être une adresse HTTP ou HTTPS valide.");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Le site web doit être une adresse HTTP ou HTTPS valide.", exception);
            }
        }
        Instant now = Instant.now();
        return Jpa.transaction(em -> {
            PartnerExpert partner;
            if (partnerId == null) {
                partner = new PartnerExpert();
                partner.createdAt = now;
                em.persist(partner);
            } else {
                partner = em.find(PartnerExpert.class, partnerId);
                if (partner == null) {
                    throw new IllegalArgumentException("Partenaire introuvable.");
                }
            }
            partner.name = normalizedName;
            partner.category = category;
            partner.country = normalizedCountry;
            partner.location = normalizedLocation;
            partner.summary = normalizedSummary;
            partner.contactEmail = normalizedEmail;
            partner.phone = normalizedPhone;
            partner.website = normalizedWebsite;
            partner.active = active;
            partner.updatedAt = now;
            return partnerExpertView(partner);
        });
    }

    public void setPartnerExpertActive(UserView administrator, Long partnerId, boolean active) {
        requireRole(administrator, Role.SUPER);
        Jpa.transaction(em -> {
            PartnerExpert partner = em.find(PartnerExpert.class, partnerId);
            if (partner == null) {
                throw new IllegalArgumentException("Partenaire introuvable.");
            }
            partner.active = active;
            partner.updatedAt = Instant.now();
            return null;
        });
    }

    public void setArticlePublished(UserView administrator, Long articleId, boolean published) {
        requireRole(administrator, Role.SUPER);
        Jpa.transaction(em -> {
            Article article = articleId == null ? null : em.find(Article.class, articleId);
            if (article == null) {
                throw new IllegalArgumentException("Article introuvable.");
            }
            if (published) {
                article.title = requiredText(article.title, 180, "Le titre");
                article.category = requiredText(article.category, 80, "La catégorie");
                article.summary = requiredText(article.summary, 500, "Le résumé");
                article.content = requiredText(article.content, 20000, "Le contenu");
                article.status = "PUBLISHED";
                article.publishedAt = Instant.now();
            } else {
                article.status = "DRAFT";
                article.publishedAt = null;
            }
            article.updatedAt = Instant.now();
            return null;
        });
    }

    public List<ContactMessageView> contactMessagesFor(UserView administrator) {
        requireRole(administrator, Role.SUPER);
        return Jpa.transaction(em -> em.createQuery(
                        "select m from ContactMessage m order by m.createdAt desc, m.id desc",
                        ContactMessage.class)
                .getResultList().stream().map(MarketplaceService::contactMessageView).toList());
    }

    public void updateContactMessage(UserView administrator, Long messageId, String status, String adminNote) {
        requireRole(administrator, Role.SUPER);
        if (status == null || !Set.of("NEW", "IN_PROGRESS", "RESOLVED").contains(status)) {
            throw new IllegalArgumentException("Sélectionnez un statut valide pour ce message.");
        }
        String normalizedNote = optionalText(adminNote, 3000, "La note interne");
        Jpa.transaction(em -> {
            ContactMessage contactMessage = messageId == null ? null : em.find(ContactMessage.class, messageId);
            if (contactMessage == null) {
                throw new IllegalArgumentException("Message introuvable.");
            }
            contactMessage.status = status;
            contactMessage.adminNote = normalizedNote;
            contactMessage.handledBy = administrator.id();
            contactMessage.updatedAt = Instant.now();
            return null;
        });
    }

    public void submitContactMessage(String senderName, String senderEmail, String senderPhone, String type,
                                     String subject, String messageText) {
        String normalizedName = requiredText(senderName, 160, "Le nom");
        String normalizedEmail = normalizeEmail(senderEmail);
        String normalizedPhone = optionalText(senderPhone, 40, "Le téléphone");
        if (type == null || !Set.of("CONTACT", "COMPLAINT").contains(type)) {
            throw new IllegalArgumentException("Sélectionnez un motif de contact valide.");
        }
        String normalizedSubject = requiredText(subject, 180, "L’objet");
        String normalizedMessage = requiredText(messageText, 10000, "Le message");
        String encryptedName = CryptoService.encrypt(normalizedName);
        String encryptedEmail = CryptoService.encrypt(normalizedEmail);
        String encryptedPhone = normalizedPhone == null ? null : CryptoService.encrypt(normalizedPhone);
        String encryptedSubject = CryptoService.encrypt(normalizedSubject);
        String encryptedMessage = CryptoService.encrypt(normalizedMessage);
        Instant now = Instant.now();
        Jpa.transaction(em -> {
            ContactMessage contactMessage = new ContactMessage();
            contactMessage.senderNameEncrypted = encryptedName;
            contactMessage.senderEmailEncrypted = encryptedEmail;
            contactMessage.senderPhoneEncrypted = encryptedPhone;
            contactMessage.type = type;
            contactMessage.subjectEncrypted = encryptedSubject;
            contactMessage.messageTextEncrypted = encryptedMessage;
            contactMessage.status = "NEW";
            contactMessage.createdAt = now;
            contactMessage.updatedAt = now;
            em.persist(contactMessage);
            return null;
        });
    }

    public void createManagedUser(UserView administrator, String email, String password, String name,
                                  String organization, Role role) {
        requireRole(administrator, Role.SUPER);
        if (role == null) {
            LOGGER.info("Aucun rôle sélectionné pour le nouvel utilisateur");
            throw new IllegalArgumentException("Sélectionnez un rôle pour le nouvel utilisateur.");
        }
        if (role == Role.BROKER && (organization == null || organization.isBlank())) {
            LOGGER.info("Organisation manquante pour le profil courtier");
            throw new IllegalArgumentException("Un cabinet ou une organisation est requis pour le profil courtier.");
        }
        createUser(email, password, name, organization, role, true, null);
    }

    public void assignTemporaryPassword(UserView administrator, Long userId, String password) {
        LOGGER.info("assignation d'un mot de passe temporaire pour l'utilisateur avec l'ID : " + userId);
        requireRole(administrator, Role.SUPER);
        validatePassword(password);
        Jpa.transaction(em -> {
            UserAccount target = userId == null ? null : em.find(UserAccount.class, userId);
            if (target == null) {
                throw new IllegalArgumentException("Utilisateur introuvable.");
            }
            target.passwordHash = PASSWORD_ENCODER.encode(password);
            target.passwordChangeRequired = true;
            return null;
        });
    }

    public UserView changeTemporaryPassword(UserView user, String password) {
        LOGGER.info("Changement du mot de passe temporaire pour l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        if (user == null || !user.passwordChangeRequired()) {
            throw new SecurityException("Aucun changement de mot de passe temporaire n'est requis.");
        }
        validatePassword(password);
        return Jpa.transaction(em -> {
            LOGGER.info("Récupération du compte utilisateur pour l'ID : " + user.id());
            UserAccount account = em.find(UserAccount.class, user.id());
            if (account == null || !account.passwordChangeRequired) {
                throw new SecurityException("Aucun changement de mot de passe temporaire n'est requis.");
            }
            if (PASSWORD_ENCODER.matches(password, account.passwordHash)) {
                throw new IllegalArgumentException("Le nouveau mot de passe doit être différent du mot de passe temporaire.");
            }
            account.passwordHash = PASSWORD_ENCODER.encode(password);
            account.passwordChangeRequired = false;
            return view(account);
        });
    }

    public boolean isSuperUser(UserView user) {
        LOGGER.info("Vérification si l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()) + " est un super utilisateur");  
        return user != null && Role.SUPER.name().equals(user.role());
    }

    private UserView createUser(String email, String password, String name, String organization, Role role,
                                boolean passwordChangeRequired, BuyerRegistration buyerRegistration) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (name == null || name.isBlank() || name.length() > 160) {
            throw new IllegalArgumentException("Le nom est obligatoire (160 caractères maximum).");
        }
        return Jpa.transaction(em -> {
            Long count = em.createQuery("select count(u) from UserAccount u where u.email = :email", Long.class)
                    .setParameter("email", normalizedEmail).getSingleResult();
            if (count != 0) {
                throw new IllegalArgumentException("Cette adresse courriel est déjà utilisée.");
            }
            UserAccount user = new UserAccount();
            user.email = normalizedEmail;
            user.passwordHash = PASSWORD_ENCODER.encode(password);
            user.role = Objects.requireNonNull(role);
            user.fullName = name.trim();
            user.organization = trimToNull(organization);
            user.createdAt = Instant.now();
            user.passwordChangeRequired = passwordChangeRequired;
            em.persist(user);
            em.flush();
            if (role == Role.BUYER) {
                BuyerProfile profile = new BuyerProfile();
                profile.userId = user.id;
                if (buyerRegistration != null) {
                    profile.buyerType = buyerRegistration.type();
                    profile.phone = trimToNull(buyerRegistration.phone());
                    profile.nationality = trimToNull(buyerRegistration.nationality());
                    profile.residence = trimToNull(buyerRegistration.residence());
                    profile.profession = trimToNull(buyerRegistration.profession());
                    profile.indicativeFinancialCapacity = buyerRegistration.indicativeFinancialCapacity();
                    profile.financialCapacityCurrencyCode = buyerRegistration.financialCapacityCurrencyCode();
                    profile.sectors = trimToNull(buyerRegistration.sectors());
                    profile.legalForm = trimToNull(buyerRegistration.legalForm());
                    profile.country = trimToNull(buyerRegistration.country());
                    profile.countryCode = buyerRegistration.countryCode();
                    profile.registrationNumber = trimToNull(buyerRegistration.registrationNumber());
                    profile.taxIdentifier = trimToNull(buyerRegistration.taxIdentifier());
                    profile.headOffice = trimToNull(buyerRegistration.headOffice());
                    profile.representativeFunction = trimToNull(buyerRegistration.representativeFunction());
                }
                em.persist(profile);
                if (profile.buyerType != null) {
                    BuyerKyc kyc = new BuyerKyc();
                    kyc.userId = user.id;
                    kyc.status = BuyerKycStatus.DOCUMENTS_PENDING;
                    kyc.updatedAt = Instant.now();
                    em.persist(kyc);
                }
            } else if (role == Role.SELLER) {
                SellerVerification verification = new SellerVerification();
                verification.userId = user.id;
                verification.status = SellerVerificationStatus.DOCUMENTS_PENDING;
                verification.updatedAt = Instant.now();
                em.persist(verification);
            }
            em.flush();
            return view(user);
        });
    }

    private static void validateBuyerRegistration(String name, String organization,
                                                   BuyerRegistration registration) {
        if (registration == null || registration.type() == null) {
            throw new IllegalArgumentException("Choisissez le type de compte acquéreur.");
        }
        validateLength(registration.sectors(), 500, "Secteurs recherchés");
        validateNonNegative(registration.indicativeFinancialCapacity(), "Capacité financière indicative");
        validateDecimal(registration.indicativeFinancialCapacity(), 16, 2, "Capacité financière indicative");
        if (!MarketCatalog.isCurrencyCode(registration.financialCapacityCurrencyCode())) {
            throw new IllegalArgumentException("Choisissez une devise ISO 4217 valide pour la capacité financière.");
        }
        if (registration.type() == BuyerType.INDIVIDUAL) {
            requireBuyerField(registration.phone(), 40, "Le téléphone");
            requireBuyerField(registration.nationality(), 100, "La nationalité");
            requireBuyerField(registration.residence(), 120, "Le pays de résidence");
            requireBuyerField(registration.profession(), 160, "La profession");
            return;
        }
        requireBuyerField(organization, 160, "La dénomination sociale");
        requireBuyerField(registration.phone(), 40, "Le téléphone");
        requireBuyerField(registration.legalForm(), 100, "La forme juridique");
        requireBuyerField(registration.country(), 100, "Le pays d’immatriculation");
        if (registration.countryCode() != null && !MarketCatalog.isCountryCode(registration.countryCode())) {
            throw new IllegalArgumentException("Choisissez un pays ISO 3166-1 valide.");
        }
        requireBuyerField(registration.registrationNumber(), 120, "Le numéro RCCM ou équivalent");
        requireBuyerField(registration.taxIdentifier(), 120, "Le numéro fiscal");
        requireBuyerField(registration.headOffice(), 240, "Le siège social");
        requireBuyerField(name, 160, "Le représentant légal");
        requireBuyerField(registration.representativeFunction(), 120, "La fonction du représentant légal");
    }

    private static void requireBuyerField(String value, int maximum, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " est obligatoire.");
        }
        validateLength(value, maximum, label);
    }

    public UserView authenticate(String email, String password) {
        LOGGER.info("Tentative d'authentification pour l'adresse courriel : " + email);
        String normalizedEmail = normalizeEmail(email);
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Adresse courriel ou mot de passe invalide.");
        }
        return Jpa.transaction(em -> {
            List<UserAccount> users = em.createQuery(
                            "select u from UserAccount u where u.email = :email", UserAccount.class)
                    .setParameter("email", normalizedEmail).setMaxResults(1).getResultList();
            if (users.isEmpty() || !PASSWORD_ENCODER.matches(password, users.get(0).passwordHash)) {
                throw new IllegalArgumentException("Adresse courriel ou mot de passe invalide.");
            }
            return view(users.get(0));
        });
    }

    public void saveBuyerCriteria(UserView user, String sectors, String region,
                                  BigDecimal minimum, BigDecimal maximum) {
        saveBuyerCriteria(user, sectors, region, minimum, maximum, "EUR");
    }

    public void saveBuyerCriteria(UserView user, String sectors, String region,
                                  BigDecimal minimum, BigDecimal maximum, String currencyCode) {
        LOGGER.info("Sauvegarde des critères d'achat pour l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        requireRole(user, Role.BUYER);
        validateLength(sectors, 500, "Secteurs recherchés");
        validateLength(region, 120, "Région souhaitée");
        if (!MarketCatalog.isCurrencyCode(currencyCode)) {
            throw new IllegalArgumentException("Choisissez une devise ISO 4217 valide pour les critères d’investissement.");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Le budget minimum ne peut dépasser le budget maximum.");
        }
        validateNonNegative(minimum, "Investissement minimum");
        validateNonNegative(maximum, "Investissement maximum");
        Jpa.transaction(em -> {
            BuyerProfile profile = em.createQuery(
                            "select p from BuyerProfile p where p.userId = :userId", BuyerProfile.class)
                    .setParameter("userId", user.id()).getSingleResult();
            profile.sectors = trimToNull(sectors);
            profile.preferredRegion = trimToNull(region);
            profile.minInvestment = minimum;
            profile.maxInvestment = maximum;
            profile.investmentCurrencyCode = currencyCode;
            return null;
        });
    }

    public ListingSaveResult saveListing(UserView seller, Long listingId, String brokerEmail,
                                         String sectorCategory, List<String> activityDomains,
                                         String sector, String secondarySector, Integer foundedYear,
                                         String description, String region, String customerSummary,
                                         String advantages, String saleReason, BigDecimal revenue,
                                         BigDecimal ebitda, BigDecimal askingPrice, BigDecimal profitMargin,
                                         String saleConditions, String supportPeriod, String companyName,
                                         String exactLocation) {
        return saveListing(seller, listingId, brokerEmail, sectorCategory, activityDomains, sector,
                secondarySector, foundedYear, description, region, customerSummary, advantages, saleReason,
                revenue, ebitda, askingPrice, profitMargin, saleConditions, supportPeriod,
                companyName, exactLocation, null, false);
    }

    public ListingSaveResult saveListing(UserView seller, Long listingId, String brokerEmail,
                                         String sectorCategory, List<String> activityDomains,
                                         String sector, String secondarySector, Integer foundedYear,
                                         String description, String region, String customerSummary,
                                         String advantages, String saleReason, BigDecimal revenue,
                                         BigDecimal ebitda, BigDecimal askingPrice, BigDecimal profitMargin,
                                         String saleConditions, String supportPeriod, String companyName,
                                         String exactLocation, Integer approximateEmployeeCount,
                                         boolean publicRevenueRangeAllowed) {
        return saveListing(seller, listingId, brokerEmail, sectorCategory, activityDomains, sector,
                secondarySector, foundedYear, description, region, customerSummary, advantages, saleReason,
                revenue, ebitda, askingPrice, profitMargin, saleConditions, supportPeriod, companyName,
                exactLocation, approximateEmployeeCount, publicRevenueRangeAllowed,
                ListingTransactionType.FULL_SALE);
    }

    public ListingSaveResult saveListing(UserView seller, Long listingId, String brokerEmail,
                                         String sectorCategory, List<String> activityDomains,
                                         String sector, String secondarySector, Integer foundedYear,
                                         String description, String region, String customerSummary,
                                         String advantages, String saleReason, BigDecimal revenue,
                                         BigDecimal ebitda, BigDecimal askingPrice, BigDecimal profitMargin,
                                         String saleConditions, String supportPeriod, String companyName,
                                         String exactLocation, Integer approximateEmployeeCount,
                                         boolean publicRevenueRangeAllowed,
                                         ListingTransactionType transactionType) {
        return saveListingInternal(seller, listingId, brokerEmail, sectorCategory, activityDomains, sector,
                secondarySector, foundedYear, description, region, customerSummary, advantages, saleReason,
                revenue, ebitda, askingPrice, profitMargin, saleConditions, supportPeriod, companyName,
                exactLocation, approximateEmployeeCount, publicRevenueRangeAllowed, transactionType,
                null, null, null, null, null, false, null, "EUR");
    }

    public ListingSaveResult saveListing(UserView seller, Long listingId, String brokerEmail,
                                         String sectorCategory, List<String> activityDomains,
                                         String sector, String secondarySector, Integer foundedYear,
                                         String description, String region, String customerSummary,
                                         String advantages, String saleReason, BigDecimal revenue,
                                         BigDecimal ebitda, BigDecimal askingPrice, BigDecimal profitMargin,
                                         String saleConditions, String supportPeriod, String companyName,
                                         String exactLocation, Integer approximateEmployeeCount,
                                         boolean publicRevenueRangeAllowed,
                                         ListingTransactionType transactionType,
                                         BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalDebts,
                                         BigDecimal fixedAssets, String financialOutlook) {
        return saveListingInternal(seller, listingId, brokerEmail, sectorCategory, activityDomains, sector,
                secondarySector, foundedYear, description, region, customerSummary, advantages, saleReason,
                revenue, ebitda, askingPrice, profitMargin, saleConditions, supportPeriod, companyName,
                exactLocation, approximateEmployeeCount, publicRevenueRangeAllowed, transactionType,
                netIncome, totalAssets, totalDebts, fixedAssets, financialOutlook, true, null, "EUR");
    }

    public ListingSaveResult saveListing(UserView seller, Long listingId, String brokerEmail,
                                         String sectorCategory, List<String> activityDomains,
                                         String sector, String secondarySector, Integer foundedYear,
                                         String description, String region, String customerSummary,
                                         String advantages, String saleReason, BigDecimal revenue,
                                         BigDecimal ebitda, BigDecimal askingPrice, BigDecimal profitMargin,
                                         String saleConditions, String supportPeriod, String companyName,
                                         String exactLocation, Integer approximateEmployeeCount,
                                         boolean publicRevenueRangeAllowed,
                                         ListingTransactionType transactionType,
                                         BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalDebts,
                                         BigDecimal fixedAssets, String financialOutlook,
                                         String countryCode, String currencyCode) {
        if (!MarketCatalog.isCountryCode(countryCode)) {
            throw new IllegalArgumentException("Choisissez un pays ISO 3166-1 valide pour l'annonce.");
        }
        return saveListingInternal(seller, listingId, brokerEmail, sectorCategory, activityDomains, sector,
                secondarySector, foundedYear, description, region, customerSummary, advantages, saleReason,
                revenue, ebitda, askingPrice, profitMargin, saleConditions, supportPeriod, companyName,
                exactLocation, approximateEmployeeCount, publicRevenueRangeAllowed, transactionType,
                netIncome, totalAssets, totalDebts, fixedAssets, financialOutlook, true, countryCode, currencyCode);
    }

    private ListingSaveResult saveListingInternal(UserView seller, Long listingId, String brokerEmail,
                                                  String sectorCategory, List<String> activityDomains,
                                                  String sector, String secondarySector, Integer foundedYear,
                                                  String description, String region, String customerSummary,
                                                  String advantages, String saleReason, BigDecimal revenue,
                                                  BigDecimal ebitda, BigDecimal askingPrice, BigDecimal profitMargin,
                                                  String saleConditions, String supportPeriod, String companyName,
                                                  String exactLocation, Integer approximateEmployeeCount,
                                                  boolean publicRevenueRangeAllowed,
                                                  ListingTransactionType transactionType,
                                                  BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalDebts,
                                                  BigDecimal fixedAssets, String financialOutlook,
                                                  boolean updateFinancialDiagnosis, String countryCode,
                                                  String currencyCode) {
        LOGGER.info("Sauvegarde d'une annonce pour l'utilisateur avec l'ID : " + (seller == null ? "null" : seller.id()));
        requireRole(seller, Role.SELLER);
        if (sector == null || sector.isBlank() || description == null || description.isBlank()
                || region == null || region.isBlank()) {
            throw new IllegalArgumentException("Le secteur, la description et la région sont obligatoires.");
        }
        if (!ActivityTaxonomy.isValid(sectorCategory, activityDomains)) {
            throw new IllegalArgumentException(
                    "Sélectionnez un grand secteur et au moins un domaine d'activité correspondant.");
        }
        if (description.length() > 4000 || sector.length() > 120 || region.length() > 120) {
            throw new IllegalArgumentException("Un des champs dépasse la longueur autorisée.");
        }
        validateLength(secondarySector, 120, "Secteur secondaire");
        validateLength(customerSummary, 2000, "Clientèle");
        validateLength(advantages, 2000, "Avantages concurrentiels");
        validateLength(saleReason, 1000, "Raison de la vente");
        validateLength(saleConditions, 1000, "Conditions de cession");
        validateLength(supportPeriod, 500, "Accompagnement");
        validateLength(financialOutlook, 2000, "Perspectives financières");
        validateLength(companyName, 500, "Nom réel");
        validateLength(exactLocation, 500, "Localisation exacte");
        if (!MarketCatalog.isCurrencyCode(currencyCode)) {
            throw new IllegalArgumentException("Choisissez une devise ISO 4217 valide pour les données financières.");
        }
        if (countryCode != null && !MarketCatalog.isCountryCode(countryCode)) {
            throw new IllegalArgumentException("Choisissez un pays ISO 3166-1 valide.");
        }
        if (foundedYear != null && (foundedYear < 1800 || foundedYear > java.time.Year.now().getValue() + 5)) {
            throw new IllegalArgumentException("Année de création invalide.");
        }
        if (approximateEmployeeCount != null && (approximateEmployeeCount < 1 || approximateEmployeeCount > 1_000_000)) {
            throw new IllegalArgumentException("L’effectif approximatif doit être compris entre 1 et 1 000 000.");
        }
        validateNonNegative(revenue, "Chiffre d'affaires");
        validateNonNegative(askingPrice, "Prix demandé");
        validateDecimal(revenue, 16, 2, "Chiffre d'affaires");
        validateDecimal(ebitda, 16, 2, "EBE / EBITDA");
        validateDecimal(askingPrice, 16, 2, "Prix demandé");
        validateDecimal(profitMargin, 7, 2, "Marge bénéficiaire");
        validateNonNegative(totalAssets, "Actifs");
        validateNonNegative(totalDebts, "Dettes");
        validateNonNegative(fixedAssets, "Immobilisations");
        validateDecimal(netIncome, 16, 2, "Résultat net");
        validateDecimal(totalAssets, 16, 2, "Actifs");
        validateDecimal(totalDebts, 16, 2, "Dettes");
        validateDecimal(fixedAssets, 16, 2, "Immobilisations");
        String teaserText = String.join(" ",
                sector, Objects.toString(secondarySector, ""), description, region,
                Objects.toString(customerSummary, ""), Objects.toString(advantages, ""),
                Objects.toString(saleReason, ""), Objects.toString(saleConditions, ""));
        if (containsSensitiveValue(teaserText, companyName) || containsSensitiveValue(teaserText, exactLocation)) {
            throw new IllegalArgumentException(
                    "Le teaser semble contenir le nom réel ou l'adresse exacte. Retirez ces éléments avant publication.");
        }

        SavedListing saved = Jpa.transaction(em -> {
            Listing listing = listingId == null ? new Listing() : requireListing(em, listingId);
            if (listingId != null) {
                requireListingSeller(seller, listing);
                if ("BROKER_ACCEPTED".equals(listing.status)) {
                    throw new IllegalArgumentException("Ce dossier a été accepté par un courtier et ne peut plus être modifié.");
                }
            } else {
                listing.sellerId = seller.id();
                listing.views = 0;
                listing.createdAt = Instant.now();
            }
            Long brokerId = findBrokerId(em, brokerEmail);
            listing.brokerId = brokerId;
            listing.sectorCategory = sectorCategory;
            listing.activityDomains = String.join(",", activityDomains);
            listing.sector = sector.trim();
            listing.secondarySector = trimToNull(secondarySector);
            listing.foundedYear = foundedYear;
            listing.approximateEmployeeCount = approximateEmployeeCount;
            listing.publicRevenueRangeAllowed = publicRevenueRangeAllowed;
            listing.description = description.trim();
            listing.region = region.trim();
            listing.customerSummary = trimToNull(customerSummary);
            listing.advantages = trimToNull(advantages);
            listing.saleReason = trimToNull(saleReason);
            listing.revenue = revenue;
            listing.ebitda = ebitda;
            listing.askingPrice = askingPrice;
            listing.profitMargin = profitMargin;
            listing.saleConditions = trimToNull(saleConditions);
            listing.supportPeriod = trimToNull(supportPeriod);
            listing.transactionType = transactionType == null
                    ? ListingTransactionType.FULL_SALE : transactionType;
            if (companyName != null && !companyName.isBlank()
                    && !UNAVAILABLE_ENCRYPTED_DATA.equals(companyName)) {
                listing.companyNameEncrypted = CryptoService.encrypt(companyName.trim());
            }
            if (exactLocation != null && !exactLocation.isBlank()
                    && !UNAVAILABLE_ENCRYPTED_DATA.equals(exactLocation)) {
                listing.exactLocationEncrypted = CryptoService.encrypt(exactLocation.trim());
            }
            listing.status = "PUBLISHED";
            if (listingId == null) {
                em.persist(listing);
            }
            em.flush();
            ensureListingSaleProcess(em, listing, seller.id());
            ListingFinancialDiagnosis diagnosis = findFinancialDiagnosis(em, listing.id);
            boolean newDiagnosis = diagnosis == null;
            if (diagnosis == null && (updateFinancialDiagnosis || countryCode != null)) {
                diagnosis = new ListingFinancialDiagnosis();
                diagnosis.listingId = listing.id;
                em.persist(diagnosis);
            }
            if (diagnosis != null && (newDiagnosis || countryCode != null)) {
                diagnosis.countryCode = countryCode;
                diagnosis.currencyCode = currencyCode;
            }
            if (diagnosis != null && updateFinancialDiagnosis) {
                diagnosis.netIncome = netIncome;
                diagnosis.totalAssets = totalAssets;
                diagnosis.totalDebts = totalDebts;
                diagnosis.fixedAssets = fixedAssets;
                diagnosis.financialOutlook = trimToNull(financialOutlook);
            }
            if (diagnosis != null) {
                diagnosis.valuationMultiple = null;
                diagnosis.valuationValidatedBy = null;
                diagnosis.valuationValidatedAt = null;
            }
            return new SavedListing(new ListingSaveResult(listing.id, brokerId != null), listing);
        });
        if (listingId == null && saved.result().id() != null) {
            notifyMatchedBuyers(saved.listing());
        }
        return saved.result();
    }

    public ListingEditView listingForEditing(UserView seller, Long listingId) {
        LOGGER.info("Récupération de l'annonce pour modification par l'utilisateur avec l'ID : " + (seller == null ? "null" : seller.id()));
        requireRole(seller, Role.SELLER);
        return Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            requireListingSeller(seller, listing);
            if ("BROKER_ACCEPTED".equals(listing.status)) {
                throw new IllegalArgumentException("Ce dossier a été accepté par un courtier et ne peut plus être modifié.");
            }
            UserAccount broker = listing.brokerId == null ? null : em.find(UserAccount.class, listing.brokerId);
            ListingFinancialDiagnosis diagnosis = findFinancialDiagnosis(em, listing.id);
            return new ListingEditView(listing.id, broker == null ? null : broker.email,
                    listing.sectorCategory, ActivityTaxonomy.decodeDomains(listing.activityDomains),
                    listing.sector, listing.secondarySector, listing.foundedYear,
                    listing.approximateEmployeeCount, listing.publicRevenueRangeAllowed, listing.description,
                    listing.region, listing.customerSummary, listing.advantages, listing.saleReason,
                    listing.revenue, listing.ebitda, listing.askingPrice, listing.profitMargin,
                    listing.saleConditions, listing.supportPeriod,
                    decryptForDisplay(listing.companyNameEncrypted, "nom d'entreprise", listing.id),
                    decryptForDisplay(listing.exactLocationEncrypted, "localisation exacte", listing.id),
                    listing.transactionType == null ? ListingTransactionType.FULL_SALE : listing.transactionType,
                    diagnosis == null ? null : diagnosis.netIncome,
                    diagnosis == null ? null : diagnosis.totalAssets,
                    diagnosis == null ? null : diagnosis.totalDebts,
                    diagnosis == null ? null : diagnosis.fixedAssets,
                    diagnosis == null ? null : diagnosis.financialOutlook,
                    diagnosis == null ? null : diagnosis.countryCode,
                    diagnosis == null ? "EUR" : diagnosis.currencyCode);
        });
    }

    public List<ListingView> listingsFor(UserView user) {
        return listingsFor(user, new ListingSearchFilters(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null), false);
    }

    public List<ListingView> listingsFor(UserView user, String sectorFilter,
                                         String regionFilter, BigDecimal maximumPriceFilter) {
        return listingsFor(user, new ListingSearchFilters(sectorFilter, null, regionFilter,
                null, maximumPriceFilter, null, null, null, null, null, null, null, null,
                null, "EUR"), false);
    }

    public List<ListingView> listingsFor(UserView user, ListingSearchFilters filters,
                                         boolean onlyFavorites) {
        if (user == null) {
            throw new SecurityException("Une session authentifiée est nécessaire.");
        }
        validateSearchFilters(filters);
        return Jpa.transaction(em -> {
            List<Listing> listings;
            if (user.role().equals(Role.BUYER.name())) {
                listings = em.createQuery("select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                                Listing.class).getResultList();
            } else if (user.role().equals(Role.SELLER.name())) {
                listings = em.createQuery("select l from Listing l where l.sellerId = :id order by l.createdAt desc",
                                Listing.class).setParameter("id", user.id()).getResultList();
            } else if (user.role().equals(Role.BROKER.name())) {
                listings = em.createQuery(
                                "select l from Listing l where l.brokerId = :id or (l.brokerId is null and l.status = 'PUBLISHED') order by l.createdAt desc",
                                Listing.class).setParameter("id", user.id()).getResultList();
            } else if (Role.SUPER.name().equals(user.role())) {
                listings = em.createQuery("select l from Listing l order by l.createdAt desc",
                        Listing.class).getResultList();
            } else {
                listings = em.createQuery("select l from Listing l where l.brokerId = :id order by l.createdAt desc",
                                Listing.class).setParameter("id", user.id()).getResultList();
            }
            BuyerProfile profile = user.role().equals(Role.BUYER.name()) ? findProfile(em, user.id()) : null;
            Set<Long> favoriteIds = onlyFavorites && Role.BUYER.name().equals(user.role())
                    ? favoriteIds(em, user.id()) : Set.of();
            Set<Long> ndaListingIds = Role.BUYER.name().equals(user.role()) && hasFinancialFilters(filters)
                    ? new HashSet<>(em.createQuery(
                                    "select d from DealRequest d where d.buyerId = :buyerId "
                                            + "and d.buyerSignedAt is not null and d.sellerSignedAt is not null",
                                    DealRequest.class)
                            .setParameter("buyerId", user.id()).getResultList().stream()
                            .map(request -> request.listingId).toList())
                    : Set.of();
            List<ListingView> result = new ArrayList<>();
            for (Listing listing : listings) {
                ListingFinancialDiagnosis diagnosis = findFinancialDiagnosis(em, listing.id);
                if (user.role().equals(Role.BUYER.name())
                        && (!matchesFilters(listing, diagnosis, filters)
                        || hasFinancialFilters(filters) && !ndaListingIds.contains(listing.id)
                        || onlyFavorites && !favoriteIds.contains(listing.id))) {
                    continue;
                }
                result.add(listingView(listing, diagnosis, profile, null,
                        managesListing(user, listing) || Role.SUPER.name().equals(user.role())));
            }
            if (user.role().equals(Role.BUYER.name())) {
                result.sort(Comparator.comparingInt(ListingView::matchPercent).reversed());
            }
            return result;
        });
    }

    public void validateListingSearchFilters(ListingSearchFilters filters) {
        validateSearchFilters(filters);
    }

    public Set<Long> favoriteListingIds(UserView buyer) {
        requireRole(buyer, Role.BUYER);
        return Jpa.transaction(em -> favoriteIds(em, buyer.id()));
    }

    public boolean toggleFavorite(UserView buyer, Long listingId) {
        requireRole(buyer, Role.BUYER);
        return Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            if (!isPublished(listing.status)) {
                throw new IllegalArgumentException("Seules les annonces disponibles peuvent être ajoutées aux favoris.");
            }
            List<BuyerFavorite> existing = em.createQuery(
                            "select f from BuyerFavorite f where f.buyerId = :buyerId and f.listingId = :listingId",
                            BuyerFavorite.class)
                    .setParameter("buyerId", buyer.id()).setParameter("listingId", listingId)
                    .setMaxResults(1).getResultList();
            if (!existing.isEmpty()) {
                em.remove(existing.get(0));
                return false;
            }
            BuyerFavorite favorite = new BuyerFavorite();
            favorite.buyerId = buyer.id();
            favorite.listingId = listingId;
            em.persist(favorite);
            return true;
        });
    }

    public List<NotificationView> notificationsFor(UserView user) {
        if (user == null) {
            return List.of();
        }
        return Jpa.transaction(em -> em.createQuery(
                        "select n from UserNotification n where n.recipientId = :recipientId "
                                + "order by n.createdAt desc, n.id desc", UserNotification.class)
                .setParameter("recipientId", user.id()).setMaxResults(50).getResultList().stream()
                .map(notification -> new NotificationView(notification.id, notification.title,
                        notification.body, notification.createdAt, notification.readAt != null))
                .toList());
    }

    public void markNotificationRead(UserView user, Long notificationId) {
        if (user == null || notificationId == null) {
            throw new SecurityException("Notification inaccessible.");
        }
        Jpa.transaction(em -> {
            UserNotification notification = em.find(UserNotification.class, notificationId);
            if (notification == null || !Objects.equals(notification.recipientId, user.id())) {
                throw new SecurityException("Cette notification ne vous appartient pas.");
            }
            if (notification.readAt == null) {
                notification.readAt = Instant.now();
            }
            return null;
        });
    }

    public List<AppointmentView> appointmentsFor(UserView user, Long requestId) {
        if (user == null || requestId == null) {
            return List.of();
        }
        return Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireDealParticipant(user, request, listing);
            if (!request.isNdaSigned()) {
                throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
            }
            return em.createQuery(
                            "select a from DealAppointment a where a.requestId = :requestId "
                                    + "order by a.appointmentAt desc, a.id desc", DealAppointment.class)
                    .setParameter("requestId", requestId).getResultList().stream()
                    .map(appointment -> new AppointmentView(appointment.id, appointment.requestId,
                            appointment.proposedBy, appointment.appointmentAt,
                            appointment.status.name(), appointment.createdAt))
                    .toList();
        });
    }

    public void proposeAppointment(UserView user, Long requestId, LocalDateTime proposedTime) {
        if (proposedTime == null || !proposedTime.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Choisissez une date et une heure futures.");
        }
        Long recipientId = Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireDealParticipant(user, request, listing);
            if (!request.isNdaSigned()) {
                throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
            }
            DealAppointment appointment = new DealAppointment();
            appointment.requestId = requestId;
            appointment.proposedBy = user.id();
            appointment.appointmentAt = proposedTime.atZone(ZoneId.systemDefault()).toInstant();
            appointment.status = AppointmentStatus.PENDING;
            appointment.createdAt = Instant.now();
            em.persist(appointment);
            return otherDealParticipant(user, request, listing);
        });
        notifyUser(recipientId, "Nouvelle proposition de rendez-vous",
                "Un participant au dossier #" + requestId + " a proposé une date de rendez-vous.");
    }

    public void respondToAppointment(UserView user, Long appointmentId, boolean accept) {
        Long proposerId = Jpa.transaction(em -> {
            DealAppointment appointment = em.find(DealAppointment.class, appointmentId);
            if (appointment == null) {
                throw new IllegalArgumentException("Cette proposition de rendez-vous n'existe pas.");
            }
            DealRequest request = requireRequest(em, appointment.requestId);
            Listing listing = requireListing(em, request.listingId);
            requireDealParticipant(user, request, listing);
            if (!request.isNdaSigned()) {
                throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
            }
            if (Objects.equals(appointment.proposedBy, user.id())
                    || appointment.status != AppointmentStatus.PENDING) {
                throw new IllegalArgumentException("Seul l'autre participant peut traiter une proposition en attente.");
            }
            appointment.status = accept ? AppointmentStatus.CONFIRMED : AppointmentStatus.DECLINED;
            return appointment.proposedBy;
        });
        notifyUser(proposerId, accept ? "Rendez-vous confirmé" : "Proposition de rendez-vous refusée",
                "L'autre participant a répondu à votre proposition de rendez-vous.");
    }

    public List<PublicListingView> publicListings() {
        return Jpa.transaction(em -> {
            List<PublicListingView> result = new ArrayList<>();
            for (Listing listing : em.createQuery(
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') "
                            + "order by l.createdAt desc", Listing.class).getResultList()) {
                result.add(publicListingView(listing, findFinancialDiagnosis(em, listing.id)));
            }
            return result;
        });
    }

    public List<ArticleView> publishedArticles() {
        return Jpa.transaction(em -> em.createQuery(
                        "select a from Article a where a.status = 'PUBLISHED' "
                                + "order by a.publishedAt desc, a.id desc", Article.class)
                .getResultList().stream().map(MarketplaceService::articleView).toList());
    }

    public ArticleView publishedArticle(Long articleId) {
        return Jpa.transaction(em -> {
            Article article = articleId == null ? null : em.find(Article.class, articleId);
            return article == null || !"PUBLISHED".equals(article.status) ? null : articleView(article);
        });
    }

    public void processFinancials(UserView broker, Long listingId, BigDecimal revenue, BigDecimal ebitda,
                                  BigDecimal valuationMultiple) {
        LOGGER.info("Traitement des informations financières pour l'annonce avec l'ID : " + listingId + " par le courtier avec l'ID : " + (broker == null ? "null" : broker.id()));
        requireRole(broker, Role.BROKER);
        if (valuationMultiple != null
                && (valuationMultiple.compareTo(BigDecimal.ZERO) <= 0
                || valuationMultiple.compareTo(BigDecimal.valueOf(30)) > 0)) {
            throw new IllegalArgumentException("Le multiple de valorisation doit être entre 0 et 30.");
        }
        validateDecimal(revenue, 16, 2, "Chiffre d'affaires retraité");
        validateDecimal(ebitda, 16, 2, "EBE / EBITDA retraité");
        validateNonNegative(valuationMultiple, "Multiple de valorisation");
        Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            requireListingManager(broker, listing);
            if (revenue != null && revenue.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Le chiffre d'affaires doit être positif ou nul.");
            }
            if (revenue != null) {
                listing.revenue = revenue;
            }
            if (ebitda != null) {
                listing.ebitda = ebitda;
            }
            if (listing.revenue != null && listing.ebitda != null && listing.revenue.compareTo(BigDecimal.ZERO) > 0) {
                listing.profitMargin = listing.ebitda.multiply(BigDecimal.valueOf(100))
                        .divide(listing.revenue, 2, java.math.RoundingMode.HALF_UP);
                validateDecimal(listing.profitMargin, 7, 2, "Marge bénéficiaire calculée");
            }
            if (valuationMultiple != null && listing.ebitda != null) {
                if (listing.ebitda.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Un EBITDA négatif ne peut être valorisé par un multiple positif.");
                }
                BigDecimal estimatedPrice = listing.ebitda.multiply(valuationMultiple)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                validateDecimal(estimatedPrice, 16, 2, "Prix estimatif calculé");
                listing.askingPrice = estimatedPrice;
                ListingFinancialDiagnosis diagnosis = findFinancialDiagnosis(em, listing.id);
                if (diagnosis == null) {
                    diagnosis = new ListingFinancialDiagnosis();
                    diagnosis.listingId = listing.id;
                    em.persist(diagnosis);
                }
                diagnosis.valuationMultiple = valuationMultiple;
                diagnosis.valuationValidatedBy = broker.id();
                diagnosis.valuationValidatedAt = Instant.now();
            }
            return null;
        });
    }

    public List<DealView> dealsFor(UserView user) {
        LOGGER.info("Récupération des deals pour l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        return Jpa.transaction(em -> {
            List<DealRequest> requests;
            if (user.role().equals(Role.BUYER.name())) {
                requests = em.createQuery("select d from DealRequest d where d.buyerId = :id order by d.createdAt desc",
                                DealRequest.class).setParameter("id", user.id()).getResultList();
            } else if (user.role().equals(Role.SELLER.name())) {
                requests = em.createQuery(
                                "select d from DealRequest d, Listing l where d.listingId = l.id and l.sellerId = :id order by d.createdAt desc",
                                DealRequest.class).setParameter("id", user.id()).getResultList();
            } else if (Role.SUPER.name().equals(user.role())) {
                requests = em.createQuery("select d from DealRequest d order by d.createdAt desc",
                        DealRequest.class).getResultList();
            } else {
                requests = em.createQuery(
                                "select d from DealRequest d, Listing l where d.listingId = l.id and l.brokerId = :id order by d.createdAt desc",
                                DealRequest.class).setParameter("id", user.id()).getResultList();
            }
            return requests.stream().map(request -> dealView(em, request, user)).toList();
        });
    }

    public List<ListingSaleProcessView> listingSaleProcessesFor(UserView user) {
        if (user == null || !(Role.SUPER.name().equals(user.role())
                || Role.BROKER.name().equals(user.role()) || Role.SELLER.name().equals(user.role()))) {
            throw new SecurityException("Accès réservé au cédant, au courtier ou à l'administration ALP.");
        }
        return Jpa.transaction(em -> {
            List<Listing> listings;
            if (Role.SUPER.name().equals(user.role())) {
                listings = em.createQuery("select l from Listing l order by l.createdAt desc", Listing.class)
                        .getResultList();
            } else if (Role.SELLER.name().equals(user.role())) {
                listings = em.createQuery("select l from Listing l where l.sellerId = :id order by l.createdAt desc",
                        Listing.class).setParameter("id", user.id()).getResultList();
            } else {
                listings = em.createQuery("select l from Listing l where l.brokerId = :id order by l.createdAt desc",
                        Listing.class).setParameter("id", user.id()).getResultList();
            }
            boolean canAdvance = Role.SUPER.name().equals(user.role()) || Role.BROKER.name().equals(user.role());
            return listings.stream().map(listing -> listingSaleProcessView(em, listing, user.id(), canAdvance)).toList();
        });
    }

    public void advanceListingSaleProcess(UserView user, Long listingId) {
        if (user == null || !(Role.SUPER.name().equals(user.role()) || Role.BROKER.name().equals(user.role()))) {
            throw new SecurityException("Seuls un courtier mandaté ou l'administration ALP peuvent faire avancer le processus.");
        }
        Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            requireListingManager(user, listing);
            ListingSaleProcess process = ensureListingSaleProcess(em, listing, user.id());
            SaleProcessStage current = SaleProcessStage.valueOf(process.stage);
            SaleProcessStage next = current.next();
            if (next == null) {
                throw new IllegalArgumentException("Le processus de cession est déjà clôturé.");
            }
            Instant now = Instant.now();
            process.stage = next.name();
            process.updatedBy = user.id();
            process.updatedAt = now;
            ListingSaleProcessHistory history = new ListingSaleProcessHistory();
            history.listingId = listing.id;
            history.fromStage = current.name();
            history.toStage = next.name();
            history.changedBy = user.id();
            history.changedAt = now;
            em.persist(history);
            return null;
        });
    }

    public void requestIntroduction(UserView buyer, Long listingId) {
        LOGGER.info("Demande de mise en relation pour l'annonce avec l'ID : " + listingId + " par l'acheteur avec l'ID : " + (buyer == null ? "null" : buyer.id()));
        requireRole(buyer, Role.BUYER);
        ListingRecipients recipients = Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            if (!isPublished(listing.status)) {
                throw new IllegalArgumentException("Cette annonce n'est pas disponible.");
            }
            Long existing = em.createQuery(
                            "select count(d) from DealRequest d where d.listingId = :listingId and d.buyerId = :buyerId",
                            Long.class)
                    .setParameter("listingId", listingId).setParameter("buyerId", buyer.id()).getSingleResult();
            if (existing > 0) {
                throw new IllegalArgumentException("Vous avez déjà demandé une mise en relation pour cette annonce.");
            }
            DealRequest request = new DealRequest();
            request.listingId = listingId;
            request.buyerId = buyer.id();
            request.status = "PENDING";
            request.createdAt = Instant.now();
            em.persist(request);
            return new ListingRecipients(listing.sellerId, listing.brokerId);
        });
        notifyUser(recipients.sellerId(), "Nouvelle manifestation d'intérêt",
                "Un acquéreur souhaite être mis en relation au sujet d'une annonce.");
        if (!Objects.equals(recipients.brokerId(), recipients.sellerId())) {
            notifyUser(recipients.brokerId(), "Nouvelle manifestation d'intérêt",
                    "Un acquéreur souhaite être mis en relation au sujet d'une annonce suivie par vous.");
        }
    }

    public void decideIntroduction(UserView user, Long requestId, boolean accept) {
        LOGGER.info("Décision sur la demande de mise en relation avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()) + " acceptée : " + accept);
        Long buyerId = Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireOperationManager(user, listing);
            if (!"PENDING".equals(request.status)) {
                throw new IllegalArgumentException("Cette demande a déjà été traitée.");
            }
            request.status = accept ? "NDA_PENDING" : "DECLINED";
            request.ndaVersion = accept ? "MVP-1.0" : null;
            return request.buyerId;
        });
        notifyUser(buyerId, accept ? "Demande acceptée" : "Demande refusée",
                accept ? "Votre demande de mise en relation a été acceptée. Vous pouvez signer l'accord de confidentialité."
                        : "Votre demande de mise en relation n'a pas été retenue.");
    }

    public void signNda(UserView user, Long requestId, String signatureName) {
        LOGGER.info("Signature de l'accord de confidentialité pour la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        if (signatureName == null || signatureName.isBlank() || signatureName.length() > 160) {
            throw new IllegalArgumentException("Saisissez votre nom complet comme signature.");
        }
        Long otherParticipantId = Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            boolean isBuyer = request.buyerId.equals(user.id()) && user.role().equals(Role.BUYER.name());
            boolean isManager = managesListing(user, listing);
            if ((!isBuyer && !isManager) || "DECLINED".equals(request.status) || "PENDING".equals(request.status)) {
                throw new IllegalArgumentException("Vous n'êtes pas autorisé à signer cet accord.");
            }
            if (isBuyer && !buyerKycValidated(em, user.id())) {
                throw new SecurityException(
                        "Votre profil acquéreur doit être vérifié avant de signer l’accord de confidentialité.");
            }
            if (isBuyer && request.buyerSignedAt == null) {
                request.buyerSignatureName = signatureName.trim();
                request.buyerSignedAt = Instant.now();
            } else if (isManager && request.sellerSignedAt == null) {
                request.sellerSignatureName = signatureName.trim();
                request.sellerSignedAt = Instant.now();
            }
            request.status = request.isNdaSigned() ? "NDA_SIGNED" : "NDA_PENDING";
            return isBuyer ? listing.sellerId : request.buyerId;
        });
        notifyUser(otherParticipantId, "Signature de confidentialité enregistrée",
                "L'autre participant a signé l'accord de confidentialité du dossier #" + requestId + ".");
    }

    public void sendMessage(UserView user, Long requestId, String body) {
        LOGGER.info("Envoi d'un message pour la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        if (body == null || body.isBlank() || body.length() > 8000) {
            throw new IllegalArgumentException("Le message doit contenir entre 1 et 8 000 caractères.");
        }
        String encryptedBody = CryptoService.encrypt(body.trim());
        Long recipientId = Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireDealParticipant(user, request, listing);
            if (!request.isNdaSigned()) {
                throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
            }
            SecureMessage message = new SecureMessage();
            message.requestId = requestId;
            message.senderId = user.id();
            message.encryptedBody = encryptedBody;
            message.createdAt = Instant.now();
            em.persist(message);
            return otherDealParticipant(user, request, listing);
        });
        notifyUser(recipientId, "Nouveau message sécurisé",
                "Un participant a envoyé un message dans le dossier #" + requestId + ".");
    }

    public List<MessageView> messages(UserView user, Long requestId) {
        LOGGER.info("Récupération des messages pour la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        return Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireDealParticipant(user, request, listing);
            if (!request.isNdaSigned()) {
                throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
            }
            List<SecureMessage> messages = em.createQuery(
                            "select m from SecureMessage m where m.requestId = :requestId order by m.createdAt",
                            SecureMessage.class).setParameter("requestId", requestId).getResultList();
            return messages.stream().map(message -> new MessageView(
                    em.find(UserAccount.class, message.senderId).fullName,
                    decryptForDisplay(message.encryptedBody, "message sécurisé", message.id),
                    message.createdAt)).toList();
        });
    }

    public DocumentView storeDocument(UserView user, Long listingId, Long requestId,
                                      String originalName, String contentType, byte[] bytes) {
        return storeDocument(user, listingId, requestId, null, originalName, contentType, bytes);
    }

    public DocumentView storeSellerVerificationDocument(UserView seller, String verificationType,
                                                        String originalName, String contentType, byte[] bytes) {
        requireRole(seller, Role.SELLER);
        SellerDocumentType type;
        try {
            type = SellerDocumentType.valueOf(verificationType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Choisissez une catégorie de document valide.");
        }
        return storeDocument(seller, null, null, type.name(), "SELLER_VERIFICATION",
                originalName, contentType, bytes);
    }

    public DocumentView storeBuyerKycDocument(UserView buyer, String documentType,
                                              String originalName, String contentType, byte[] bytes) {
        requireRole(buyer, Role.BUYER);
        BuyerKycDocumentType type;
        try {
            type = BuyerKycDocumentType.valueOf(documentType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Choisissez une catégorie de document KYC valide.");
        }
        return storeDocument(buyer, null, null, type.name(), "BUYER_KYC",
                originalName, contentType, bytes);
    }

    private DocumentView storeDocument(UserView user, Long listingId, Long requestId, String verificationType,
                                       String originalName, String contentType, byte[] bytes) {
        return storeDocument(user, listingId, requestId, verificationType,
                verificationType == null ? null : "SELLER_VERIFICATION",
                originalName, contentType, bytes);
    }

    private DocumentView storeDocument(UserView user, Long listingId, Long requestId, String verificationType,
                                       String documentPurpose, String originalName, String contentType, byte[] bytes) {
        LOGGER.info("Stockage d'un document pour l'annonce avec l'ID : " + listingId + " et la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        if (documentPurpose != null) {
            if ("SELLER_VERIFICATION".equals(documentPurpose)) {
                requireRole(user, Role.SELLER);
            } else if ("BUYER_KYC".equals(documentPurpose)) {
                requireRole(user, Role.BUYER);
            } else {
                throw new IllegalArgumentException("Type de document sécurisé invalide.");
            }
            if (listingId != null || requestId != null) {
                throw new IllegalArgumentException("Un document de vérification ne peut être associé à un dossier de cession.");
            }
        }
        if (bytes == null || bytes.length == 0 || bytes.length > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("Le fichier doit faire entre 1 octet et 20 Mo.");
        }
        ValidatedUpload upload = validateDocumentUpload(originalName);
        String key = UUID.randomUUID().toString();
        Path target = storageDirectory().resolve(key + ".bin");
        byte[] encrypted = CryptoService.encrypt(bytes);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, encrypted);
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible d'enregistrer le document sécurisé.", exception);
        }
        try {
            return Jpa.transaction(em -> {
                Listing listing = null;
                DealRequest request = null;
                if (documentPurpose == null) {
                    listing = listingId == null ? null : requireListing(em, listingId);
                    request = requestId == null ? null : requireRequest(em, requestId);
                    if (request != null) {
                        Listing requestListing = requireListing(em, request.listingId);
                        if (listing != null && !listing.id.equals(request.listingId)) {
                            throw new IllegalArgumentException("Dossier de partage incohérent.");
                        }
                        requireNdaAccess(user, request, requestListing);
                        listing = requestListing;
                    } else if (listing != null) {
                        requireListingManager(user, listing);
                    } else {
                        throw new IllegalArgumentException("Un dossier de collecte ou un dossier NDA est requis.");
                    }
                }
                SecureDocument document = new SecureDocument();
                document.listingId = listing == null ? null : listing.id;
                document.requestId = requestId;
                document.documentPurpose = documentPurpose;
                document.verificationType = verificationType;
                document.uploadedBy = user.id();
                document.originalName = CryptoService.encrypt(upload.safeName());
                document.storageKey = key;
                document.contentType = normalizeContentType(contentType, upload.extension());
                document.sizeBytes = bytes.length;
                document.uploadedAt = Instant.now();
                if (request != null && Role.SELLER.name().equals(user.role())
                        && Objects.equals(user.id(), listing.sellerId)) {
                    document.sellerAuthorized = true;
                    document.sellerAuthorizedBy = user.id();
                    document.sellerAuthorizedAt = document.uploadedAt;
                }
                em.persist(document);
                if ("SELLER_VERIFICATION".equals(documentPurpose)) {
                    SellerVerification verification = findSellerVerification(em, user.id());
                    if (verification == null) {
                        verification = new SellerVerification();
                        verification.userId = user.id();
                        verification.status = SellerVerificationStatus.DOCUMENTS_PENDING;
                        em.persist(verification);
                    } else if (verification.status == SellerVerificationStatus.NEEDS_CORRECTION) {
                        verification.status = SellerVerificationStatus.DOCUMENTS_PENDING;
                        verification.feedback = null;
                    }
                    verification.updatedAt = Instant.now();
                } else if ("BUYER_KYC".equals(documentPurpose)) {
                    BuyerProfile profile = findProfile(em, user.id());
                    if (profile == null || !BuyerKycDocumentType.valueOf(verificationType)
                            .isAvailableFor(profile.buyerType)) {
                        throw new IllegalArgumentException(
                                "Cette catégorie de document KYC ne correspond pas au compte acquéreur.");
                    }
                    BuyerKyc kyc = findBuyerKyc(em, user.id());
                    if (kyc == null) {
                        kyc = new BuyerKyc();
                        kyc.userId = user.id();
                        kyc.status = BuyerKycStatus.DOCUMENTS_PENDING;
                        em.persist(kyc);
                    }
                    kyc.status = BuyerKycStatus.DOCUMENTS_PENDING;
                    kyc.feedback = null;
                    kyc.updatedAt = Instant.now();
                }
                em.flush();
                return documentView(document);
            });
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }
    }

    private static ValidatedUpload validateDocumentUpload(String originalName) {
        String normalizedName = originalName == null ? "" : originalName.replace('\\', '/');
        String safeName = normalizedName.substring(normalizedName.lastIndexOf('/') + 1);
        if (safeName.isBlank() || safeName.length() > 255) {
            throw new IllegalArgumentException("Nom de fichier invalide.");
        }
        String extension = safeName.contains(".")
                ? safeName.substring(safeName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Format non autorisé. Formats acceptés : PDF, DOCX, XLSX, PNG et JPEG.");
        }
        return new ValidatedUpload(safeName, extension);
    }

    public DocumentDownload readDocument(UserView user, Long documentId) {
        LOGGER.info("Lecture du document avec l'ID : " + documentId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        AuthorizedDocument document = Jpa.transaction(em -> {
            SecureDocument found = em.find(SecureDocument.class, documentId);
            if (found == null) {
                throw new IllegalArgumentException("Document introuvable.");
            }
            if ("SELLER_VERIFICATION".equals(found.documentPurpose)) {
                requireSellerVerificationDocumentAccess(user, found);
            } else if ("BUYER_KYC".equals(found.documentPurpose)) {
                requireBuyerKycDocumentAccess(user, found);
            } else {
                Listing listing = requireListing(em, found.listingId);
                if (found.requestId != null) {
                    DealRequest request = requireRequest(em, found.requestId);
                    requireDealDocumentAccess(user, request, listing);
                    if (Role.BUYER.name().equals(user.role())
                            && (!found.sellerAuthorized || !found.alpValidated
                            || found.accessExpiresAt == null || !found.accessExpiresAt.isAfter(Instant.now()))) {
                        throw new SecurityException("L’accès acquéreur à ce document n’est pas approuvé ou a expiré.");
                    }
                } else {
                    requireListingReviewer(user, listing);
                }
            }

            return new AuthorizedDocument(found.id, found.requestId, CryptoService.decrypt(found.originalName),
                    found.contentType, found.storageKey);
        });
        try {
            Path file = storageDirectory().resolve(document.storageKey() + ".bin").normalize();
            if (!file.getParent().equals(storageDirectory())) {
                throw new IllegalStateException("Chemin de document invalide.");
            }
            byte[] content = CryptoService.decrypt(Files.readAllBytes(file));
            if (document.requestId() != null) {
                recordDataRoomAccess(user, document);
            }
            return new DocumentDownload(document.name(), document.contentType(), content);
        } catch (IOException exception) {
            throw new IllegalStateException("Le document sécurisé est indisponible.", exception);
        }
    }

    private void recordDataRoomAccess(UserView user, AuthorizedDocument document) {
        DataRoomAccessLog access = new DataRoomAccessLog();
        access.documentId = document.id();
        access.requestId = document.requestId();
        access.accessedBy = user.id();
        access.accessedByEmail = requiredText(user.email(), 320, "Adresse courriel");
        access.accessedAt = Instant.now();
        Jpa.transaction(em -> {
            em.persist(access);
            return null;
        });
    }

    private static String documentNameForAudit(SecureDocument document) {
        try {
            return CryptoService.decrypt(document.originalName);
        } catch (CryptoService.UnreadableCiphertextException exception) {
            logUnavailableEncryptedData("nom de document", document.id);
            return UNAVAILABLE_ENCRYPTED_DATA;
        }
    }

    public List<DocumentView> sellerVerificationDocuments(UserView requester, Long sellerId) {
        if (requester == null || sellerId == null) {
            throw new SecurityException("Accès réservé au cédant propriétaire, au courtier ou à l’administrateur.");
        }
        if (Role.SELLER.name().equals(requester.role()) && Objects.equals(requester.id(), sellerId)) {
            return sellerVerificationDocumentsFor(sellerId);
        }
        requireVerificationReviewer(requester);
        return sellerVerificationDocumentsFor(sellerId);
    }

    public List<DocumentView> sellerVerificationDocuments(UserView seller) {
        requireRole(seller, Role.SELLER);
        return sellerVerificationDocumentsFor(seller.id());
    }

    public BuyerKycView buyerKycStatus(UserView buyer) {
        requireRole(buyer, Role.BUYER);
        return Jpa.transaction(em -> {
            BuyerProfile profile = findProfile(em, buyer.id());
            if (profile == null || profile.buyerType == null) {
                return null;
            }
            BuyerKyc kyc = findBuyerKyc(em, buyer.id());
            List<DocumentView> documents = em.createQuery(
                            "select d from SecureDocument d where d.documentPurpose = 'BUYER_KYC' "
                                    + "and d.uploadedBy = :id order by d.uploadedAt desc",
                            SecureDocument.class).setParameter("id", buyer.id()).getResultList()
                    .stream().map(MarketplaceService::documentView).toList();
            UserAccount account = em.find(UserAccount.class, buyer.id());
            return buyerKycView(profile, account, kyc, documents);
        });
    }

    public List<BuyerKycView> buyerKycQueue(UserView reviewer) {
        requireVerificationReviewer(reviewer);
        return Jpa.transaction(em -> {
            List<BuyerProfile> profiles = em.createQuery(
                            "select p from BuyerProfile p where p.buyerType is not null order by p.userId desc",
                            BuyerProfile.class).getResultList();
            if (profiles.isEmpty()) {
                return List.of();
            }
            List<Long> buyerIds = profiles.stream().map(profile -> profile.userId).toList();
            Map<Long, UserAccount> accounts = new HashMap<>();
            em.createQuery("select u from UserAccount u where u.id in :ids", UserAccount.class)
                    .setParameter("ids", buyerIds).getResultList()
                    .forEach(account -> accounts.put(account.id, account));
            Map<Long, BuyerKyc> kycByBuyer = new HashMap<>();
            em.createQuery("select k from BuyerKyc k where k.userId in :ids", BuyerKyc.class)
                    .setParameter("ids", buyerIds).getResultList()
                    .forEach(kyc -> kycByBuyer.put(kyc.userId, kyc));
            Map<Long, List<DocumentView>> documentsByBuyer = new HashMap<>();
            em.createQuery("select d from SecureDocument d where d.documentPurpose = 'BUYER_KYC' "
                                    + "and d.uploadedBy in :ids order by d.uploadedAt desc",
                            SecureDocument.class)
                    .setParameter("ids", buyerIds).getResultList()
                    .forEach(document -> documentsByBuyer
                            .computeIfAbsent(document.uploadedBy, ignored -> new ArrayList<>())
                            .add(documentView(document)));
            return profiles.stream()
                    .filter(profile -> accounts.containsKey(profile.userId))
                    .map(profile -> buyerKycView(profile, accounts.get(profile.userId),
                            kycByBuyer.get(profile.userId),
                            List.copyOf(documentsByBuyer.getOrDefault(profile.userId, List.of()))))
                    .toList();
        });
    }

    public void updateBuyerKyc(UserView reviewer, Long buyerId, String targetStatus, String feedback,
                               boolean emailVerified, boolean phoneVerified, boolean sourceOfFundsRequired,
                               boolean beneficialOwnersRequired) {
        requireVerificationReviewer(reviewer);
        if (buyerId == null) {
            throw new IllegalArgumentException("Sélectionnez un acquéreur.");
        }
        BuyerKycStatus target;
        try {
            target = BuyerKycStatus.valueOf(targetStatus);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Sélectionnez une étape KYC valide.");
        }
        String normalizedFeedback = trimToNull(feedback);
        validateLength(normalizedFeedback, 1000, "Le commentaire KYC");
        Jpa.transaction(em -> {
            BuyerProfile profile = findProfile(em, buyerId);
            if (profile == null || profile.buyerType == null) {
                throw new IllegalArgumentException("Profil acquéreur introuvable.");
            }
            BuyerKyc kyc = findBuyerKyc(em, buyerId);
            if (kyc == null) {
                kyc = new BuyerKyc();
                kyc.userId = buyerId;
                kyc.status = BuyerKycStatus.DOCUMENTS_PENDING;
                kyc.updatedAt = Instant.now();
                em.persist(kyc);
            }
            BuyerKycStatus current = kyc.status == null
                    ? BuyerKycStatus.DOCUMENTS_PENDING : kyc.status;
            boolean allowed = switch (target) {
                case UNDER_REVIEW -> current == BuyerKycStatus.DOCUMENTS_PENDING;
                case VALIDATED, NEEDS_CORRECTION -> current == BuyerKycStatus.UNDER_REVIEW;
                case DOCUMENTS_PENDING -> false;
            };
            if (!allowed) {
                throw new IllegalArgumentException("Cette transition KYC n’est pas autorisée depuis l’état « "
                        + current.label() + " ».");
            }
            if (target == BuyerKycStatus.UNDER_REVIEW || target == BuyerKycStatus.VALIDATED) {
                validateRequiredBuyerKycDocuments(em, buyerId, profile.buyerType);
            }
            if (target == BuyerKycStatus.NEEDS_CORRECTION && normalizedFeedback == null) {
                throw new IllegalArgumentException("Précisez les pièces manquantes ou les corrections attendues.");
            }
            if (target == BuyerKycStatus.VALIDATED) {
                if (!emailVerified || !phoneVerified) {
                    throw new IllegalArgumentException(
                            "Le courriel et le téléphone doivent être vérifiés avant la validation KYC.");
                }
                if (sourceOfFundsRequired
                        && buyerKycDocumentCount(em, buyerId, BuyerKycDocumentType.SOURCE_OF_FUNDS) == 0L) {
                    throw new IllegalArgumentException(
                            "Le justificatif d’origine des fonds requis doit être déposé avant la validation.");
                }
                if (beneficialOwnersRequired
                        && buyerKycDocumentCount(em, buyerId, BuyerKycDocumentType.BENEFICIAL_OWNERS) == 0L) {
                    throw new IllegalArgumentException(
                            "Les informations sur les bénéficiaires effectifs requises doivent être déposées avant la validation.");
                }
            }
            kyc.status = target;
            kyc.emailVerified = emailVerified;
            kyc.phoneVerified = phoneVerified;
            kyc.sourceOfFundsRequired = sourceOfFundsRequired;
            kyc.beneficialOwnersRequired = beneficialOwnersRequired;
            kyc.feedback = target == BuyerKycStatus.NEEDS_CORRECTION ? normalizedFeedback : null;
            kyc.updatedBy = reviewer.id();
            kyc.updatedAt = Instant.now();
            return null;
        });
    }

    private List<DocumentView> sellerVerificationDocumentsFor(Long sellerId) {
        return Jpa.transaction(em -> em.createQuery(
                        "select d from SecureDocument d where d.documentPurpose = 'SELLER_VERIFICATION' "
                                + "and d.uploadedBy = :id order by d.uploadedAt desc",
                        SecureDocument.class).setParameter("id", sellerId).getResultList()
                .stream().map(MarketplaceService::documentView).toList());
    }

    public SellerVerificationStatusView sellerVerificationStatus(UserView seller) {
        requireRole(seller, Role.SELLER);
        return Jpa.transaction(em -> {
            SellerVerification verification = findSellerVerification(em, seller.id());
            return verification == null
                    ? new SellerVerificationStatusView(SellerVerificationStatus.DOCUMENTS_PENDING, null, null)
                    : new SellerVerificationStatusView(verification.status, verification.feedback,
                    verification.updatedAt);
        });
    }

    public List<SellerVerificationView> sellerVerificationQueue(UserView reviewer) {
        requireVerificationReviewer(reviewer);
        return Jpa.transaction(em -> {
            List<UserAccount> sellers = em.createQuery(
                            "select u from UserAccount u where u.role = :role order by u.createdAt desc, u.id desc",
                            UserAccount.class)
                    .setParameter("role", Role.SELLER).getResultList();
            Map<Long, SellerVerification> verifications = new HashMap<>();
            em.createQuery("select v from SellerVerification v", SellerVerification.class)
                    .getResultList().forEach(verification -> verifications.put(verification.userId, verification));
            Map<Long, List<DocumentView>> documentsBySeller = new HashMap<>();
            em.createQuery("select d from SecureDocument d where d.documentPurpose = 'SELLER_VERIFICATION' "
                                    + "order by d.uploadedAt desc", SecureDocument.class)
                    .getResultList().forEach(document -> documentsBySeller
                            .computeIfAbsent(document.uploadedBy, ignored -> new ArrayList<>())
                            .add(documentView(document)));
            return sellers.stream().map(account -> {
                SellerVerification verification = verifications.get(account.id);
                return new SellerVerificationView(account.id, account.fullName, account.email,
                        account.organization,
                        verification == null || verification.status == null
                                ? SellerVerificationStatus.DOCUMENTS_PENDING : verification.status,
                        verification == null ? null : verification.feedback,
                        verification == null ? null : verification.updatedAt,
                        List.copyOf(documentsBySeller.getOrDefault(account.id, List.of())));
            }).toList();
        });
    }

    public void updateSellerVerification(UserView reviewer, Long sellerId, String targetStatus,
                                         String feedback) {
        requireVerificationReviewer(reviewer);
        if (sellerId == null) {
            throw new IllegalArgumentException("Sélectionnez un compte cédant.");
        }
        SellerVerificationStatus target;
        try {
            target = SellerVerificationStatus.valueOf(targetStatus);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Sélectionnez une étape de vérification valide.");
        }
        String normalizedFeedback = trimToNull(feedback);
        validateLength(normalizedFeedback, 1000, "Le commentaire de vérification");
        Jpa.transaction(em -> {
            UserAccount account = em.find(UserAccount.class, sellerId);
            if (account == null || account.role != Role.SELLER) {
                throw new IllegalArgumentException("Compte cédant introuvable.");
            }
            SellerVerification verification = findSellerVerification(em, sellerId);
            if (verification == null) {
                verification = new SellerVerification();
                verification.userId = sellerId;
                verification.status = SellerVerificationStatus.DOCUMENTS_PENDING;
                verification.updatedAt = Instant.now();
                em.persist(verification);
            }
            SellerVerificationStatus current = verification.status == null
                    ? SellerVerificationStatus.DOCUMENTS_PENDING : verification.status;
            boolean allowed = switch (target) {
                case UNDER_REVIEW -> current == SellerVerificationStatus.DOCUMENTS_PENDING;
                case DOCUMENTS_VALIDATED, NEEDS_CORRECTION -> current == SellerVerificationStatus.UNDER_REVIEW;
                case ACCOUNT_VALIDATED -> current == SellerVerificationStatus.DOCUMENTS_VALIDATED;
                case DOCUMENTS_PENDING -> false;
            };
            if (!allowed) {
                throw new IllegalArgumentException("Cette transition n’est pas autorisée depuis l’état « "
                        + current.label() + " ».");
            }
            if (target == SellerVerificationStatus.UNDER_REVIEW
                    || target == SellerVerificationStatus.DOCUMENTS_VALIDATED
                    || target == SellerVerificationStatus.ACCOUNT_VALIDATED) {
                Long documentCount = em.createQuery(
                                "select count(d) from SecureDocument d where d.documentPurpose = 'SELLER_VERIFICATION' "
                                        + "and d.uploadedBy = :id", Long.class)
                        .setParameter("id", sellerId).getSingleResult();
                if (documentCount == null || documentCount == 0L) {
                    throw new IllegalArgumentException("Le cédant doit téléverser au moins une pièce justificative.");
                }
            }
            if (target == SellerVerificationStatus.NEEDS_CORRECTION && normalizedFeedback == null) {
                throw new IllegalArgumentException("Précisez les pièces manquantes ou les corrections attendues.");
            }
            verification.status = target;
            verification.feedback = target == SellerVerificationStatus.NEEDS_CORRECTION ? normalizedFeedback : null;
            verification.updatedBy = reviewer.id();
            verification.updatedAt = Instant.now();
            return null;
        });
    }

    public List<DocumentView> documentsFor(UserView user, Long listingId) {
        LOGGER.info("Récupération des documents pour l'annonce avec l'ID : " + listingId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        return Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            requireListingReviewer(user, listing);
            return em.createQuery(
                            "select d from SecureDocument d where d.listingId = :id and d.requestId is null "
                                    + "and d.documentPurpose is null order by d.uploadedAt desc",
                            SecureDocument.class).setParameter("id", listingId).getResultList()
                    .stream().map(MarketplaceService::documentView).toList();
        });
    }

    public List<DocumentView> dealDocuments(UserView user, Long requestId) {
        return Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireDealDocumentAccess(user, request, listing);
            List<SecureDocument> documents = em.createQuery(
                            "select d from SecureDocument d where d.requestId = :id order by d.uploadedAt desc",
                            SecureDocument.class).setParameter("id", requestId).getResultList();
            if (Role.BUYER.name().equals(user.role())) {
                documents = documents.stream()
                        .filter(document -> document.sellerAuthorized && document.alpValidated)
                        .toList();
            }
            return documents.stream().map(MarketplaceService::documentView).toList();
        });
    }

    public List<DocumentAccessLogView> dataRoomAccessLogs(UserView user, Long requestId) {
        return Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            if (user != null && Role.SELLER.name().equals(user.role())) {
                requireListingSeller(user, listing);
            } else {
                requireDataRoomReviewer(user, listing);
            }
            return em.createQuery(
                            "select a from DataRoomAccessLog a where a.requestId = :requestId "
                                    + "order by a.accessedAt desc",
                            DataRoomAccessLog.class).setParameter("requestId", requestId).getResultList()
                    .stream().map(access -> {
                        SecureDocument document = em.find(SecureDocument.class, access.documentId);
                        String name = document == null ? "Document #" + access.documentId
                                : documentNameForAudit(document);
                        return new DocumentAccessLogView(name, access.accessedByEmail, access.accessedAt);
                    }).toList();
        });
    }

    public void setListingPublished(UserView reviewer, Long listingId, boolean published) {
        if (reviewer == null || !(Role.SUPER.name().equals(reviewer.role())
                || Role.BROKER.name().equals(reviewer.role()))) {
            throw new SecurityException("La gestion des annonces est réservée aux courtiers et administrateurs.");
        }
        Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            requireListingPublicationManager(reviewer, listing);
            if (published) {
                if (!isPublished(listing.status) && !"SUSPENDED".equals(listing.status)
                        && !"PENDING_BROKER_APPROVAL".equals(listing.status)) {
                    throw new IllegalArgumentException("Cette annonce ne peut pas être publiée dans son état actuel.");
                }
                listing.status = listing.brokerId == null ? "PUBLISHED" : "BROKER_ACCEPTED";
            } else {
                if (!isPublished(listing.status) && !"SUSPENDED".equals(listing.status)) {
                    throw new IllegalArgumentException("Cette annonce ne peut pas être suspendue dans son état actuel.");
                }
                listing.status = "SUSPENDED";
            }
            return null;
        });
    }

    public void authorizeDealDocument(UserView seller, Long documentId) {
        authorizeDealDocument(seller, documentId, 30);
    }

    public void authorizeDealDocument(UserView seller, Long documentId, Integer accessDurationDays) {
        requireRole(seller, Role.SELLER);
        if (accessDurationDays == null || accessDurationDays < 1 || accessDurationDays > 365) {
            throw new IllegalArgumentException("La durée d’accès doit être comprise entre 1 et 365 jours.");
        }
        Jpa.transaction(em -> {
            SecureDocument document = requireDealDocument(em, documentId);
            DealRequest request = requireRequest(em, document.requestId);
            Listing listing = requireListing(em, request.listingId);
            requireListingSeller(seller, listing);
            if (!request.isNdaSigned()) {
                throw new SecurityException("L’accord de confidentialité doit être signé avant d’autoriser le partage.");
            }
            Instant now = Instant.now();
            if (document.accessExpiresAt != null && document.accessExpiresAt.isAfter(now)) {
                throw new IllegalArgumentException("L’accès au document est déjà actif.");
            }
            document.sellerAuthorized = true;
            document.sellerAuthorizedBy = seller.id();
            document.sellerAuthorizedAt = now;
            document.accessDurationDays = accessDurationDays;
            document.accessExpiresAt = null;
            document.alpValidated = false;
            document.alpValidatedBy = null;
            document.alpValidatedAt = null;
            return null;
        });
    }

    public void validateDealDocument(UserView reviewer, Long documentId) {
        requireVerificationReviewer(reviewer);
        Jpa.transaction(em -> {
            SecureDocument document = requireDealDocument(em, documentId);
            DealRequest request = requireRequest(em, document.requestId);
            Listing listing = requireListing(em, request.listingId);
            requireDataRoomReviewer(reviewer, listing);
            if (!request.isNdaSigned()) {
                throw new SecurityException("L’accord de confidentialité doit être signé avant la validation.");
            }
            if (!document.sellerAuthorized) {
                throw new IllegalArgumentException("Le cédant doit autoriser le partage avant la validation ALP.");
            }
            if (document.accessExpiresAt != null && document.accessExpiresAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("La fenêtre d’accès est déjà active.");
            }
            if (document.alpValidated) {
                throw new IllegalArgumentException("Le cédant doit renouveler son autorisation avant une nouvelle validation.");
            }
            if (document.accessDurationDays == null
                    || document.accessDurationDays < 1 || document.accessDurationDays > 365) {
                throw new IllegalArgumentException("Le cédant doit définir une durée d’accès valide.");
            }
            Instant now = Instant.now();
            document.alpValidated = true;
            document.alpValidatedBy = reviewer.id();
            document.alpValidatedAt = now;
            document.accessExpiresAt = now.plusSeconds(document.accessDurationDays * 24L * 60L * 60L);
            return null;
        });
    }

    public void acceptListing(UserView user, Long listingId) {
        LOGGER.info("Acceptation de l'annonce avec l'ID : " + listingId + " par le courtier avec l'ID : " + (user == null ? "null" : user.id()));
        requireRole(user, Role.BROKER);
        Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            if (listing.brokerId != null && !Objects.equals(user.id(), listing.brokerId)) {
                throw new SecurityException("Ce dossier est déjà attribué à un autre courtier.");
            }
            if ("BROKER_ACCEPTED".equals(listing.status)) {
                throw new IllegalArgumentException("Ce dossier a déjà été accepté par un courtier.");
            }
            if (!isPublished(listing.status) && !"PENDING_BROKER_APPROVAL".equals(listing.status)) {
                throw new IllegalArgumentException("Ce dossier ne peut pas être accepté dans son état actuel.");
            }
            listing.brokerId = user.id();
            listing.status = "BROKER_ACCEPTED";
            return null;
        });
    }

    public void recordMarketplaceViews(UserView buyer) {
        LOGGER.info("Enregistrement des vues du marketplace par l'acheteur avec l'ID : " + (buyer == null ? "null" : buyer.id()));
        requireRole(buyer, Role.BUYER);
        Jpa.transaction(em -> {
            em.createQuery("update Listing l set l.views = l.views + 1 where l.status in ('PUBLISHED', 'BROKER_ACCEPTED')")
                    .executeUpdate();
            return null;
        });
    }

    private static ListingSaleProcess ensureListingSaleProcess(jakarta.persistence.EntityManager em,
                                                                Listing listing, Long actorId) {
        ListingSaleProcess process = em.createQuery(
                        "select p from ListingSaleProcess p where p.listingId = :listingId",
                        ListingSaleProcess.class)
                .setParameter("listingId", listing.id)
                .getResultList().stream().findFirst().orElse(null);
        if (process != null) {
            return process;
        }
        Instant now = Instant.now();
        process = new ListingSaleProcess();
        process.listingId = listing.id;
        process.stage = SaleProcessStage.SELLER_REGISTRATION.name();
        process.updatedBy = actorId;
        process.updatedAt = now;
        em.persist(process);

        ListingSaleProcessHistory initialHistory = new ListingSaleProcessHistory();
        initialHistory.listingId = listing.id;
        initialHistory.toStage = process.stage;
        initialHistory.changedBy = actorId;
        initialHistory.changedAt = now;
        em.persist(initialHistory);
        return process;
    }

    private static ListingSaleProcessView listingSaleProcessView(jakarta.persistence.EntityManager em,
                                                                  Listing listing, Long actorId,
                                                                  boolean canAdvance) {
        ListingSaleProcess process = ensureListingSaleProcess(em, listing, actorId);
        SaleProcessStage stage = SaleProcessStage.valueOf(process.stage);
        List<SaleProcessHistoryView> history = em.createQuery(
                        "select h from ListingSaleProcessHistory h where h.listingId = :listingId "
                                + "order by h.changedAt asc",
                        ListingSaleProcessHistory.class)
                .setParameter("listingId", listing.id).getResultList().stream()
                .map(item -> new SaleProcessHistoryView(
                        SaleProcessStage.valueOf(item.toStage).label(), item.changedAt))
                .toList();
        return new ListingSaleProcessView(listing.id, listing.sector, stage.name(), stage.label(),
                process.updatedAt, history, canAdvance && stage.next() != null);
    }

    private static ListingFinancialDiagnosis findFinancialDiagnosis(jakarta.persistence.EntityManager em,
                                                                      Long listingId) {
        return em.createQuery("select d from ListingFinancialDiagnosis d where d.listingId = :listingId",
                        ListingFinancialDiagnosis.class)
                .setParameter("listingId", listingId)
                .getResultList().stream().findFirst().orElse(null);
    }

    private static ListingView listingView(Listing listing, ListingFinancialDiagnosis diagnosis,
                                           BuyerProfile profile, DealRequest request, boolean managerView) {
        LOGGER.info("Création de la vue pour l'annonce avec l'ID : " + (listing == null ? "null" : listing.id) + " pour le profil acheteur avec l'ID : " + (profile == null ? "null" : profile.id) + " et la demande avec l'ID : " + (request == null ? "null" : request.id));
        boolean ndaSigned = request != null && request.isNdaSigned();
        boolean detailsVisible = ndaSigned || managerView;
        String company = detailsVisible ? decryptForDisplay(listing.companyNameEncrypted, "nom d'entreprise", listing.id) : null;
        String exactLocation = detailsVisible ? decryptForDisplay(listing.exactLocationEncrypted, "localisation exacte", listing.id) : null;
        return new ListingView(listing.id, ActivityTaxonomy.sectorLabel(listing.sectorCategory),
                ActivityTaxonomy.domainLabels(listing.sectorCategory, listing.activityDomains),
                listing.sector, listing.secondarySector, detailsVisible ? listing.foundedYear : null,
                listing.region, detailsVisible ? listing.description
                        : "Informations complémentaires accessibles après validation du profil et accord de confidentialité.",
                detailsVisible ? listing.revenue : null,
                detailsVisible ? listing.ebitda : null,
                detailsVisible ? listing.askingPrice : null, listing.views,
                matchPercent(listing, profile, diagnosis), company, exactLocation, listing.status,
                listing.transactionType == null ? ListingTransactionType.FULL_SALE : listing.transactionType,
                detailsVisible && diagnosis != null ? diagnosis.netIncome : null,
                detailsVisible && diagnosis != null ? diagnosis.totalAssets : null,
                detailsVisible && diagnosis != null ? diagnosis.totalDebts : null,
                detailsVisible && diagnosis != null ? diagnosis.fixedAssets : null,
                detailsVisible && diagnosis != null ? diagnosis.financialOutlook : null,
                detailsVisible && diagnosis != null && diagnosis.valuationValidatedAt != null,
                diagnosis == null ? null : diagnosis.countryCode,
                diagnosis == null ? "EUR" : diagnosis.currencyCode);
    }

    private static PublicListingView publicListingView(Listing listing, ListingFinancialDiagnosis diagnosis) {
        String countryCode = diagnosis == null ? null : diagnosis.countryCode;
        String currencyCode = diagnosis == null || diagnosis.currencyCode == null ? "EUR" : diagnosis.currencyCode;
        return new PublicListingView(listing.id, ActivityTaxonomy.sectorLabel(listing.sectorCategory),
                ActivityTaxonomy.domainLabels(listing.sectorCategory, listing.activityDomains),
                listing.sector, MarketCatalog.countryName(countryCode, Locale.FRENCH), experienceRange(listing.foundedYear),
                employeeRange(listing.approximateEmployeeCount),
                listing.publicRevenueRangeAllowed && listing.revenue != null
                        ? revenueRange(listing.revenue) : null,
                countryCode, currencyCode);
    }

    private static String experienceRange(Integer foundedYear) {
        if (foundedYear == null) {
            return "À préciser";
        }
        int years = Math.max(0, java.time.Year.now().getValue() - foundedYear);
        if (years < 5) {
            return "Moins de 5 ans";
        }
        if (years < 11) {
            return "5 à 10 ans";
        }
        if (years < 21) {
            return "11 à 20 ans";
        }
        return "Plus de 20 ans";
    }

    private static String employeeRange(Integer employeeCount) {
        if (employeeCount == null) {
            return "À préciser";
        }
        if (employeeCount <= 10) {
            return "1 à 10";
        }
        if (employeeCount <= 50) {
            return "11 à 50";
        }
        if (employeeCount <= 200) {
            return "51 à 200";
        }
        return "Plus de 200";
    }

    private static String revenueRange(BigDecimal revenue) {
        if (revenue == null) {
            return null;
        }
        if (revenue.compareTo(BigDecimal.valueOf(100_000)) < 0) {
            return "Moins de 100 000";
        }
        if (revenue.compareTo(BigDecimal.valueOf(500_000)) < 0) {
            return "100 000 à 499 999";
        }
        if (revenue.compareTo(BigDecimal.valueOf(2_000_000)) < 0) {
            return "500 000 à 1 999 999";
        }
        if (revenue.compareTo(BigDecimal.valueOf(10_000_000)) < 0) {
            return "2 000 000 à 9 999 999";
        }
        return "10 000 000 et plus";
    }

    private static int matchPercent(Listing listing, BuyerProfile profile, ListingFinancialDiagnosis diagnosis) {
        LOGGER.info("Calcul du pourcentage de correspondance pour l'annonce avec l'ID : " + (listing == null ? "null" : listing.id) + " et le profil acheteur avec l'ID : " + (profile == null ? "null" : profile.id));
        if (profile == null) {
            return 0;
        }
        int possible = 0;
        int score = 0;
        if (profile.sectors != null && !profile.sectors.isBlank()) {
            possible++;
            if (Arrays.stream(profile.sectors.split(","))
                    .map(String::trim).anyMatch(s -> s.equalsIgnoreCase(listing.sector)
                            || Arrays.stream(Objects.toString(listing.activityDomains, "").split(","))
                            .map(String::trim).anyMatch(domain -> domain.equalsIgnoreCase(s)))) {
                score++;
            }
        }
        if (profile.preferredRegion != null && !profile.preferredRegion.isBlank()) {
            possible++;
            if (profile.preferredRegion.equalsIgnoreCase(listing.region)) {
                score++;
            }
        }
        if ((profile.minInvestment != null || profile.maxInvestment != null) && listing.askingPrice != null
                && Objects.equals(profile.investmentCurrencyCode,
                diagnosis == null ? "EUR" : diagnosis.currencyCode)) {
            possible++;
            if ((profile.maxInvestment == null || listing.askingPrice.compareTo(profile.maxInvestment) <= 0)
                    && (profile.minInvestment == null || listing.askingPrice.compareTo(profile.minInvestment) >= 0)) {
                score++;
            }
        }
        return possible == 0 ? 0 : (int) Math.round(score * 100.0 / possible);
    }

    private static boolean matchesFilters(Listing listing, ListingFinancialDiagnosis diagnosis,
                                          ListingSearchFilters filters) {
        if (filters == null) {
            return true;
        }
        boolean sectorMatches = blank(filters.sector())
                || matchesTextFilter(listing.sector, filters.sector())
                || matchesTextFilter(listing.secondarySector, filters.sector());
        return sectorMatches && matchesRemainingFilters(listing, diagnosis, filters);
    }

    private static void validateSearchFilters(ListingSearchFilters filters) {
        if (filters == null) {
            return;
        }

        validateNonNegative(filters.minimumPrice(), "Prix minimum");
        validateNonNegative(filters.maximumPrice(), "Prix maximum");
        validateNonNegative(filters.minimumRevenue(), "Chiffre d'affaires minimum");
        validateNonNegative(filters.maximumRevenue(), "Chiffre d'affaires maximum");
        if (!blank(filters.countryCode()) && !MarketCatalog.isCountryCode(filters.countryCode())) {
            throw new IllegalArgumentException("Choisissez un pays ISO 3166-1 valide.");
        }
        if (!blank(filters.currencyCode()) && !MarketCatalog.isCurrencyCode(filters.currencyCode())) {
            throw new IllegalArgumentException("Choisissez une devise ISO 4217 valide.");
        }
        if (hasFinancialFilters(filters) && blank(filters.currencyCode())) {
            throw new IllegalArgumentException("Choisissez une devise pour appliquer les filtres financiers.");
        }
        if (filters.minimumPrice() != null && filters.maximumPrice() != null
                && filters.minimumPrice().compareTo(filters.maximumPrice()) > 0) {
            throw new IllegalArgumentException("Le prix minimum ne peut dépasser le prix maximum.");
        }
        if (filters.minimumRevenue() != null && filters.maximumRevenue() != null
                && filters.minimumRevenue().compareTo(filters.maximumRevenue()) > 0) {
            throw new IllegalArgumentException("Le chiffre d'affaires minimum ne peut dépasser le maximum.");
        }
        if (filters.minimumProfitMargin() != null
                && (filters.minimumProfitMargin().compareTo(BigDecimal.valueOf(-1000)) < 0
                || filters.minimumProfitMargin().compareTo(BigDecimal.valueOf(1000)) > 0)) {
            throw new IllegalArgumentException("La marge minimale doit être comprise entre -1 000 % et 1 000 %.");
        }
        if (filters.foundedAfter() != null && filters.foundedBefore() != null
                && filters.foundedAfter() > filters.foundedBefore()) {
            throw new IllegalArgumentException("L'année de création minimale ne peut dépasser l'année maximale.");
        }
        if (filters.minimumEmployees() != null && filters.maximumEmployees() != null
                && filters.minimumEmployees() > filters.maximumEmployees()) {
            throw new IllegalArgumentException("L'effectif minimum ne peut dépasser l'effectif maximum.");
        }
    }

    private static boolean hasFinancialFilters(ListingSearchFilters filters) {
        return filters != null && (filters.minimumPrice() != null || filters.maximumPrice() != null
                || filters.minimumRevenue() != null || filters.maximumRevenue() != null
                || filters.minimumProfitMargin() != null);
    }

    private static boolean matchesRemainingFilters(Listing listing, ListingFinancialDiagnosis diagnosis,
                                                   ListingSearchFilters filters) {
        boolean domainMatches = blank(filters.domain())
                || Arrays.stream(Objects.toString(listing.activityDomains, "").split(","))
                .anyMatch(domain -> containsIgnoreCase(domain, filters.domain()));
        boolean locationMatches = blank(filters.region()) || containsIgnoreCase(listing.region, filters.region());
        boolean transactionMatches = filters.transactionType() == null
                || (listing.transactionType == null ? ListingTransactionType.FULL_SALE : listing.transactionType)
                == filters.transactionType();
        String listingCurrency = diagnosis == null || diagnosis.currencyCode == null ? "EUR" : diagnosis.currencyCode;
        boolean countryMatches = blank(filters.countryCode())
                || diagnosis != null && Objects.equals(diagnosis.countryCode, filters.countryCode());
        boolean currencyMatches = blank(filters.currencyCode())
                || Objects.equals(listingCurrency, filters.currencyCode());
        return domainMatches && locationMatches && transactionMatches && countryMatches && currencyMatches
                && matchesRange(listing.askingPrice, filters.minimumPrice(), filters.maximumPrice())
                && matchesRange(listing.revenue, filters.minimumRevenue(), filters.maximumRevenue())
                && (filters.minimumProfitMargin() == null || listing.profitMargin != null
                && listing.profitMargin.compareTo(filters.minimumProfitMargin()) >= 0)
                && (filters.foundedAfter() == null || listing.foundedYear != null
                && listing.foundedYear >= filters.foundedAfter())
                && (filters.foundedBefore() == null || listing.foundedYear != null
                && listing.foundedYear <= filters.foundedBefore())
                && (filters.minimumEmployees() == null || listing.approximateEmployeeCount != null
                && listing.approximateEmployeeCount >= filters.minimumEmployees())
                && (filters.maximumEmployees() == null || listing.approximateEmployeeCount != null
                && listing.approximateEmployeeCount <= filters.maximumEmployees());
    }

    private static boolean matchesTextFilter(String value, String filter) {
        return !blank(filter) && containsIgnoreCase(value, filter);
    }

    private static boolean containsIgnoreCase(String value, String filter) {
        return value != null && value.toLowerCase(Locale.ROOT)
                .contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean matchesRange(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return (minimum == null || value != null && value.compareTo(minimum) >= 0)
                && (maximum == null || value != null && value.compareTo(maximum) <= 0);
    }

    private static boolean containsSensitiveValue(String teaser, String sensitiveValue) {
        LOGGER.info("Vérification de la présence de la valeur sensible : " + sensitiveValue + " dans le teaser : " + teaser);
        if (sensitiveValue == null || sensitiveValue.trim().length() < 3) {
            return false;
        }
        String normalizedTeaser = normalizeForComparison(teaser);
        String normalizedSensitive = normalizeForComparison(sensitiveValue);
        return normalizedSensitive.length() >= 3 && normalizedTeaser.contains(normalizedSensitive);
    }

    private static String normalizeForComparison(String value) {
        LOGGER.info("Normalisation de la valeur pour la comparaison : " + value);
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static DealView dealView(jakarta.persistence.EntityManager em, DealRequest request, UserView viewer) {
        LOGGER.info("Création de la vue pour la demande avec l'ID : " + request.id + " et l'annonce avec l'ID : " + request.listingId + " pour l'utilisateur avec l'ID : " + (viewer == null ? "null" : viewer.id()));
        Listing listing = requireListing(em, request.listingId);
        UserAccount buyer = em.find(UserAccount.class, request.buyerId);
        UserAccount seller = em.find(UserAccount.class, listing.sellerId);
        boolean signed = request.isNdaSigned();
        boolean buyerVerified = !signed || !Role.BUYER.name().equals(viewer.role())
                || buyerKycValidated(em, viewer.id());
        boolean detailsVisible = signed && buyerVerified;
        String sellerDisplayName = !detailsVisible && viewer.role().equals(Role.BUYER.name())
                ? "Cédant confidentiel" : seller.fullName;
        String companyName = null;
        String exactLocation = null;
        ListingFinancialDiagnosis diagnosis = findFinancialDiagnosis(em, listing.id);
        boolean encryptedIdentityUnavailable = false;
        if (detailsVisible) {
            try {
                companyName = CryptoService.decrypt(listing.companyNameEncrypted);
                exactLocation = CryptoService.decrypt(listing.exactLocationEncrypted);
            } catch (CryptoService.UnreadableCiphertextException exception) {
                encryptedIdentityUnavailable = true;
                logUnavailableEncryptedData("identité de l'annonce", listing.id);
            }
        }
        return new DealView(request.id, listing.id, listing.sector, buyer.fullName, sellerDisplayName,
                request.status, signed, encryptedIdentityUnavailable ? UNAVAILABLE_ENCRYPTED_DATA : companyName,
                encryptedIdentityUnavailable ? "" : exactLocation,
                detailsVisible, request.buyerSignedAt != null, request.sellerSignedAt != null, request.ndaVersion,
                encryptedIdentityUnavailable,
                detailsVisible ? listing.revenue : null, detailsVisible ? listing.ebitda : null,
                detailsVisible ? listing.askingPrice : null,
                diagnosis == null || diagnosis.currencyCode == null ? "EUR" : diagnosis.currencyCode);
    }

    private static String decryptForDisplay(String ciphertext, String dataType, Long id) {
        LOGGER.info("Déchiffrement des données pour le type : " + dataType + " avec l'ID : " + id);
        try {
            return CryptoService.decrypt(ciphertext);
        } catch (CryptoService.UnreadableCiphertextException exception) {
            logUnavailableEncryptedData(dataType, id);
            return UNAVAILABLE_ENCRYPTED_DATA;
        }
    }

    private static void logUnavailableEncryptedData(String dataType, Long id) {
        LOGGER.warning("Impossible de déchiffrer " + dataType + " #" + id
                + " avec la clé AES configurée; l'ancienne clé ou une sauvegarde est nécessaire.");
    }

    private static BuyerProfile findProfile(jakarta.persistence.EntityManager em, Long userId) {
        List<BuyerProfile> profiles = em.createQuery(
                        "select p from BuyerProfile p where p.userId = :id", BuyerProfile.class)
                .setParameter("id", userId).setMaxResults(1).getResultList();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private static Long findBrokerId(jakarta.persistence.EntityManager em, String brokerEmail) {
        String email = trimToNull(brokerEmail);
        if (email == null) {
            return null;
        }
        List<UserAccount> brokers = em.createQuery(
                        "select u from UserAccount u where u.email = :email and u.role = :role", UserAccount.class)
                .setParameter("email", email.toLowerCase(Locale.ROOT))
                .setParameter("role", Role.BROKER).setMaxResults(1).getResultList();
        return brokers.isEmpty() ? null : brokers.get(0).id;
    }

    private static Listing requireListing(jakarta.persistence.EntityManager em, Long id) {
        Listing listing = id == null ? null : em.find(Listing.class, id);
        if (listing == null) {
            throw new IllegalArgumentException("Annonce introuvable.");
        }
        return listing;
    }

    private static DealRequest requireRequest(jakarta.persistence.EntityManager em, Long id) {
        DealRequest request = id == null ? null : em.find(DealRequest.class, id);
        if (request == null) {
            throw new IllegalArgumentException("Demande de mise en relation introuvable.");
        }
        return request;
    }

    private static SecureDocument requireDealDocument(jakarta.persistence.EntityManager em, Long documentId) {
        SecureDocument document = documentId == null ? null : em.find(SecureDocument.class, documentId);
        if (document == null || document.requestId == null || document.documentPurpose != null) {
            throw new IllegalArgumentException("Document de data room introuvable.");
        }
        return document;
    }

    private static void requireDataRoomReviewer(UserView reviewer, Listing listing) {
        if (reviewer == null
                || !(Role.SUPER.name().equals(reviewer.role())
                || Role.BROKER.name().equals(reviewer.role())
                && Objects.equals(reviewer.id(), listing.brokerId))) {
            throw new SecurityException("La validation est réservée à l’administrateur ou au courtier mandaté.");
        }
    }

    private static boolean buyerKycValidated(jakarta.persistence.EntityManager em, Long buyerId) {
        BuyerKyc kyc = findBuyerKyc(em, buyerId);
        return kyc != null && kyc.status == BuyerKycStatus.VALIDATED;
    }

    private static boolean hasPartnerDirectoryAccess(jakarta.persistence.EntityManager em, UserView user) {
        return user != null && (Role.SUPER.name().equals(user.role()) || Role.BROKER.name().equals(user.role())
                || hasValidatedProfile(em, user));
    }

    private static boolean hasValidatedProfile(jakarta.persistence.EntityManager em, UserView user) {
        if (Role.BUYER.name().equals(user.role())) {
            return buyerKycValidated(em, user.id());
        }
        if (Role.SELLER.name().equals(user.role())) {
            SellerVerification verification = findSellerVerification(em, user.id());
            return verification != null
                    && verification.status == SellerVerificationStatus.ACCOUNT_VALIDATED;
        }
        return false;
    }

    private static void requireListingManager(UserView user, Listing listing) {
        if (user == null || !(user.role().equals(Role.BROKER.name()) && Objects.equals(user.id(), listing.brokerId))
                && !(user.role().equals(Role.SELLER.name()) && Objects.equals(user.id(), listing.sellerId))) {
            throw new SecurityException("Accès réservé au cédant ou au courtier mandaté.");
        }
    }

    private static void requireOperationManager(UserView user, Listing listing) {
        if (user != null && Role.SUPER.name().equals(user.role())) {
            return;
        }
        requireListingManager(user, listing);
    }

    private static void requireListingReviewer(UserView user, Listing listing) {
        if (user != null && Role.SUPER.name().equals(user.role())) {
            return;
        }
        requireListingManager(user, listing);
    }

    private static void requireListingPublicationManager(UserView user, Listing listing) {
        if (user != null && Role.SUPER.name().equals(user.role())) {
            return;
        }
        if (user == null || !Role.BROKER.name().equals(user.role())
                || !Objects.equals(user.id(), listing.brokerId)) {
            throw new SecurityException("La gestion de publication est réservée au courtier mandaté ou à l’administrateur.");
        }
    }

    private static void requireListingSeller(UserView seller, Listing listing) {
        if (seller == null || !Role.SELLER.name().equals(seller.role())
                || !Objects.equals(seller.id(), listing.sellerId)) {
            throw new SecurityException("Seul le cédant propriétaire peut modifier ce teaser.");
        }
    }

    private static boolean isPublished(String status) {
        return "PUBLISHED".equals(status) || "BROKER_ACCEPTED".equals(status);
    }

    private static boolean managesListing(UserView user, Listing listing) {
        return user != null && (user.role().equals(Role.BROKER.name()) && Objects.equals(user.id(), listing.brokerId)
                || user.role().equals(Role.SELLER.name()) && Objects.equals(user.id(), listing.sellerId));
    }

    private static void requireDealParticipant(UserView user, DealRequest request, Listing listing) {
        boolean buyer = user != null && Role.BUYER.name().equals(user.role())
                && Objects.equals(user.id(), request.buyerId);
        boolean seller = user != null && Role.SELLER.name().equals(user.role())
                && Objects.equals(user.id(), listing.sellerId);
        if (!buyer && !seller) {
            throw new SecurityException("La messagerie et les rendez-vous sont réservés aux parties du dossier.");
        }
    }

    private static Long otherDealParticipant(UserView user, DealRequest request, Listing listing) {
        requireDealParticipant(user, request, listing);
        return Role.BUYER.name().equals(user.role()) ? listing.sellerId : request.buyerId;
    }

    private static void requireNdaAccess(UserView user, DealRequest request, Listing listing) {
        if (!request.isNdaSigned()) {
            throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
        }
        boolean buyer = user.role().equals(Role.BUYER.name()) && request.buyerId.equals(user.id());
        if (!buyer && !managesListing(user, listing)) {
            throw new SecurityException("Accès réservé aux participants autorisés à ce dossier.");
        }
    }

    private static Set<Long> favoriteIds(jakarta.persistence.EntityManager em, Long buyerId) {
        return new HashSet<>(em.createQuery(
                        "select f.listingId from BuyerFavorite f where f.buyerId = :buyerId", Long.class)
                .setParameter("buyerId", buyerId).getResultList());
    }

    private void notifyMatchedBuyers(Listing listing) {
        List<Long> recipientIds = Jpa.transaction(em -> {
            ListingFinancialDiagnosis diagnosis = findFinancialDiagnosis(em, listing.id);
            return em.createQuery("select p from BuyerProfile p", BuyerProfile.class).getResultList().stream()
                    .filter(profile -> matchPercent(listing, profile, diagnosis) > 0)
                    .map(profile -> profile.userId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        });
        for (Long recipientId : recipientIds) {
            notifyUser(recipientId, "Nouvelle entreprise correspondant à vos critères",
                    "Une nouvelle opportunité anonymisée pourrait correspondre à votre recherche.");
        }
    }

    private void notifyUser(Long recipientId, String title, String body) {
        if (recipientId == null) {
            return;
        }
        boolean emailConfigured = NotificationEmailSender.isConfigured();
        NotificationDelivery delivery = Jpa.transaction(em -> {
            UserAccount recipient = em.find(UserAccount.class, recipientId);
            if (recipient == null) {
                LOGGER.warning("Impossible de créer une notification : compte destinataire introuvable.");
                return null;
            }
            UserNotification notification = new UserNotification();
            notification.recipientId = recipientId;
            notification.title = title;
            notification.body = body;
            notification.emailDeliveryStatus = emailConfigured ? "PENDING" : "NOT_CONFIGURED";
            notification.createdAt = Instant.now();
            em.persist(notification);
            return new NotificationDelivery(notification.id, recipient.email);
        });
        if (delivery == null || !emailConfigured) {
            return;
        }

        String status = "SENT";
        try {
            NotificationEmailSender.send(delivery.email(), title, body);
        } catch (IllegalStateException exception) {
            status = "FAILED";
            LOGGER.log(Level.WARNING, "La notification interne a été enregistrée, mais le courriel n'a pas été envoyé.",
                    exception);
        }
        String deliveryStatus = status;
        Jpa.transaction(em -> {
            UserNotification notification = em.find(UserNotification.class, delivery.notificationId());
            if (notification != null) {
                notification.emailDeliveryStatus = deliveryStatus;
            }
            return null;
        });
    }

    private static void requireDealDocumentAccess(UserView user, DealRequest request, Listing listing) {
        if (user != null && Role.SUPER.name().equals(user.role())) {
            if (!request.isNdaSigned()) {
                throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
            }
            return;
        }
        requireNdaAccess(user, request, listing);
    }

    private static void requireRole(UserView user, Role role) {
        if (user == null || !role.name().equals(user.role())) {
            throw new SecurityException("Cette action n'est pas autorisée pour votre profil.");
        }
    }

    private static void requireVerificationReviewer(UserView user) {
        if (user == null || !(Role.BROKER.name().equals(user.role()) || Role.SUPER.name().equals(user.role()))) {
            throw new SecurityException("Cette action est réservée aux courtiers et aux administrateurs.");
        }
    }

    private static SellerVerification findSellerVerification(jakarta.persistence.EntityManager em, Long sellerId) {
        List<SellerVerification> results = em.createQuery(
                        "select v from SellerVerification v where v.userId = :id", SellerVerification.class)
                .setParameter("id", sellerId).setMaxResults(1).getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    private static BuyerKyc findBuyerKyc(jakarta.persistence.EntityManager em, Long buyerId) {
        List<BuyerKyc> results = em.createQuery(
                        "select k from BuyerKyc k where k.userId = :id", BuyerKyc.class)
                .setParameter("id", buyerId).setMaxResults(1).getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    private static long buyerKycDocumentCount(jakarta.persistence.EntityManager em, Long buyerId,
                                              BuyerKycDocumentType type) {
        String query = "select count(d) from SecureDocument d where d.documentPurpose = 'BUYER_KYC' "
                + "and d.uploadedBy = :id and d.verificationType = '" + type.name() + "'";
        return em.createQuery(query, Long.class).setParameter("id", buyerId).getSingleResult();
    }

    private static void validateRequiredBuyerKycDocuments(jakarta.persistence.EntityManager em, Long buyerId,
                                                           BuyerType buyerType) {
        List<BuyerKycDocumentType> requiredTypes = switch (buyerType) {
            case INDIVIDUAL -> List.of(BuyerKycDocumentType.IDENTITY_DOCUMENT);
            case COMPANY -> {
                yield List.of(
                        BuyerKycDocumentType.COMPANY_REGISTRATION,
                        BuyerKycDocumentType.COMPANY_STATUTES,
                        BuyerKycDocumentType.LEGAL_REPRESENTATIVE_ID,
                        BuyerKycDocumentType.REPRESENTATION_AUTHORITY);
            }
        };
        for (BuyerKycDocumentType type : requiredTypes) {
            if (buyerKycDocumentCount(em, buyerId, type) == 0L) {
                throw new IllegalArgumentException("La pièce requise doit être déposée : " + type.label() + ".");
            }
        }
    }

    private static BuyerKycView buyerKycView(BuyerProfile profile, UserAccount account, BuyerKyc kyc,
                                             List<DocumentView> documents) {
        return new BuyerKycView(profile.userId, account.fullName, account.email, account.organization,
                profile.buyerType, profile.phone, profile.nationality, profile.residence, profile.profession,
                profile.indicativeFinancialCapacity, profile.financialCapacityCurrencyCode,
                profile.legalForm, profile.country, profile.countryCode,
                profile.registrationNumber, profile.taxIdentifier, profile.headOffice,
                profile.representativeFunction,
                kyc == null || kyc.status == null ? BuyerKycStatus.DOCUMENTS_PENDING : kyc.status,
                kyc == null ? null : kyc.feedback,
                kyc != null && kyc.emailVerified,
                kyc != null && kyc.phoneVerified,
                kyc != null && kyc.sourceOfFundsRequired,
                kyc != null && kyc.beneficialOwnersRequired,
                kyc == null ? null : kyc.updatedAt, documents);
    }

    private static AdminUserView adminUserView(UserAccount account, BuyerProfile profile, BuyerKyc kyc) {
        BuyerType buyerType = profile == null ? null : profile.buyerType;
        BuyerKycStatus status = buyerType == null ? null
                : kyc == null || kyc.status == null ? BuyerKycStatus.DOCUMENTS_PENDING : kyc.status;
        return new AdminUserView(account.id, account.email, account.fullName, account.organization,
                account.role.name(), account.createdAt, account.passwordChangeRequired,
                buyerType, status, kyc == null ? null : kyc.updatedAt);
    }

    private static ArticleView articleView(Article article) {
        return new ArticleView(article.id, article.title, article.category, article.summary,
                article.content, article.status, article.createdAt, article.updatedAt, article.publishedAt);
    }

    private static PartnerExpertView partnerExpertView(PartnerExpert partner) {
        return new PartnerExpertView(partner.id, partner.name, partner.category.name(),
                partner.category.label(), partner.country, partner.location, partner.summary,
                partner.contactEmail, partner.phone, partner.website, partner.active);
    }

    private static ContactMessageView contactMessageView(ContactMessage message) {
        return new ContactMessageView(message.id, CryptoService.decrypt(message.senderNameEncrypted),
                CryptoService.decrypt(message.senderEmailEncrypted),
                message.senderPhoneEncrypted == null ? null : CryptoService.decrypt(message.senderPhoneEncrypted),
                message.type, CryptoService.decrypt(message.subjectEncrypted),
                CryptoService.decrypt(message.messageTextEncrypted),
                message.status, message.adminNote, message.createdAt, message.updatedAt);
    }

    private static void requireSellerVerificationDocumentAccess(UserView user, SecureDocument document) {
        if (user != null && Role.SELLER.name().equals(user.role())
                && Objects.equals(user.id(), document.uploadedBy)) {
            return;
        }
        if (user != null && (Role.BROKER.name().equals(user.role()) || Role.SUPER.name().equals(user.role()))) {
            return;
        }
        throw new SecurityException("Accès réservé au cédant propriétaire, au courtier ou à l’administrateur.");
    }

    private static void requireBuyerKycDocumentAccess(UserView user, SecureDocument document) {
        if (user != null && Role.BUYER.name().equals(user.role())
                && Objects.equals(user.id(), document.uploadedBy)) {
            return;
        }
        if (user != null && (Role.BROKER.name().equals(user.role()) || Role.SUPER.name().equals(user.role()))) {
            return;
        }
        throw new SecurityException("Accès réservé à l’acquéreur propriétaire, au courtier ou à l’administrateur.");
    }

    private static UserView view(UserAccount user) {
        return new UserView(user.id, user.email, user.fullName, user.organization,
                user.role == null ? "" : user.role.name(), user.passwordChangeRequired);
    }

    private static DocumentView documentView(SecureDocument document) {
        String name = document.originalName;
        boolean unavailable = false;
        try {
            name = CryptoService.decrypt(document.originalName);
        } catch (CryptoService.UnreadableCiphertextException exception) {
            unavailable = true;
            logUnavailableEncryptedData("nom de document", document.id);
        }
        return new DocumentView(document.id, document.listingId, document.requestId,
                unavailable ? UNAVAILABLE_ENCRYPTED_DATA : name, document.contentType,
                document.sizeBytes, document.uploadedAt, unavailable, document.verificationType,
                document.sellerAuthorized, document.alpValidated, document.accessDurationDays,
                document.accessExpiresAt,
                document.sellerAuthorized && document.alpValidated
                        && document.accessExpiresAt != null && document.accessExpiresAt.isAfter(Instant.now()));
    }

    private static String normalizeEmail(String email) {
        // L'adresse est nettoyée avant validation : un copier-coller avec espaces doit être accepté.
        String candidate = email == null ? null : email.trim();
        if (candidate == null || !candidate.matches("(?i)^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$")) {
            throw new IllegalArgumentException("Saisissez une adresse courriel valide.");
        }
        return candidate.toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins 12 caractères et au plus 72 octets UTF-8.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Path storageDirectory() {
        return Path.of(AppConfiguration.get("PRIVATE_STORAGE_DIR", "./private-storage"))
                .toAbsolutePath().normalize();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Échec du nettoyage du fichier temporaire sécurisé.", exception);
        }
    }

    private static String normalizeContentType(String contentType, String extension) {
        if (contentType != null && Set.of("application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "image/png", "image/jpeg").contains(contentType.toLowerCase(Locale.ROOT))) {
            return contentType.toLowerCase(Locale.ROOT);
        }
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }
}
