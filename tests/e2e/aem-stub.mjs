// Deterministic stand-in for an AEM author instance, used by the E2E suite so the MCP tools and
// health probes have something real to call without a live AEM. Covers every endpoint the server
// hits: QueryBuilder, Sling node GETs, and the OSGi bundles console. Special path segments let the
// tests drive the server's error-mapping branches:
//   - ".../forbidden..."   -> 403 (exercises the aem_http_error envelope / forbidden health category)
//   - ".../unreachable..." -> the socket is destroyed (exercises aem_unreachable / unreachable health)
import http from 'node:http';

const PORT = Number(process.env.AEM_STUB_PORT ?? 14502);

const server = http.createServer((req, res) => {
  const { pathname } = new URL(req.url, `http://127.0.0.1:${PORT}`);

  // Simulate a connection-layer failure (DNS/TLS/refused look the same to the client).
  if (pathname.includes('/unreachable')) {
    req.socket.destroy();
    return;
  }
  // Simulate AEM rejecting the request (permission/auth error).
  if (pathname.includes('/forbidden')) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Forbidden');
    return;
  }

  const json = (obj, code = 200) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj));
  };

  // QueryBuilder — used by searchContent and its health probe (p.limit=0).
  if (pathname === '/bin/querybuilder.json') {
    return json({
      success: true,
      results: 1,
      total: 1,
      more: false,
      hits: [{ 'jcr:path': '/content/yoursite/en', 'jcr:title': 'Home', 'jcr:primaryType': 'cq:Page' }],
    });
  }

  // OSGi bundles console — used by bundleHealth and its health probe.
  if (pathname === '/system/console/bundles.json') {
    return json({
      status: 'Bundle information: 42 bundles in total - all 42 bundles active.',
      s: [42, 42, 0, 0, 0],
      data: [{ id: 0, name: 'System Bundle', state: 'Active', stateRaw: 32 }],
    });
  }

  // Any Sling node GET (inspectNode tool: <path>.<depth>.tidy.json; inspect health: <prefix>.0.json).
  if (pathname.endsWith('.json')) {
    return json({
      'jcr:primaryType': 'cq:Page',
      'jcr:title': 'Stub Node',
      'jcr:content': { 'sling:resourceType': 'yoursite/components/page' },
    });
  }

  json({ ok: true });
});

server.listen(PORT, '127.0.0.1', () => {
  // eslint-disable-next-line no-console
  console.log(`AEM stub listening on http://127.0.0.1:${PORT}`);
});
