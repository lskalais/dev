package fr.alp.confidentia.model;

public enum PartnerExpertCategory {
    ACCOUNTANT("Expert-comptable"),
    LAWYER("Avocat"),
    NOTARY("Notaire"),
    TAX_ADVISOR("Conseiller fiscal"),
    VALUATOR("Évaluateur"),
    BANK("Banque"),
    CONSULTANT("Consultant");

    private final String label;

    PartnerExpertCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
