/*
 * Sample MCP server simulating a platform deploy surface for AI-Git-Bot M5.
 *
 * Exposes three tools over the Streamable HTTP MCP transport at /mcp:
 *   - platform__deploy_preview   (deployTool)
 *   - platform__preview_status   (statusTool)
 *   - platform__teardown_preview (teardownTool)
 *
 * Per-run state is kept in-memory; on `platform__deploy_preview` the
 * server allocates a fake deploymentId, schedules a transition to READY
 * after DEPLOY_DELAY_MS, and returns a PENDING handle. Subsequent
 * `platform__preview_status` calls return PENDING / READY / FAILED based
 * on that timer; READY responses include `previewUrl` pointing at the
 * companion sample-e2e-app container (the same M4 wave-2 login app).
 *
 * On teardown the entry is removed; further status calls return FAILED.
 *
 * This is intentionally ~120 lines and dependency-light. It demonstrates
 * the *protocol shape* AI-Git-Bot expects (see doc/MCP_SERVER_HANDLING.md
 * § 6), not real Kubernetes mechanics.
 */
const express = require('express');
const { randomUUID } = require('crypto');
const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
const {
  StreamableHTTPServerTransport
} = require('@modelcontextprotocol/sdk/server/streamableHttp.js');
const { z } = require('zod');

const PORT = process.env.PORT ? Number(process.env.PORT) : 8090;
const DEPLOY_DELAY_MS = process.env.DEPLOY_DELAY_MS
  ? Number(process.env.DEPLOY_DELAY_MS)
  : 3000;
const PREVIEW_URL = process.env.PREVIEW_URL || 'http://sample-e2e-app:3000';

/** runId -> { deploymentId, status, readyAt, previewUrl } */
const deployments = new Map();

function trigger(args) {
  const runId = String(args.runId ?? randomUUID());
  const deploymentId = `dep-${randomUUID().slice(0, 8)}`;
  const readyAt = Date.now() + DEPLOY_DELAY_MS;
  deployments.set(runId, {
    deploymentId,
    status: 'pending',
    readyAt,
    previewUrl: PREVIEW_URL,
    prNumber: args.prNumber,
    repoOwner: args.repoOwner,
    repoName: args.repoName
  });
  console.log(
    `[deploy] runId=${runId} pr=${args.prNumber} repo=${args.repoOwner}/${args.repoName} deploymentId=${deploymentId}`
  );
  // Optional early-ready shortcut for fast local demos: set DEPLOY_DELAY_MS=0.
  if (DEPLOY_DELAY_MS <= 0) {
    deployments.get(runId).status = 'ready';
    return { previewUrl: PREVIEW_URL, handle: { deploymentId } };
  }
  return { handle: { deploymentId, runId } };
}

function status(args) {
  const runId = String(args.runId);
  const entry = deployments.get(runId);
  if (!entry) {
    return { status: 'failed', error: `unknown runId ${runId}` };
  }
  if (entry.status === 'pending' && Date.now() >= entry.readyAt) {
    entry.status = 'ready';
    console.log(`[status] runId=${runId} → READY (${entry.previewUrl})`);
  }
  if (entry.status === 'ready') {
    return { previewUrl: entry.previewUrl, handle: { deploymentId: entry.deploymentId } };
  }
  if (entry.status === 'failed') {
    return { status: 'failed', error: 'deployment failed' };
  }
  return { handle: { deploymentId: entry.deploymentId, runId } };
}

function teardown(args) {
  const runId = String(args.runId);
  const entry = deployments.get(runId);
  if (!entry) {
    return { ok: true, note: `runId ${runId} already gone` };
  }
  deployments.delete(runId);
  console.log(`[teardown] runId=${runId} deploymentId=${entry.deploymentId}`);
  return { ok: true, deploymentId: entry.deploymentId };
}

function buildServer() {
  const server = new McpServer({
    name: 'sample-platform-mcp',
    version: '0.1.0'
  });

  const argsShape = {
    runId: z.union([z.string(), z.number()]).optional(),
    prNumber: z.union([z.string(), z.number()]).optional(),
    repoOwner: z.string().optional(),
    repoName: z.string().optional(),
    sha: z.string().optional(),
    branch: z.string().optional(),
    callbackUrl: z.string().optional(),
    callbackSecret: z.string().optional(),
    handle: z.any().optional()
  };

  const wrap = (fn) => async (args) => ({
    content: [{ type: 'text', text: JSON.stringify(fn(args ?? {})) }]
  });

  server.tool(
    'platform__deploy_preview',
    'Deploy a per-PR preview environment. Returns {previewUrl} when ready or {handle} for PENDING.',
    argsShape,
    wrap(trigger)
  );
  server.tool(
    'platform__preview_status',
    'Return current status for a previously triggered deployment.',
    argsShape,
    wrap(status)
  );
  server.tool(
    'platform__teardown_preview',
    'Tear down a previously triggered deployment.',
    argsShape,
    wrap(teardown)
  );

  return server;
}

async function main() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/healthz', (_req, res) => res.json({ status: 'ok', tools: 3 }));

  // Stateless streamable-HTTP MCP endpoint — a fresh transport per request
  // keeps the example tiny and works fine for the bot's poll-style usage.
  app.all('/mcp', async (req, res) => {
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined
    });
    res.on('close', () => transport.close());
    const server = buildServer();
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  });

  app.listen(PORT, () => {
    console.log(`sample-mcp-deploy-server listening on http://0.0.0.0:${PORT}/mcp`);
    console.log(`  preview URL handed out on READY: ${PREVIEW_URL}`);
    console.log(`  deploy delay: ${DEPLOY_DELAY_MS} ms`);
  });
}

main().catch((err) => {
  console.error('fatal:', err);
  process.exit(1);
});


