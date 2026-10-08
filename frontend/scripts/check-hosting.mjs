import assert from 'node:assert/strict';

const target = process.argv[2];
if (!target) {
  console.error('Uso: pnpm check:hosting https://endereco-do-site');
  process.exit(1);
}
const base = new URL(target);
assert.equal(base.protocol, 'https:', 'Use o endereço HTTPS publicado.');
assert.equal(base.pathname, '/', 'Informe somente a origem do site.');
assert.ok(!base.username && !base.password && !base.search && !base.hash);
const timings = [];

async function get(path, expectedStatus = 200, headers = {}) {
  const start = performance.now();
  const response = await fetch(new URL(path, base), {
    headers,
    redirect: 'error',
    signal: AbortSignal.timeout(90000),
  });
  const body = await response.text();
  assert.equal(response.status, expectedStatus, `${path}: status inesperado`);
  timings.push({ path, status: response.status, ms: Math.round(performance.now() - start) });
  return { response, body };
}

function json(result, path) {
  assert.match(
    result.response.headers.get('content-type') || '',
    /application\/json/,
    `${path}: a API deve responder JSON, sem página de login da Vercel ou HTML do Angular`,
  );
  assert.match(
    result.response.headers.get('cache-control') || '',
    /no-store/,
    `${path}: os dados da API não devem ser armazenados em cache compartilhado`,
  );
  assert.notEqual(
    result.response.headers.get('x-vercel-cache'),
    'HIT',
    `${path}: resposta privada não pode vir do cache da Vercel`,
  );
  return JSON.parse(result.body);
}

try {
  const home = await get('/');
  assert.match(home.body, /<app-root>/);
  assert.match(home.response.headers.get('content-security-policy') || '', /connect-src 'self'/);
  assert.equal(home.response.headers.get('x-frame-options'), 'DENY');
  assert.equal(home.response.headers.get('x-content-type-options'), 'nosniff');
  const assets = [...home.body.matchAll(/(?:src|href)="([^"\s]+\.(?:js|css))"/g)].map(
    (match) => match[1],
  );
  assert.ok(
    assets.some((asset) => asset.startsWith('main-')),
    'Bundle principal ausente',
  );
  for (const asset of assets) {
    const resource = await get(`/${asset}`);
    assert.match(
      resource.response.headers.get('content-type') || '',
      asset.endsWith('.css') ? /text\/css/ : /javascript/,
      `${asset}: o arquivo está sendo entregue como HTML`,
    );
  }
  assert.equal(json(await get('/api/health'), '/api/health').status, 'UP');
  for (const path of ['/api/demo', '/api/demo/finished']) {
    const demo = json(await get(path), path);
    assert.equal(demo.club.demo, true);
    assert.ok(demo.game.id && Array.isArray(demo.teams));
  }
  const session = await get('/api/auth/csrf');
  const token = json(session, '/api/auth/csrf');
  assert.ok(token.token && token.headerName === 'X-CSRF-TOKEN');
  const cookie = session.response.headers
    .getSetCookie()
    .find((value) => value.startsWith('SESSION='));
  assert.ok(cookie, 'Cookie de sessão não foi encaminhado pelo proxy');
  for (const flag of ['Secure', 'HttpOnly', 'SameSite=Lax', 'Path=/']) {
    assert.ok(cookie.includes(flag), `Cookie sem ${flag}`);
  }
  assert.ok(!/;\s*Domain=/i.test(cookie), 'O cookie deve pertencer à origem acessada');
  const cookieHeader = cookie.split(';')[0];
  json(await get('/api/auth/csrf', 200, { Cookie: cookieHeader }), '/api/auth/csrf');
  for (const path of ['/api/auth/me', '/api/groups', '/api/admin/summary', '/api/career/groups']) {
    json(await get(path, 401, { Cookie: cookieHeader }), path);
  }
  const schema = json(await get('/api/openapi'), '/api/openapi');
  for (const path of [
    '/api/auth/profile',
    '/api/auth/profile/photo',
    '/api/players/{playerId}/photo',
    '/api/groups/{id}/career/me',
    '/api/games/{id}/attendance-review',
  ]) {
    assert.ok(schema.paths[path], `${path}: rota ausente na API publicada`);
  }
  assert.ok(
    schema.components.schemas.UserView.properties.photoUrl,
    'Campo de foto ausente na conta',
  );
  console.table(timings);
  console.log(
    'Entrega, API, carreira, perfil, demonstrações e cookie seguro verificados. Nenhuma conta ou grupo criado.',
  );
  console.log('Os tempos representam esta execução; não medem uma inicialização após hibernação.');
} catch (error) {
  console.table(timings);
  console.error(`Falha: ${error.message}`);
  process.exitCode = 1;
}
