package fr.alp.confidentia.config;

import jakarta.servlet.ServletContext;

public final class AppConfiguration {
    private static volatile ServletContext servletContext;

    private AppConfiguration() {
    }

    public static void initialize(ServletContext context) {
        servletContext = context;
    }

    public static String get(String name, String fallback) {
        ServletContext context = servletContext;
        String value = context == null ? null : context.getInitParameter(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
