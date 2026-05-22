(function() {
    const getStoredTheme = () => {
        try {
            return localStorage.getItem('theme');
        } catch (e) {
            return null;
        }
    };

    const setStoredTheme = theme => {
        try {
            if (theme === 'auto') {
                localStorage.removeItem('theme');
            } else {
                localStorage.setItem('theme', theme);
            }
        } catch (e) {}
    };

    const getPreferredTheme = () => {
        const storedTheme = getStoredTheme();
        if (storedTheme) {
            return storedTheme;
        }
        return 'auto';
    };

    const setTheme = theme => {
        let actualTheme = theme;
        if (theme === 'auto') {
            actualTheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
            document.documentElement.style.colorScheme = 'light dark';
        } else {
            document.documentElement.style.colorScheme = theme;
        }
        document.documentElement.setAttribute('data-bs-theme', actualTheme);
        updateUI(theme, actualTheme);
    };

    const updateUI = (theme, actualTheme) => {
        // Update icons and buttons if they exist
        const themeIcon = document.getElementById('theme-icon');
        const themeToggle = document.getElementById('bd-theme');

        if (themeIcon) {
            if (actualTheme === 'dark') {
                themeIcon.classList.remove('bi-sun-fill');
                themeIcon.classList.add('bi-moon-stars-fill');
            } else {
                themeIcon.classList.remove('bi-moon-stars-fill');
                themeIcon.classList.add('bi-sun-fill');
            }
        }

        if (themeToggle) {
            themeToggle.setAttribute('aria-pressed', actualTheme === 'dark');
        }
    };

    // Initialize theme immediately to prevent flash
    setTheme(getPreferredTheme());

    // Listen for system theme changes
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
        const storedTheme = getStoredTheme();
        if (!storedTheme) {
            setTheme('auto');
        }
    });

    // Expose toggle function globally
    window.toggleTheme = () => {
        const currentTheme = getPreferredTheme();
        let newTheme;
        // Cycle: auto -> dark -> light -> auto
        if (currentTheme === 'auto') {
            newTheme = 'dark';
        } else if (currentTheme === 'dark') {
            newTheme = 'light';
        } else {
            newTheme = 'auto';
        }
        setStoredTheme(newTheme);
        setTheme(newTheme);
    };

    // When DOM is loaded, update UI again to catch any elements that weren't ready
    document.addEventListener('DOMContentLoaded', () => {
        setTheme(getPreferredTheme());

        const themeToggle = document.getElementById('bd-theme');
        if (themeToggle) {
            themeToggle.addEventListener('click', () => {
                window.toggleTheme();
            });
        }
    });
})();
