// Offline DOM-event check of the shipped selection script; no MCP server or browser dependency.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Element {
    constructor() { this.children = []; this.listeners = {}; this.attributes = {}; this.value = ''; }
    set innerHTML(value) { assert.equal(value, ''); this.children = []; }
    appendChild(child) { this.children.push(child); }
    setAttribute(name, value) { this.attributes[name] = value; }
    addEventListener(name, listener) { this.listeners[name] = listener; }
    fire(name) { this.listeners[name].call(this); }
}

const elements = new Map();
const element = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
};
element('pageSize').value = '25';
const tools = Array.from({length: 30}, (_, index) => ({
    qualifiedName: `mcp:docs:read-${String(index).padStart(2, '0')}`,
    serverName: 'docs', toolName: `read-${String(index).padStart(2, '0')}`,
    title: 'Read', description: 'Read-only hint does not approve',
    selected: index === 0 || index === 29,
    reviewReadOnlyApproved: index === 0 || index === 29
}));
const template = fs.readFileSync(path.join(__dirname,
    '../../main/resources/templates/system-settings/mcp-tool-selection.html'), 'utf8');
const script = template.match(/<script th:inline="javascript">([\s\S]*?)<\/script>/)[1]
    .replace(/const allTools =[^\n]*;/, `const allTools = ${JSON.stringify(tools)};`);
vm.runInNewContext(script, {document: {
    getElementById: element,
    createElement: () => new Element(),
    querySelectorAll: () => [],
    addEventListener: (name, listener) => { assert.equal(name, 'DOMContentLoaded'); listener(); }
}});

const submitted = name => element('selectionInputs').children.filter(input => input.name === name).map(input => input.value).sort();
const grants = () => submitted('reviewReadOnlyApprovedQualifiedNames');
const revocations = () => submitted('reviewReadOnlyRevokedQualifiedNames');
const selected = () => submitted('selectedQualifiedNames');
const checkbox = (index, approval = false) => {
    const label = `${approval ? 'Read-only approval (new review protocol)' : 'Selected'}: ${tools[index].qualifiedName}`;
    const inputs = element('toolRows').children.flatMap(row => row.children.flatMap(cell => cell.children));
    const input = inputs.find(input => input.attributes['aria-label'] === label);
    assert.ok(input, `Missing visible checkbox: ${label}`);
    return input;
};
const check = (input, value) => { input.checked = value; input.fire('change'); };
assert.deepEqual(grants(), [], 'existing checked approvals are not fresh grants');
assert.equal(checkbox(0, true).checked, true);
assert.equal(checkbox(1, true).disabled, true);
check(element('selectAllVisible'), true);
assert.equal(selected().length, 26);
assert.deepEqual(grants(), [], 'select-all must not grant approval');
assert.equal(checkbox(0, true).checked, true);
check(checkbox(1, true), true);
const withExplicit = [tools[1].qualifiedName];
assert.deepEqual(grants(), withExplicit);

element('toolFilter').value = 'read-29';
element('toolFilter').fire('input');
assert.equal(checkbox(29, true).checked, true);
assert.deepEqual(grants(), withExplicit, 'filtering preserves hidden explicit edits');
element('toolFilter').value = '';
element('toolFilter').fire('input');
element('pageSize').value = '10';
element('pageSize').fire('change');
element('nextPage').fire('click');
element('nextPage').fire('click');
assert.equal(checkbox(29, true).checked, true);
assert.deepEqual(grants(), withExplicit, 'paging preserves hidden explicit edits');
check(checkbox(29), false);
check(checkbox(29), true);
assert.equal(checkbox(29, true).checked, false, 'reselection must not restore revoked trust');
assert.deepEqual(grants(), withExplicit);
assert.deepEqual(revocations(), [tools[29].qualifiedName]);
element('prevPage').fire('click');
element('prevPage').fire('click');
check(element('selectAllVisible'), false);
check(element('selectAllVisible'), true);
assert.deepEqual(grants(), [], 'bulk selection must not restore approvals');
assert.ok(revocations().includes(tools[0].qualifiedName));
console.log('MCP selection: explicit approval, select-all, filter, paging and revocation passed');
