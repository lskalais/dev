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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

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
    private record AuthorizedDocument(String name, String contentType, String storageKey) {
    }

    private static void validateLength(String value, int maximum, String label) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(label + " dépasse " + maximum + " caractères.");
        }
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
        return createUser(email, password, name, organization, role, false);
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
        return Jpa.transaction(em -> em.createQuery(
                        "select u from UserAccount u order by u.createdAt desc, u.id desc", UserAccount.class)
                .getResultList().stream()
                .map(account -> new AdminUserView(account.id, account.email, account.fullName,
                        account.organization, account.role.name(), account.createdAt,
                        account.passwordChangeRequired))
                .toList());
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
        createUser(email, password, name, organization, role, true);
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
                                boolean passwordChangeRequired) {
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
                em.persist(profile);
            }
            em.flush();
            return view(user);
        });
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
        LOGGER.info("Sauvegarde des critères d'achat pour l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        requireRole(user, Role.BUYER);
        validateLength(sectors, 500, "Secteurs recherchés");
        validateLength(region, 120, "Région souhaitée");
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
            return null;
        });
    }

    public ListingSaveResult saveListing(UserView seller, Long listingId, String brokerEmail,
                                         String sector, String secondarySector, Integer foundedYear,
                                         String description, String region, String customerSummary,
                                         String advantages, String saleReason, BigDecimal revenue,
                                         BigDecimal ebitda, BigDecimal askingPrice, BigDecimal profitMargin,
                                         String saleConditions, String supportPeriod, String companyName,
                                         String exactLocation) {
        LOGGER.info("Sauvegarde d'une annonce pour l'utilisateur avec l'ID : " + (seller == null ? "null" : seller.id()));
        requireRole(seller, Role.SELLER);
        if (sector == null || sector.isBlank() || description == null || description.isBlank()
                || region == null || region.isBlank()) {
            throw new IllegalArgumentException("Le secteur, la description et la région sont obligatoires.");
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
        validateLength(companyName, 500, "Nom réel");
        validateLength(exactLocation, 500, "Localisation exacte");
        if (foundedYear != null && (foundedYear < 1800 || foundedYear > java.time.Year.now().getValue() + 5)) {
            throw new IllegalArgumentException("Année de création invalide.");
        }
        validateNonNegative(revenue, "Chiffre d'affaires");
        validateNonNegative(askingPrice, "Prix demandé");
        validateDecimal(revenue, 16, 2, "Chiffre d'affaires");
        validateDecimal(ebitda, 16, 2, "EBE / EBITDA");
        validateDecimal(askingPrice, 16, 2, "Prix demandé");
        validateDecimal(profitMargin, 7, 2, "Marge bénéficiaire");
        String teaserText = String.join(" ",
                sector, Objects.toString(secondarySector, ""), description, region,
                Objects.toString(customerSummary, ""), Objects.toString(advantages, ""),
                Objects.toString(saleReason, ""), Objects.toString(saleConditions, ""));
        if (containsSensitiveValue(teaserText, companyName) || containsSensitiveValue(teaserText, exactLocation)) {
            throw new IllegalArgumentException(
                    "Le teaser semble contenir le nom réel ou l'adresse exacte. Retirez ces éléments avant publication.");
        }

        return Jpa.transaction(em -> {
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
            listing.sector = sector.trim();
            listing.secondarySector = trimToNull(secondarySector);
            listing.foundedYear = foundedYear;
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
            return new ListingSaveResult(listing.id, brokerId != null);
        });
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
            return new ListingEditView(listing.id, broker == null ? null : broker.email,
                    listing.sector, listing.secondarySector, listing.foundedYear, listing.description,
                    listing.region, listing.customerSummary, listing.advantages, listing.saleReason,
                    listing.revenue, listing.ebitda, listing.askingPrice, listing.profitMargin,
                    listing.saleConditions, listing.supportPeriod,
                    decryptForDisplay(listing.companyNameEncrypted, "nom d'entreprise", listing.id),
                    decryptForDisplay(listing.exactLocationEncrypted, "localisation exacte", listing.id));
        });
    }

    public List<ListingView> listingsFor(UserView user) {
        return listingsFor(user, null, null, null);
    }

    public List<ListingView> listingsFor(UserView user, String sectorFilter,
                                         String regionFilter, BigDecimal maximumPriceFilter) {
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
            } else {
                listings = em.createQuery("select l from Listing l where l.brokerId = :id order by l.createdAt desc",
                                Listing.class).setParameter("id", user.id()).getResultList();
            }
            BuyerProfile profile = user.role().equals(Role.BUYER.name()) ? findProfile(em, user.id()) : null;
            List<ListingView> result = new ArrayList<>();
            for (Listing listing : listings) {
                if (user.role().equals(Role.BUYER.name())
                        && !matchesFilters(listing, sectorFilter, regionFilter, maximumPriceFilter)) {
                    continue;
                }
                result.add(listingView(listing, profile, null));
            }
            if (user.role().equals(Role.BUYER.name())) {
                result.sort(Comparator.comparingInt(ListingView::matchPercent).reversed());
            }
            return result;
        });
    }

    public List<ListingView> publicListings() {
        return Jpa.transaction(em -> em.createQuery(
                        "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                        Listing.class).getResultList().stream()
                .map(listing -> listingView(listing, null, null))
                .toList());
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
            } else {
                requests = em.createQuery(
                                "select d from DealRequest d, Listing l where d.listingId = l.id and l.brokerId = :id order by d.createdAt desc",
                                DealRequest.class).setParameter("id", user.id()).getResultList();
            }
            return requests.stream().map(request -> dealView(em, request, user)).toList();
        });
    }

    public void requestIntroduction(UserView buyer, Long listingId) {
        LOGGER.info("Demande de mise en relation pour l'annonce avec l'ID : " + listingId + " par l'acheteur avec l'ID : " + (buyer == null ? "null" : buyer.id()));
        requireRole(buyer, Role.BUYER);
        Jpa.transaction(em -> {
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
            return null;
        });
    }

    public void decideIntroduction(UserView user, Long requestId, boolean accept) {
        LOGGER.info("Décision sur la demande de mise en relation avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()) + " acceptée : " + accept);
        Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireListingManager(user, listing);
            if (!"PENDING".equals(request.status)) {
                throw new IllegalArgumentException("Cette demande a déjà été traitée.");
            }
            request.status = accept ? "NDA_PENDING" : "DECLINED";
            request.ndaVersion = accept ? "MVP-1.0" : null;
            return null;
        });
    }

    public void signNda(UserView user, Long requestId, String signatureName) {
        LOGGER.info("Signature de l'accord de confidentialité pour la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        if (signatureName == null || signatureName.isBlank() || signatureName.length() > 160) {
            throw new IllegalArgumentException("Saisissez votre nom complet comme signature.");
        }
        Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            boolean isBuyer = request.buyerId.equals(user.id()) && user.role().equals(Role.BUYER.name());
            boolean isManager = managesListing(user, listing);
            if ((!isBuyer && !isManager) || "DECLINED".equals(request.status) || "PENDING".equals(request.status)) {
                throw new IllegalArgumentException("Vous n'êtes pas autorisé à signer cet accord.");
            }
            if (isBuyer && request.buyerSignedAt == null) {
                request.buyerSignatureName = signatureName.trim();
                request.buyerSignedAt = Instant.now();
            } else if (isManager && request.sellerSignedAt == null) {
                request.sellerSignatureName = signatureName.trim();
                request.sellerSignedAt = Instant.now();
            }
            request.status = request.isNdaSigned() ? "NDA_SIGNED" : "NDA_PENDING";
            return null;
        });
    }

    public void sendMessage(UserView user, Long requestId, String body) {
        LOGGER.info("Envoi d'un message pour la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        if (body == null || body.isBlank() || body.length() > 8000) {
            throw new IllegalArgumentException("Le message doit contenir entre 1 et 8 000 caractères.");
        }
        String encryptedBody = CryptoService.encrypt(body.trim());
        Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireNdaAccess(user, request, listing);
            SecureMessage message = new SecureMessage();
            message.requestId = requestId;
            message.senderId = user.id();
            message.encryptedBody = encryptedBody;
            message.createdAt = Instant.now();
            em.persist(message);
            return null;
        });
    }

    public List<MessageView> messages(UserView user, Long requestId) {
        LOGGER.info("Récupération des messages pour la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        return Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireNdaAccess(user, request, listing);
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
        LOGGER.info("Stockage d'un document pour l'annonce avec l'ID : " + listingId + " et la demande avec l'ID : " + requestId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        if (bytes == null || bytes.length == 0 || bytes.length > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("Le fichier doit faire entre 1 octet et 20 Mo.");
        }
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
                Listing listing = listingId == null ? null : requireListing(em, listingId);
                DealRequest request = requestId == null ? null : requireRequest(em, requestId);
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
                SecureDocument document = new SecureDocument();
                document.listingId = listing.id;
                document.requestId = requestId;
                document.uploadedBy = user.id();
                document.originalName = CryptoService.encrypt(safeName);
                document.storageKey = key;
                document.contentType = normalizeContentType(contentType, extension);
                document.sizeBytes = bytes.length;
                document.uploadedAt = Instant.now();
                em.persist(document);
                em.flush();
                return documentView(document);
            });
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }
    }

    public DocumentDownload readDocument(UserView user, Long documentId) {
        LOGGER.info("Lecture du document avec l'ID : " + documentId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        AuthorizedDocument document = Jpa.transaction(em -> {
            SecureDocument found = em.find(SecureDocument.class, documentId);
            if (found == null) {
                throw new IllegalArgumentException("Document introuvable.");
            }
            Listing listing = requireListing(em, found.listingId);
            if (found.requestId != null) {
                DealRequest request = requireRequest(em, found.requestId);
                requireNdaAccess(user, request, listing);
            } else {
                requireListingManager(user, listing);
            }
            return new AuthorizedDocument(CryptoService.decrypt(found.originalName),
                    found.contentType, found.storageKey);
        });
        try {
            Path file = storageDirectory().resolve(document.storageKey() + ".bin").normalize();
            if (!file.getParent().equals(storageDirectory())) {
                throw new IllegalStateException("Chemin de document invalide.");
            }
            return new DocumentDownload(document.name(), document.contentType(),
                    CryptoService.decrypt(Files.readAllBytes(file)));
        } catch (IOException exception) {
            throw new IllegalStateException("Le document sécurisé est indisponible.", exception);
        }
    }

    public List<DocumentView> documentsFor(UserView user, Long listingId) {
        LOGGER.info("Récupération des documents pour l'annonce avec l'ID : " + listingId + " par l'utilisateur avec l'ID : " + (user == null ? "null" : user.id()));
        return Jpa.transaction(em -> {
            Listing listing = requireListing(em, listingId);
            requireListingManager(user, listing);
            return em.createQuery(
                            "select d from SecureDocument d where d.listingId = :id and d.requestId is null order by d.uploadedAt desc",
                            SecureDocument.class).setParameter("id", listingId).getResultList()
                    .stream().map(MarketplaceService::documentView).toList();
        });
    }

    public List<DocumentView> dealDocuments(UserView user, Long requestId) {
        return Jpa.transaction(em -> {
            DealRequest request = requireRequest(em, requestId);
            Listing listing = requireListing(em, request.listingId);
            requireNdaAccess(user, request, listing);
            return em.createQuery(
                            "select d from SecureDocument d where d.requestId = :id order by d.uploadedAt desc",
                            SecureDocument.class).setParameter("id", requestId).getResultList()
                    .stream().map(MarketplaceService::documentView).toList();
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

    private static ListingView listingView(Listing listing, BuyerProfile profile, DealRequest request) {
        LOGGER.info("Création de la vue pour l'annonce avec l'ID : " + (listing == null ? "null" : listing.id) + " pour le profil acheteur avec l'ID : " + (profile == null ? "null" : profile.id) + " et la demande avec l'ID : " + (request == null ? "null" : request.id));
        boolean ndaSigned = request != null && request.isNdaSigned();
        String company = ndaSigned ? CryptoService.decrypt(listing.companyNameEncrypted) : null;
        String exactLocation = ndaSigned ? CryptoService.decrypt(listing.exactLocationEncrypted) : null;
        return new ListingView(listing.id, listing.sector, listing.region, listing.description,
                listing.revenue, listing.ebitda, listing.askingPrice, listing.views,
                matchPercent(listing, profile), company, exactLocation, listing.status);
    }

    private static int matchPercent(Listing listing, BuyerProfile profile) {
        LOGGER.info("Calcul du pourcentage de correspondance pour l'annonce avec l'ID : " + (listing == null ? "null" : listing.id) + " et le profil acheteur avec l'ID : " + (profile == null ? "null" : profile.id));
        if (profile == null) {
            return 0;
        }
        int possible = 0;
        int score = 0;
        if (profile.sectors != null && !profile.sectors.isBlank()) {
            possible++;
            if (Arrays.stream(profile.sectors.split(","))
                    .map(String::trim).anyMatch(s -> s.equalsIgnoreCase(listing.sector))) {
                score++;
            }
        }
        if (profile.preferredRegion != null && !profile.preferredRegion.isBlank()) {
            possible++;
            if (profile.preferredRegion.equalsIgnoreCase(listing.region)) {
                score++;
            }
        }
        if (profile.maxInvestment != null && listing.askingPrice != null) {
            possible++;
            if (listing.askingPrice.compareTo(profile.maxInvestment) <= 0
                    && (profile.minInvestment == null
                    || listing.askingPrice.compareTo(profile.minInvestment) >= 0)) {
                score++;
            }
        }
        return possible == 0 ? 0 : (int) Math.round(score * 100.0 / possible);
    }

    private static boolean matchesFilters(Listing listing, String sector, String region, BigDecimal maximumPrice) {
        LOGGER.info("Vérification des filtres pour l'annonce avec l'ID : " + (listing == null ? "null" : listing.id) + ", secteur : " + sector + ", région : " + region + ", prix maximum : " + maximumPrice);
        return (sector == null || sector.isBlank()
                || listing.sector.toLowerCase(Locale.ROOT).contains(sector.trim().toLowerCase(Locale.ROOT))
                || listing.secondarySector != null
                && listing.secondarySector.toLowerCase(Locale.ROOT).contains(sector.trim().toLowerCase(Locale.ROOT)))
                && (region == null || region.isBlank()
                || listing.region.toLowerCase(Locale.ROOT).contains(region.trim().toLowerCase(Locale.ROOT)))
                && (maximumPrice == null || listing.askingPrice != null
                && listing.askingPrice.compareTo(maximumPrice) <= 0);
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
        String sellerDisplayName = !signed && viewer.role().equals(Role.BUYER.name())
                ? "Cédant confidentiel" : seller.fullName;
        String companyName = null;
        String exactLocation = null;
        boolean encryptedIdentityUnavailable = false;
        if (signed) {
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
                request.buyerSignedAt != null, request.sellerSignedAt != null, request.ndaVersion,
                encryptedIdentityUnavailable);
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

    private static void requireListingManager(UserView user, Listing listing) {
        if (user == null || !(user.role().equals(Role.BROKER.name()) && Objects.equals(user.id(), listing.brokerId))
                && !(user.role().equals(Role.SELLER.name()) && Objects.equals(user.id(), listing.sellerId))) {
            throw new SecurityException("Accès réservé au cédant ou au courtier mandaté.");
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

    private static void requireNdaAccess(UserView user, DealRequest request, Listing listing) {
        if (!request.isNdaSigned()) {
            throw new SecurityException("L'accord de confidentialité doit être signé par les deux parties.");
        }
        boolean buyer = user.role().equals(Role.BUYER.name()) && request.buyerId.equals(user.id());
        if (!buyer && !managesListing(user, listing)) {
            throw new SecurityException("Accès réservé aux participants autorisés à ce dossier.");
        }
    }

    private static void requireRole(UserView user, Role role) {
        if (user == null || !role.name().equals(user.role())) {
            throw new SecurityException("Cette action n'est pas autorisée pour votre profil.");
        }
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
                document.sizeBytes, document.uploadedAt, unavailable);
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
