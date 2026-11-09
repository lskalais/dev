package fr.alp.confidentia.service;

import fr.alp.confidentia.config.AppConfiguration;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

final class NotificationEmailSender {
    private NotificationEmailSender() {
    }

    static boolean isConfigured() {
        return configuredValue("SMTP_HOST") != null && configuredValue("SMTP_FROM") != null;
    }

    static void send(String recipient, String subject, String body) {
        String host = configuredValue("SMTP_HOST");
        String from = configuredValue("SMTP_FROM");
        if (host == null || from == null) {
            return;
        }

        String username = configuredValue("SMTP_USERNAME");
        String password = configuredValue("SMTP_PASSWORD");
        if (username != null && password == null) {
            throw new IllegalStateException("SMTP_PASSWORD est requis lorsque SMTP_USERNAME est configuré.");
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", AppConfiguration.get("SMTP_PORT", "587"));
        properties.put("mail.smtp.auth", Boolean.toString(username != null));
        properties.put("mail.smtp.starttls.enable", AppConfiguration.get("SMTP_STARTTLS", "true"));
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");

        Authenticator authenticator = username == null ? null : new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        };
        Session session = Session.getInstance(properties, authenticator);
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, false));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            Transport.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("L’envoi de la notification par courriel a échoué.", exception);
        }
    }

    private static String configuredValue(String name) {
        String value = AppConfiguration.get(name, null);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
