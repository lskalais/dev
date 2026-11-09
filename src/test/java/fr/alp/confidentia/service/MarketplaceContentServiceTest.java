package fr.alp.confidentia.service;

import fr.alp.confidentia.model.Article;
import fr.alp.confidentia.model.ContactMessage;
import fr.alp.confidentia.service.ViewModels.UserView;
import fr.alp.confidentia.testsupport.MockJpa;
import fr.alp.confidentia.testsupport.TestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

class MarketplaceContentServiceTest {
    private final MarketplaceService service = new MarketplaceService();
    private MockJpa jpa;
    private final UserView administrator = TestSupport.superUser(1L);
    private final UserView buyer = TestSupport.buyer(20L);

    @BeforeEach
    void setUp() {
        TestSupport.configureEncryptionKey();
        jpa = new MockJpa();
    }

    @AfterEach
    void tearDown() {
        jpa.close();
        TestSupport.clearEncryptionKey();
    }

    private EntityManager em() {
        return jpa.em;
    }

    @Test
    void saveArticle_createsDraft() {
        var saved = service.saveArticle(administrator, null, " Transmission ", "CESSION",
                "Résumé", "Contenu éditorial");

        ArgumentCaptor<Article> article = ArgumentCaptor.forClass(Article.class);
        verify(em()).persist(article.capture());
        assertEquals("Transmission", saved.title());
        assertEquals("DRAFT", saved.status());
        assertEquals(1L, article.getValue().createdBy);
        assertNotNull(article.getValue().updatedAt);
    }

    @Test
    void publishArticle_setsPublicationTimestampAndPublicQueryReturnsIt() {
        Article article = new Article();
        article.id = 5L;
        article.title = "Transmission";
        article.category = "CESSION";
        article.summary = "Résumé";
        article.content = "Texte";
        article.status = "DRAFT";
        doReturn(article).when(em()).find(Article.class, 5L);

        service.setArticlePublished(administrator, 5L, true);

        assertEquals("PUBLISHED", article.status);
        assertNotNull(article.publishedAt);
        MockJpa.query(em(),
                "select a from Article a where a.status = 'PUBLISHED' order by a.publishedAt desc, a.id desc",
                List.of(article));
        assertEquals(1, service.publishedArticles().size());
        assertNotNull(service.publishedArticle(5L));
    }

    @Test
    void submitContactMessage_encryptsPersonalDataAndMessage() {
        service.submitContactMessage("Alex Martin", "Alex@example.com", "+225 01020304",
                "COMPLAINT", "Accès au compte", "Je souhaite signaler un problème.");

        ArgumentCaptor<ContactMessage> message = ArgumentCaptor.forClass(ContactMessage.class);
        verify(em()).persist(message.capture());
        ContactMessage saved = message.getValue();
        assertEquals("NEW", saved.status);
        assertNotEquals("alex@example.com", saved.senderEmailEncrypted);
        assertNotEquals("Je souhaite signaler un problème.", saved.messageTextEncrypted);
        assertEquals("alex@example.com",
                fr.alp.confidentia.security.CryptoService.decrypt(saved.senderEmailEncrypted));
    }

    @Test
    void submitContactMessage_rejectsInvalidType() {
        assertThrows(IllegalArgumentException.class, () -> service.submitContactMessage(
                "Alex Martin", "alex@example.com", null, "OTHER", "Objet", "Message"));
    }

    @Test
    void nonAdminCannotManageArticlesOrReadContactInbox() {
        assertThrows(SecurityException.class,
                () -> service.saveArticle(buyer, null, "Titre", "Thème", "Résumé", "Contenu"));
        assertThrows(SecurityException.class, () -> service.contactMessagesFor(buyer));
    }
}
