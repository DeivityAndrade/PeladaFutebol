import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// Executes the real n8n node implementations. All HTTP destinations and keys are fictitious.
// Run only with a dedicated local n8n test database; never pass production credentials.
const cli = process.env.N8N_TEST_CLI;
assert(cli, 'Set N8N_TEST_CLI to the installed n8n executable JS file');
assert.equal(process.env.DB_POSTGRESDB_HOST, '127.0.0.1');
assert.equal(process.env.DB_POSTGRESDB_DATABASE, 'todentro_n8n_test');
const dir = dirname(fileURLToPath(import.meta.url));
const temp = await mkdtemp(join(tmpdir(), 'todentro-n8n-test-'));
const calls = [], receipts = [];
let mode = 'inbox', modelCalls = 0;
const jobs = [1, 2, 3, 4].map(n => ({ id: `job${n}`, leaseId: `lease${n}`, text: n === 1 ? 'vou' : n === 4 ? 'modelo indisponível' : `consultar próxima partida ${n}`, groups: [{id: 'club-test', name: 'Turma', owner: true}], games: [], contextGameId: null, proposalPending: false }));
const server = createServer(async (req, res) => {
  let raw = ''; for await (const data of req) raw += data;
  const body = raw ? JSON.parse(raw) : {};
  calls.push({url: req.url, body});
  const answer = (json, status = 200) => { res.writeHead(status, {'Content-Type': 'application/json'}); res.end(JSON.stringify(json)); };
  if (req.url === '/api/integrations/whatsapp/inbox/claim') return answer(mode === 'empty' ? [] : jobs);
  if (req.url.endsWith('/action')) return answer({needsAgent: body.action === 'AUTO' && !req.url.includes('job1'), message: 'Resposta criada pelo sistema'});
  if (req.url.endsWith('/finish')) return answer({});
  if (req.url === '/v1/chat/completions') {
    modelCalls++;
    if (body.messages.some(m => String(m.content).includes('modelo indisponível'))) return answer({error: {message: 'Falha simulada', type: 'server_error'}}, 503);
    const hasTool = body.messages.some(m => m.role === 'tool');
    const message = hasTool ? {role: 'assistant', content: 'Concluído.'} : {role: 'assistant', content: null, tool_calls: [{ id: `call-${modelCalls}`, type: 'function', function: {name: body.tools[0].function.name, arguments: JSON.stringify({action: 'LIST', clubId: '', gameId: ''})} }]};
    return answer({id: `chat-${modelCalls}`, object: 'chat.completion', created: 1, model: 'gpt-4.1-mini', choices: [{index: 0, message, finish_reason: hasTool ? 'stop' : 'tool_calls'}], usage: {prompt_tokens: 1, completion_tokens: 1, total_tokens: 2}});
  }
  if (req.url === '/api/integrations/whatsapp/outbox/claim') return answer([1, 2, 3, 4].map(n => ({id: `send${n}`, leaseId: `sendlease${n}`})));
  if (req.url.endsWith('/dispatch')) return answer({send: !req.url.includes('send4'), url: `http://127.0.0.1:${server.address().port}/meta/${req.url.includes('send1') ? 'success' : req.url.includes('send2') ? 'failure' : 'unknown'}`, payload: {to: 'fake-number', messaging_product: 'whatsapp', type: 'text', text: {body: 'Teste'}}});
  if (req.url === '/meta/success') return answer({messages: [{id: 'wamid.fixture'}]});
  if (req.url === '/meta/failure') return answer({error: {code: 131026}}, 400);
  if (req.url === '/meta/unknown') { req.socket.destroy(); return; }
  if (req.url.endsWith('/receipt')) { receipts.push({url: req.url, ...body}); return answer({}); }
  return answer({error: 'Unexpected mock path'}, 404);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
async function run(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, ...args], {env: {...process.env, N8N_DIAGNOSTICS_ENABLED: 'false', N8N_VERSION_NOTIFICATIONS_ENABLED: 'false', N8N_PERSONALIZATION_ENABLED: 'false', EXECUTIONS_DATA_SAVE_ON_ERROR: 'none', EXECUTIONS_DATA_SAVE_ON_SUCCESS: 'none', N8N_RUNNERS_ENABLED: 'false'}});
    let output = ''; child.stdout.on('data', d => output += d); child.stderr.on('data', d => output += d);
    child.on('error', reject); child.on('close', code => {
      if (code || output.includes('Execution was NOT successful') || output.includes('Error executing workflow')) reject(new Error(output.slice(-12000)));
      else resolve(output);
    });
  });
}
try {
  const credentials = [{id: 'todentro-worker', name: 'Tô Dentro — integração', type: 'httpHeaderAuth', data: {name: 'Authorization', value: 'Bearer fake-worker'}}, {id: 'todentro-meta', name: 'Meta — WhatsApp', type: 'httpHeaderAuth', data: {name: 'Authorization', value: 'Bearer fake-meta'}}, {id: 'todentro-ai', name: 'OpenAI — Tô Dentro', type: 'openAiApi', data: {apiKey: 'fake-model', url: origin + '/v1'}}];
  await writeFile(join(temp, 'credentials.json'), JSON.stringify(credentials));
  console.log('Importando credenciais fictícias no banco local isolado…');
  await run(['import:credentials', '--input=' + join(temp, 'credentials.json')]);
  const workflows = await Promise.all(['agent.json', 'delivery.json'].map(async file => {
    const w = JSON.parse(await readFile(join(dir, file), 'utf8'));
    w.nodes.find(n => n.name === 'Configuração').parameters.assignments.assignments[0].value = origin;
    return w;
  }));
  await writeFile(join(temp, 'workflows.json'), JSON.stringify(workflows));
  await run(['import:workflow', '--input=' + join(temp, 'workflows.json')]);
  console.log('Executando agente: texto direto, duas chamadas de ferramenta e falha da IA…');
  const inboxOutput = await run(['execute', '--id=todentro-inbox']);
  await writeFile(join(temp, 'inbox-result.json'), inboxOutput);
  console.log('Registro local fictício:', temp);
  assert.equal(calls.filter(c => c.url.endsWith('/finish')).length, 4);
  assert.deepEqual(calls.filter(c => c.body.action === 'LIST').map(c => c.url), ['/api/integrations/whatsapp/inbox/job2/action', '/api/integrations/whatsapp/inbox/job3/action']);
  assert(calls.some(c => c.url.includes('job4') && c.body.action === 'HELP'));
  assert(modelCalls > 0);
  calls.length = 0; mode = 'empty';
  console.log('Executando caixa de entrada vazia…');
  await run(['execute', '--id=todentro-inbox']);
  assert.equal(calls.length, 1);
  mode = 'outbox';
  console.log('Executando envios: aceito, rejeitado, transporte desconhecido e envio cancelado…');
  await run(['execute', '--id=todentro-outbox']);
  assert.deepEqual(receipts.map(r => r.state), ['ACCEPTED', 'FAILED', 'UNKNOWN']);
  assert.equal(receipts[0].providerId, 'wamid.fixture');
  assert.equal(receipts[1].errorCode, '131026');
  assert.equal(calls.filter(c => c.url.startsWith('/meta/')).length, 3);
  console.log('Fluxos n8n aprovados: contexto individual correto e nenhum reenvio após falha de transporte.');
} finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
