/**
 * Reusable JSON editor built on CodeMirror 6 with Prettier formatting.
 *
 * Usage:
 *   import { initJsonEditor } from '/js/json-editor.js';
 *   initJsonEditor(document.getElementById('jsonContent'), {
 *       formatButton: document.getElementById('jsonContentFormatBtn')
 *   });
 *
 * The original <textarea> remains in the DOM (hidden) and is kept in sync
 * so that standard form submission continues to work.
 */

// Every module must resolve to the same @codemirror/view copy: CodeMirror matches extensions
// by facet identity, so a plugin from a second copy (e.g. the placeholder) is silently ignored.
import { basicSetup } from "https://esm.sh/codemirror@6.0.1?deps=@codemirror/state@6.7.6,@codemirror/view@6.43.14";
import { EditorView, placeholder as cmPlaceholder } from "https://esm.sh/@codemirror/view@6.43.14?deps=@codemirror/state@6.7.6";
import { Compartment } from "https://esm.sh/@codemirror/state@6.7.6";
import { json, jsonParseLinter } from "https://esm.sh/@codemirror/lang-json@6.0.1?deps=@codemirror/state@6.7.6,@codemirror/view@6.43.14";
import { linter, lintGutter } from "https://esm.sh/@codemirror/lint@6.9.7?deps=@codemirror/state@6.7.6,@codemirror/view@6.43.14";
import { oneDark } from "https://esm.sh/@codemirror/theme-one-dark@6.1.2?deps=@codemirror/state@6.7.6,@codemirror/view@6.43.14";
import * as prettier from "https://esm.sh/prettier@3.3.3/standalone";
import * as estreePlugin from "https://esm.sh/prettier@3.3.3/plugins/estree";
import * as babelPlugin from "https://esm.sh/prettier@3.3.3/plugins/babel";

const PRETTIER_PLUGINS = [estreePlugin, babelPlugin];

function jsonLinter() {
    return (view) => {
        const text = view.state.doc.toString();
        if (text.trim().length === 0) {
            return [];
        }
        return jsonParseLinter()(view);
    };
}

function isDarkMode() {
    return document.documentElement.getAttribute('data-bs-theme') === 'dark';
}

function themeExtension() {
    return isDarkMode() ? oneDark : [];
}

async function formatJson(text) {
    return prettier.format(text, {
        parser: "json",
        plugins: PRETTIER_PLUGINS,
        printWidth: 80,
        tabWidth: 2,
        useTabs: false
    });
}

/**
 * @param {HTMLTextAreaElement} textarea - the textarea to enhance
 * @param {Object} [options]
 * @param {HTMLElement} [options.formatButton] - button that triggers pretty-print
 * @param {string} [options.minHeight='120px'] - minimum editor height
 * @param {string} [options.maxHeight='600px'] - maximum editor height before scrolling
 * @returns {EditorView|null} the CodeMirror view, or null if initialization failed
 */
export function initJsonEditor(textarea, options) {
    if (!textarea || textarea.tagName !== 'TEXTAREA') {
        console.error('initJsonEditor: expected a textarea element');
        return null;
    }

    const opts = options || {};
    const formatButton = opts.formatButton || null;
    const minHeight = opts.minHeight || '120px';
    const maxHeight = opts.maxHeight || '600px';

    // Wrap the editor so the hidden textarea stays close to its original position.
    const wrapper = document.createElement('div');
    wrapper.id = textarea.id ? (textarea.id + '-editor') : '';
    wrapper.className = 'cm-editor-wrapper position-relative';
    textarea.parentNode.insertBefore(wrapper, textarea);

    const extensions = [
        basicSetup,
        json(),
        linter(jsonLinter()),
        lintGutter(),
        EditorView.updateListener.of((update) => {
            if (update.docChanged) {
                textarea.value = update.state.doc.toString();
            }
        }),
        EditorView.theme({
            "&": {
                fontSize: "14px",
                border: "1px solid var(--bs-border-color)",
                borderRadius: "var(--bs-border-radius)"
            },
            ".cm-scroller": {
                minHeight: minHeight,
                maxHeight: maxHeight
            },
            ".cm-gutters": {
                borderRadius: "var(--bs-border-radius) 0 0 var(--bs-border-radius)"
            }
        })
    ];

    if (textarea.placeholder) {
        extensions.push(cmPlaceholder(textarea.placeholder));
    }

    const themeCompartment = new Compartment();
    extensions.push(themeCompartment.of(themeExtension()));

    const view = new EditorView({
        doc: textarea.value,
        extensions: extensions,
        parent: wrapper
    });

    // Reconfigure the editor theme when the app theme is toggled at runtime.
    const applyTheme = () => {
        view.dispatch({ effects: themeCompartment.reconfigure(themeExtension()) });
    };
    document.addEventListener('themeChanged', applyTheme);

    // Hide the original textarea but keep it in the DOM for form submission.
    textarea.classList.add('d-none');

    // Keep CodeMirror in sync if something external mutates the textarea.
    textarea.addEventListener('input', () => {
        const current = view.state.doc.toString();
        if (current !== textarea.value) {
            view.dispatch({
                changes: { from: 0, to: current.length, insert: textarea.value }
            });
        }
    });

    async function doFormat() {
        const current = view.state.doc.toString().trim();
        if (!current) {
            return;
        }
        try {
            JSON.parse(current); // cheap validity check before invoking Prettier
            const formatted = await formatJson(current);
            view.dispatch({
                changes: { from: 0, to: view.state.doc.length, insert: formatted }
            });
        } catch (err) {
            // Lint gutter already highlights the parse error; no need to spam the console.
            if (err instanceof SyntaxError) {
                return;
            }
            console.warn('JSON format failed', err);
        }
    }

    if (formatButton) {
        formatButton.addEventListener('click', doFormat);
    }

    // Shift+Alt+F triggers pretty-print inside the editor.
    view.dom.addEventListener('keydown', (event) => {
        if (event.shiftKey && event.altKey && event.key === 'F') {
            event.preventDefault();
            doFormat();
        }
    });

    return view;
}
