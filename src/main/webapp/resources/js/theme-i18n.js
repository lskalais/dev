(() => {
    const storageKey = "africa-legacy-market-theme";
    let savedTheme = null;
    try {
        savedTheme = window.localStorage.getItem(storageKey);
    } catch (error) {
        savedTheme = null;
    }

    if (savedTheme === "dark") {
        document.documentElement.dataset.theme = "dark";
    }

    document.addEventListener("DOMContentLoaded", () => {
        const toggleButtons = document.querySelectorAll("[data-theme-toggle]");

        const updateButtons = () => {
            const isDark = document.documentElement.dataset.theme === "dark";
            toggleButtons.forEach((button) => {
                button.textContent = isDark
                    ? (button.dataset.lightLabel || "Mode clair")
                    : (button.dataset.darkLabel || "Mode sombre");
                button.setAttribute("aria-pressed", String(isDark));
            });
        };

        updateButtons();
        toggleButtons.forEach((button) => {
            button.addEventListener("click", () => {
                const isDark = document.documentElement.dataset.theme !== "dark";
                document.documentElement.dataset.theme = isDark ? "dark" : "light";
                try {
                    window.localStorage.setItem(storageKey, isDark ? "dark" : "light");
                } catch (error) {
                    // stockage indisponible (navigation privée) : le thème reste valable pour la page
                }
                updateButtons();
            });
        });

        // Barre fixe du template : ombre plus marquée une fois la page défilée.
        const bars = document.querySelectorAll(".public-header, .topbar");
        if (bars.length > 0) {
            const syncStuck = () => {
                const scrolled = window.scrollY > 24;
                bars.forEach((bar) => bar.classList.toggle("stuck", scrolled));
            };
            syncStuck();
            window.addEventListener("scroll", syncStuck, { passive: true });
        }
    });
})();
