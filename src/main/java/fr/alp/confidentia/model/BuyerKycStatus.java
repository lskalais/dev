package fr.alp.confidentia.model;

public enum BuyerKycStatus {
    DOCUMENTS_PENDING("Documents en attente"),
    UNDER_REVIEW("Vérification en cours"),
    NEEDS_CORRECTION("Complément demandé"),
    VALIDATED("Profil vérifié");

    private final String label;

    BuyerKycStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
