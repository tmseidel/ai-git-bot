// Reviews pages: confirmations for destructive actions and the shared "propose a lesson" dialog.
(function () {
    'use strict';

    document.addEventListener('submit', function (event) {
        var form = event.target;
        var message = form.getAttribute && form.getAttribute('data-confirm');
        if (message && !window.confirm(message)) {
            event.preventDefault();
        }
    }, true);

    // Review texts, feedback reasons and lesson guidance are Markdown, as on the pull request.
    // The server sends them as escaped text; they are parsed here and sanitized before insertion.
    // Without the libraries the plain text stays, line breaks preserved by .text-pre.
    if (window.marked && window.DOMPurify) {
        document.querySelectorAll('.md').forEach(function (element) {
            element.innerHTML = window.DOMPurify.sanitize(
                window.marked.parse(element.textContent, {gfm: true, breaks: true}));
            element.classList.remove('text-pre');
            element.classList.add('md-rendered');
        });
        document.querySelectorAll('.md-inline').forEach(function (element) {
            element.innerHTML = window.DOMPurify.sanitize(
                window.marked.parseInline(element.textContent, {gfm: true}));
        });
    }

    var proposeModal = document.getElementById('proposeModal');
    if (proposeModal) {
        proposeModal.addEventListener('show.bs.modal', function (event) {
            var trigger = event.relatedTarget;
            if (!trigger) {
                return;
            }
            document.getElementById('proposeForm').setAttribute('action', trigger.getAttribute('data-action'));
            document.getElementById('proposeReference').textContent =
                trigger.getAttribute('data-reference') + ' · ' + trigger.getAttribute('data-target');
        });
    }
})();
