package fr.alp.confidentia.model;

public enum SellerVerificationStatus {
    DOCUMENTS_PENDING("Documents en attente"),
    UNDER_REVIEW("En vérification"),
    DOCUMENTS_VALIDATED("Documents validés"),
    NEEDS_CORRECTION("Rejeté / à compléter"),
    ACCOUNT_VALIDATED("Compte validé");

    private final String label;

    SellerVerificationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
