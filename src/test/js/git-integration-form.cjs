// Run from the repository root: node src/test/js/git-integration-form.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const elements = new Map();
const get = id => {
    if (!elements.has(id)) elements.set(id, {
        value: '', style: {}, listeners: {},
        addEventListener(event, handler) { this.listeners[event] = handler; }
    });
    return elements.get(id);
};
get('providerType').value = 'GITEA';
get('transport').value = 'SSH';
get('url').value = 'https://old.example.com';
get('clearToken').value = get('clearSshCredentials').value = 'false';
const template = fs.readFileSync('src/main/resources/templates/git-integrations/form.html', 'utf8');
let script = template.match(/<script th:inline="javascript">([\s\S]*?)<\/script>/)[1];
// Substitute the server-rendered state of an existing SSH integration.
for (const [name, value] of Object.entries({
    isEditMode: true, hasStoredToken: true, hasStoredSshPrivateKey: true,
    hasStoredSshKnownHosts: true, originalTransport: 'SSH', giteaTransport: 'SSH',
    originalUrl: 'https://old.example.com'
})) {
    script = script.replace(new RegExp(`((?:const|let) ${name} = )[^;]+;`),
        `$1${JSON.stringify(value)};`);
}
vm.runInNewContext(script, { document: {
    getElementById: get,
    addEventListener(event, handler) { handler(); }
} });
get('sshPrivateKey').value = 'replacement-private-key';
get('sshPrivateKey').listeners.input();
assert.equal(get('transport').value, 'SSH');
assert.equal(get('clearSshCredentials').value, 'false');
assert.equal(get('sshKnownHosts').required, false);
assert.equal(get('sshPrivateKey').required, false);
assert.equal(get('token').required, false);
get('url').value = 'https://new.example.com';
get('url').listeners.input();
assert.equal(get('transport').value, 'SSH');
assert.equal(get('clearSshCredentials').value, 'false');
assert.equal(get('sshKnownHosts').required, true);
assert.equal(get('sshPrivateKey').required, false);
assert.equal(get('token').required, true);
get('token').value = 'replacement-token';
get('token').listeners.input();
assert.equal(get('transport').value, 'SSH');
get('clearTokenBtn').listeners.click();
assert.equal(get('transport').value, 'SSH');
assert.equal(get('clearSshCredentials').value, 'false');
get('clearSshCredentialsBtn').listeners.click();
assert.equal(get('transport').value, 'HTTP');
assert.equal(get('clearSshCredentials').value, 'true');
console.log('Git integration form regression passed');
