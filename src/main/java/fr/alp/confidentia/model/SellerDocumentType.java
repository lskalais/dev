package fr.alp.confidentia.model;

public enum SellerDocumentType {
    RCCM("Extrait RCCM"),
    TAX_EXISTENCE_DECLARATION("Déclaration fiscale d’existence"),
    STATUTES("Statuts"),
    REPRESENTATIVE_IDENTITY("Pièce d’identité du représentant"),
    AUTHORITY_TO_SELL("Pouvoir de céder ou mandat"),
    OTHER("Autre pièce justificative");

    private final String label;

    SellerDocumentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
