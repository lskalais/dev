(() => {
    const storageKey = "africa-legacy-market-theme";
    const savedTheme = window.localStorage.getItem(storageKey);

    if (savedTheme === "dark") {
        document.documentElement.dataset.theme = "dark";
    }

    document.addEventListener("DOMContentLoaded", () => {
        const toggleButtons = document.querySelectorAll("[data-theme-toggle]");

        const updateButtons = () => {
            const isDark = document.documentElement.dataset.theme === "dark";
            toggleButtons.forEach((button) => {
                button.textContent = isDark ? "Mode clair" : "Mode sombre";
                button.setAttribute("aria-pressed", String(isDark));
            });
        };

        updateButtons();
        toggleButtons.forEach((button) => {
            button.addEventListener("click", () => {
                const isDark = document.documentElement.dataset.theme !== "dark";
                document.documentElement.dataset.theme = isDark ? "dark" : "light";
                window.localStorage.setItem(storageKey, isDark ? "dark" : "light");
                updateButtons();
            });
        });
    });
})();
