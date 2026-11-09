package fr.alp.confidentia.model;

public enum BuyerKycDocumentType {
    IDENTITY_DOCUMENT("Pièce d’identité"),
    COMPANY_REGISTRATION("Extrait RCCM ou registre équivalent"),
    COMPANY_STATUTES("Statuts de la personne morale"),
    LEGAL_REPRESENTATIVE_ID("Pièce d’identité du représentant légal"),
    REPRESENTATION_AUTHORITY("Pouvoir ou mandat de représentation"),
    BENEFICIAL_OWNERS("Déclaration ou justificatifs des bénéficiaires effectifs"),
    SOURCE_OF_FUNDS("Justificatif d’origine des fonds");

    private final String label;

    BuyerKycDocumentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isAvailableFor(BuyerType buyerType) {
        return switch (this) {
            case IDENTITY_DOCUMENT -> buyerType == BuyerType.INDIVIDUAL;
            case COMPANY_REGISTRATION, COMPANY_STATUTES, LEGAL_REPRESENTATIVE_ID,
                    REPRESENTATION_AUTHORITY, BENEFICIAL_OWNERS -> buyerType == BuyerType.COMPANY;
            case SOURCE_OF_FUNDS -> buyerType == BuyerType.INDIVIDUAL || buyerType == BuyerType.COMPANY;
        };
    }
}
