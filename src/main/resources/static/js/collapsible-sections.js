/**
 * Reusable collapsible-section helper.
 *
 * Persists the collapsed state of a group of Bootstrap `.collapse` elements
 * to `localStorage` and wires optional "Expand all" / "Collapse all" buttons.
 *
 * Usage:
 *   initCollapsibleSections({
 *       storageKey:        'systemSettings.collapsedSections',
 *       collapses:         document.querySelectorAll('[id^="section-"].collapse'),
 *       expandButton:      document.getElementById('expandAllSections'),
 *       collapseButton:    document.getElementById('collapseAllSections'),
 *       allowMultipleOpen: false
 *   });
 *
 * Options:
 *   storageKey         (string, required) localStorage key used to persist the
 *                      set of currently collapsed element IDs.
 *   collapses          (NodeList|Array, required) collapse elements to manage.
 *                      Each element must have an `id`. The button toggling it
 *                      is located via `[data-bs-target="#<id>"]`.
 *   expandButton       (Element, optional) clicking opens every collapse.
 *   collapseButton     (Element, optional) clicking closes every collapse.
 *   allowMultipleOpen  (bool, optional) when true, removes `data-bs-parent`
 *                      from the collapses so Bootstrap accordions can keep
 *                      multiple items open at once.
 */
(function (global) {
    function initCollapsibleSections(options) {
        const opts = options || {};
        const storageKey = opts.storageKey;
        const collapses = Array.from(opts.collapses || []);
        if (!storageKey || collapses.length === 0) {
            return;
        }

        if (opts.allowMultipleOpen) {
            collapses.forEach(function (el) {
                el.removeAttribute('data-bs-parent');
            });
        }

        // Persisted as { "<id>": true|false } where true = open, false = collapsed.
        // Using an explicit map (rather than just a set of collapsed IDs) ensures
        // that sections which the user *opened* in addition to the HTML default
        // are also restored correctly (important for Bootstrap accordions where
        // only the first item ships with `.show`).
        function loadStates() {
            try {
                const raw = JSON.parse(localStorage.getItem(storageKey));
                if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
                    return raw;
                }
                // Backward-compatibility: previous versions stored an array of
                // collapsed IDs. Convert it transparently.
                if (Array.isArray(raw)) {
                    const migrated = {};
                    raw.forEach(function (id) { migrated[id] = false; });
                    return migrated;
                }
            } catch (e) { /* ignore */ }
            return {};
        }

        function saveStates(states) {
            try {
                localStorage.setItem(storageKey, JSON.stringify(states));
            } catch (e) { /* ignore */ }
        }

        const states = loadStates();

        function findHeader(el) {
            return document.querySelector('[data-bs-target="#' + el.id + '"]');
        }

        function applyState(el, open) {
            const header = findHeader(el);
            if (open) {
                el.classList.add('show');
                if (header) {
                    header.classList.remove('collapsed');
                    header.setAttribute('aria-expanded', 'true');
                }
            } else {
                el.classList.remove('show');
                if (header) {
                    header.classList.add('collapsed');
                    header.setAttribute('aria-expanded', 'false');
                }
            }
        }

        // Apply persisted state before Bootstrap initializes transitions.
        collapses.forEach(function (el) {
            if (Object.prototype.hasOwnProperty.call(states, el.id)) {
                applyState(el, states[el.id] === true);
            }
        });

        collapses.forEach(function (el) {
            el.addEventListener('shown.bs.collapse', function () {
                states[el.id] = true;
                saveStates(states);
            });
            el.addEventListener('hidden.bs.collapse', function () {
                states[el.id] = false;
                saveStates(states);
            });
        });

        if (opts.expandButton) {
            opts.expandButton.addEventListener('click', function () {
                collapses.forEach(function (el) {
                    bootstrap.Collapse.getOrCreateInstance(el, { toggle: false }).show();
                });
            });
        }
        if (opts.collapseButton) {
            opts.collapseButton.addEventListener('click', function () {
                collapses.forEach(function (el) {
                    bootstrap.Collapse.getOrCreateInstance(el, { toggle: false }).hide();
                });
            });
        }
    }

    global.initCollapsibleSections = initCollapsibleSections;
})(window);


