package fr.alp.confidentia.model;

public enum SaleProcessStage {
    SELLER_REGISTRATION("Inscription du cédant"),
    SELLER_VERIFICATION("Vérification du cédant"),
    LISTING_PREPARATION("Préparation du dossier de cession"),
    LISTING_PUBLICATION("Publication de l'annonce"),
    BUYER_INTEREST("Manifestations d'intérêt"),
    CONFIDENTIALITY_AGREEMENT("Accord de confidentialité"),
    DUE_DILIGENCE("Revue documentaire et diagnostic"),
    NEGOTIATION("Négociation"),
    TRANSACTION_CLOSING("Conclusion de la cession"),
    POST_CLOSING_SUPPORT("Accompagnement post-cession"),
    COMPLETED("Processus clôturé");

    private final String label;

    SaleProcessStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public SaleProcessStage next() {
        SaleProcessStage[] stages = values();
        return ordinal() + 1 < stages.length ? stages[ordinal() + 1] : null;
    }
}
