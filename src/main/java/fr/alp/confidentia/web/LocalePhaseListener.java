package fr.alp.confidentia.web;

import jakarta.faces.context.FacesContext;
import jakarta.faces.event.PhaseEvent;
import jakarta.faces.event.PhaseId;
import jakarta.faces.event.PhaseListener;

import java.util.Locale;

public class LocalePhaseListener implements PhaseListener {
    private static final long serialVersionUID = 1L;
    private static final String SESSION_LANGUAGE_KEY = "alp.language";

    @Override
    public void afterPhase(PhaseEvent event) {
        FacesContext context = event.getFacesContext();
        if (context.getViewRoot() == null) {
            return;
        }
        Object selectedLanguage = context.getExternalContext().getSessionMap().get(SESSION_LANGUAGE_KEY);
        String language = "en".equals(selectedLanguage) ? "en" : "fr";
        context.getViewRoot().setLocale(Locale.forLanguageTag(language));
    }

    @Override
    public void beforePhase(PhaseEvent event) {
    }

    @Override
    public PhaseId getPhaseId() {
        return PhaseId.RESTORE_VIEW;
    }
}
