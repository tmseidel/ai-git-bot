/*
 * Workflow runs page: run toggles, copy buttons and the interaction details
 * modal. The modal's content is an HTML fragment the server renders and escapes; the raw payloads
 * are JSON and only ever set with textContent (untrusted content).
 */
document.addEventListener('DOMContentLoaded', function () {
    const page = document.getElementById('workflowRunsPage');
    if (!page) {
        return;
    }
    // The datetime-local filters are read in the browser's time zone.
    const tz = document.getElementById('filterTz');
    if (tz && !tz.value) {
        try {
            tz.value = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
        } catch (e) {
            tz.value = '';
        }
    }

    // Run toggles, Expand all / Collapse all
    function setExpanded(toggle, expanded) {
        const detail = document.getElementById(toggle.getAttribute('aria-controls'));
        toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
        if (detail) {
            detail.classList.toggle('d-none', !expanded);
        }
    }

    const toggles = page.querySelectorAll('.run-toggle');
    toggles.forEach(function (toggle) {
        toggle.addEventListener('click', function () {
            setExpanded(toggle, toggle.getAttribute('aria-expanded') !== 'true');
        });
    });
    const expandAll = document.getElementById('expandAllRuns');
    const collapseAll = document.getElementById('collapseAllRuns');
    if (expandAll) {
        expandAll.addEventListener('click', function () {
            toggles.forEach(function (toggle) { setExpanded(toggle, true); });
        });
    }
    if (collapseAll) {
        collapseAll.addEventListener('click', function () {
            toggles.forEach(function (toggle) { setExpanded(toggle, false); });
        });
    }

    // Copy buttons, also in the modal's inserted content
    document.addEventListener('click', function (event) {
        const button = event.target.closest('[data-copy]');
        if (button && navigator.clipboard) {
            navigator.clipboard.writeText(button.dataset.copy).catch(function () { /* not allowed */ });
        }
    });

    function load(url, signal) {
        return fetch(url, {signal: signal}).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            return response;
        });
    }

    // Interaction details modal: its content is an HTML fragment rendered (and escaped) by the server.
    const modal = document.getElementById('interactionModal');
    if (modal) {
        const dialog = modal.querySelector('.modal-dialog');
        const placeholder = dialog.innerHTML;
        let pending = null;
        modal.addEventListener('show.bs.modal', function (event) {
            const url = event.relatedTarget && event.relatedTarget.dataset.interactionUrl;
            if (pending) {
                pending.abort();
            }
            dialog.innerHTML = placeholder;
            if (!url) {
                return;
            }
            pending = new AbortController();
            load(url, pending.signal)
                .then(function (response) { return response.text(); })
                .then(function (html) {
                    dialog.innerHTML = html;
                    formatLocale(dialog);
                })
                .catch(function (error) {
                    if (error.name !== 'AbortError') {
                        dialog.querySelector('.modal-title').textContent = modal.dataset.failed;
                    }
                });
        });
        modal.addEventListener('hidden.bs.modal', function () {
            if (pending) {
                pending.abort();
                pending = null;
            }
            dialog.innerHTML = placeholder;
        });
        // Raw payloads: loaded the first time their section opens; set with textContent (untrusted).
        modal.addEventListener('show.bs.collapse', function (event) {
            const section = event.target;
            if (section.id !== 'interactionRaw' || section.dataset.loaded) {
                return;
            }
            section.dataset.loaded = 'true';
            const fields = section.querySelectorAll('[data-raw]');
            fields.forEach(function (el) { el.textContent = section.dataset.loading; });
            load(section.dataset.rawUrl)
                .then(function (response) { return response.json(); })
                .then(function (payload) {
                    fields.forEach(function (el) {
                        const value = payload[el.dataset.raw];
                        el.textContent = value != null ? value : '—';
                    });
                })
                .catch(function () {
                    fields.forEach(function (el) { el.textContent = section.dataset.failed; });
                });
        });
    }
});
