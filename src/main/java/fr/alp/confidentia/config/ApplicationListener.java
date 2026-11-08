package fr.alp.confidentia.config;

import fr.alp.confidentia.persistence.Jpa;
import fr.alp.confidentia.service.MarketplaceService;
import jakarta.inject.Inject;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

public class ApplicationListener implements ServletContextListener {
    @Inject
    private MarketplaceService service;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        AppConfiguration.initialize(event.getServletContext());
        Jpa.initialize();
        service.bootstrapSuperUser();
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        Jpa.close();
    }
}
