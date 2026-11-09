(() => {
    document.addEventListener("DOMContentLoaded", () => {
        const roleSelector = document.querySelector("[data-registration-role]");
        const typeSelector = document.querySelector("[data-buyer-type-selector]");
        const buyerTypeField = document.querySelector("[data-buyer-account-type]");
        const organizationField = document.querySelector("[data-buyer-organization]");
        const individualFields = document.querySelector('[data-buyer-details="INDIVIDUAL"]');
        const companyFields = document.querySelector('[data-buyer-details="COMPANY"]');

        if (!roleSelector || !typeSelector || !buyerTypeField || !organizationField
                || !individualFields || !companyFields) {
            return;
        }

        const updateBuyerFields = () => {
            const isBuyer = roleSelector.value === "BUYER";
            const isCompany = typeSelector.value === "COMPANY";
            buyerTypeField.hidden = !isBuyer;
            organizationField.hidden = isBuyer && !isCompany;
            individualFields.hidden = !isBuyer || isCompany;
            companyFields.hidden = !isBuyer || !isCompany;
        };

        roleSelector.addEventListener("change", updateBuyerFields);
        typeSelector.addEventListener("change", updateBuyerFields);
        updateBuyerFields();
    });
})();
