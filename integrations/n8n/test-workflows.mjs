import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateMedia } from './audio-guards.mjs';

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
const jobs = [1, 2, 3, 4, 5, 6, 7].map(n => ({ id: `job${n}`, leaseId: `lease${n}`, text: [3,5,6,7].includes(n) ? null : n === 1 ? 'vou' : n === 4 ? 'modelo indisponível' : `consultar próxima partida ${n}`, mediaUrl: [3,5,6,7].includes(n) ? `https://graph.facebook.com/v26.0/${n}` : null, mediaMime: 'audio/ogg', groups: [{id: 'club-test', name: 'Turma', owner: true}], games: [], contextGameId: null, proposalPending: false }));
const server = createServer(async (req, res) => {
  let raw = ''; for await (const data of req) raw += data;
  const body = raw.startsWith('{') ? JSON.parse(raw) : {};
  calls.push({url: req.url, body});
  const answer = (json, status = 200) => { res.writeHead(status, {'Content-Type': 'application/json'}); res.end(JSON.stringify(json)); };
  if (req.url === '/api/integrations/whatsapp/inbox/claim') return answer(mode === 'empty' ? [] : jobs);
  if (req.url.endsWith('/action')) return answer({needsAgent: body.action === 'AUTO' && !req.url.includes('job1'), message: 'Resposta criada pelo sistema'});
  if (req.url.endsWith('/finish')) return answer({});
  if (req.url.startsWith('/media-info/')) {
    const id=req.url.split('/').at(-1);
    return answer({ url: id==='5' ? 'https://attacker.invalid/?token=never' : `https://lookaside.fbsbx.com/whatsapp_business/attachments/?id=${id}`, mime_type:'audio/ogg', file_size:id==='7' ? 3000000 : 64 });
  }
  if (req.url.startsWith('/audio-file/')) { res.writeHead(200,{'Content-Type':'audio/ogg'}); return res.end(Buffer.from('OggSfixture')); }
  if (req.url.endsWith('/audio')) {
    assert(raw.includes('leaseId') && raw.includes('file'));
    return answer(req.url.includes('job6') ? {message:'Falha simulada de transcrição'} : {text:'consultar próxima partida 3'}, req.url.includes('job6') ? 503 : 200);
  }
  if (req.url === '/v1/chat/completions') {
    modelCalls++;
    if (body.messages.some(m => m.role==='user')) assert(!JSON.stringify(body.messages).includes('mediaUrl'));
    if (body.messages.some(m => String(m.content).includes('modelo indisponível'))) return answer({error: {message: 'Falha simulada', type: 'server_error'}}, 503);
    const hasTool = body.messages.some(m => m.role === 'tool');
    const message = hasTool ? {role: 'assistant', content: 'Concluído.'} : {role: 'assistant', content: null, tool_calls: [{ id: `call-${modelCalls}`, type: 'function', function: {name: body.tools[0].function.name, arguments: JSON.stringify({action: 'LIST', clubId: '', gameId: ''})} }]};
    return answer({id: `chat-${modelCalls}`, object: 'chat.completion', created: 1, model: 'gpt-4.1-mini', choices: [{index: 0, message, finish_reason: hasTool ? 'stop' : 'tool_calls'}], usage: {prompt_tokens: 1, completion_tokens: 1, total_tokens: 2}});
  }
  if (req.url === '/api/integrations/whatsapp/outbox/claim') {
    if (mode === 'gupshup') assert.equal(body.provider, 'GUPSHUP');
    return answer([1, 2, 3, 4].map(n => ({id: `send${n}`, leaseId: `sendlease${n}`})));
  }
  if (req.url.endsWith('/dispatch')) {
    if (mode === 'gupshup') assert.equal(body.provider, 'GUPSHUP');
    const fixtureResult = req.url.includes('send1') ? 'success' : req.url.includes('send2') ? 'failure' : 'unknown';
    return answer({send: !req.url.includes('send4'), url: mode === 'gupshup' ? 'https://api.gupshup.io/wa/app/11111111-1111-1111-1111-111111111111/v3/msg' : `${origin}/meta/${fixtureResult}`, payload: {to: 'fake-number', messaging_product: 'whatsapp', type: 'text', text: {body: 'Teste'}, fixtureResult}});
  }
  if (mode === 'gupshup' && req.url.startsWith('/meta/')) {
    assert.equal(req.headers.apikey, 'fake-gupshup');
    assert.equal(req.headers.authorization, undefined);
  }
  if (req.url === '/meta/success') return answer({messages: [{id: 'wamid.fixture'}]});
  if (req.url === '/meta/failure') return answer({error: {code: 131026}}, 400);
  if (req.url === '/meta/unknown') { req.socket.destroy(); return; }
  if (req.url.endsWith('/receipt')) { receipts.push({url: req.url, ...body}); return answer({}); }
  return answer({error: 'Unexpected mock path'}, 404);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
assert.throws(() => validateMedia({url:'https://lookaside.fbsbx.com.attacker.invalid/whatsapp_business/attachments/?id=x',mime_type:'audio/ogg',file_size:64},'audio/ogg'));
assert.throws(() => validateMedia({url:'https://user@lookaside.fbsbx.com/whatsapp_business/attachments/?id=x',mime_type:'audio/ogg',file_size:64},'audio/ogg'));
assert.throws(() => validateMedia({url:'https://lookaside.fbsbx.com/whatsapp_business/attachments/?id=x',mime_type:'audio/ogg',file_size:64},'audio/mp4'));
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
  const credentials = [{id: 'todentro-worker', name: 'Tô Dentro — integração', type: 'httpHeaderAuth', data: {name: 'Authorization', value: 'Bearer fake-worker'}}, {id: 'todentro-meta', name: 'Meta — WhatsApp', type: 'httpHeaderAuth', data: {name: 'Authorization', value: 'Bearer fake-meta'}}, {id: 'todentro-ai', name: 'OpenAI — Tô Dentro', type: 'openAiApi', data: {apiKey: 'fake-model', url: origin + '/v1'}}, {id:'todentro-gupshup', name:'Gupshup — Tô Dentro', type:'httpHeaderAuth', data:{name:'apikey', value:'fake-gupshup'}}];
  await writeFile(join(temp, 'credentials.json'), JSON.stringify(credentials));
  console.log('Importando credenciais fictícias no banco local isolado…');
  await run(['import:credentials', '--input=' + join(temp, 'credentials.json')]);
  const workflows = await Promise.all(['agent.json', 'delivery.json', 'delivery-gupshup.json'].map(async file => {
    const w = JSON.parse(await readFile(join(dir, file), 'utf8'));
    w.nodes.find(n => n.name === 'Configuração').parameters.assignments.assignments[0].value = origin;
    if (w.id==='todentro-inbox') {
      w.nodes.find(n => n.name==='Obter mídia da Meta').parameters.url=`={{ '${origin}/media-info/' + $('Uma mensagem por vez').first(1).json.id.slice(3) }}`;
      // Validate the real strict allowlist first, then substitute only the binary transport for this isolated fixture.
      w.nodes.find(n => n.name==='Validar mídia').parameters.jsCode=`${validateMedia.toString()}\nconst result=validateMedia($json,$('Uma mensagem por vez').first(1).json.mediaMime); return {json:{url:'${origin}/audio-file/'+$json.url.split('id=')[1]}};`;
    }
    if (w.id === 'todentro-outbox-gupshup') {
      const send = w.nodes.find(n => n.name === 'Enviar pela Gupshup');
      // Run the real destination guard before replacing only the transport.
      const guard = send.parameters.url.slice(3, -2);
      send.parameters.url = `={{ (() => { const verified = (${guard}); if (!verified) throw new Error('Invalid fixture'); return '${origin}/meta/' + $json.payload.fixtureResult; })() }}`;
    }
    return w;
  }));
  await writeFile(join(temp, 'workflows.json'), JSON.stringify(workflows));
  await run(['import:workflow', '--input=' + join(temp, 'workflows.json')]);
  console.log('Executando agente: texto direto, duas chamadas de ferramenta e falha da IA…');
  const inboxOutput = await run(['execute', '--id=todentro-inbox']);
  await writeFile(join(temp, 'inbox-result.json'), inboxOutput);
  console.log('Registro local fictício:', temp);
  assert.equal(calls.filter(c => c.url.endsWith('/finish')).length, 7);
  assert.deepEqual(calls.filter(c => c.body.action === 'LIST').map(c => c.url), ['/api/integrations/whatsapp/inbox/job2/action', '/api/integrations/whatsapp/inbox/job3/action']);
  assert(calls.some(c => c.url.includes('job4') && c.body.action === 'HELP'));
  assert(modelCalls > 0);
  assert.equal(calls.filter(c => c.url.endsWith('/audio')).length,2);
  assert.equal(calls.filter(c => c.url.startsWith('/audio-file/')).length,2);
  for(const id of [5,6,7]) assert(calls.some(c=>c.url.includes('job'+id) && c.body.action==='HELP'));
  calls.length = 0; mode = 'empty';
  console.log('Executando caixa de entrada vazia…');
  await run(['execute', '--id=todentro-inbox']);
  assert.equal(calls.length, 1);
  mode = 'outbox';
  console.log('Executando envios: aceito, rejeitado, transporte desconhecido e envio cancelado…');
  await run(['execute', '--id=todentro-outbox']);
  assert.deepEqual(receipts.map(r => r.state), ['ACCEPTED', 'FAILED', 'UNKNOWN']);
  assert.equal(receipts[0].providerId, 'wamid.fixture');
  assert.equal(receipts[0].errorCode, null);
  assert.equal(receipts[1].errorCode, '131026');
  assert.equal(calls.filter(c => c.url.startsWith('/meta/')).length, 3);
  calls.length = 0; receipts.length = 0; mode = 'gupshup';
  console.log('Executando Gupshup: credencial própria, provedor explícito e sem reenvio…');
  await run(['execute', '--id=todentro-outbox-gupshup']);
  assert.deepEqual(receipts.map(r => r.state), ['ACCEPTED', 'FAILED', 'UNKNOWN']);
  assert.equal(receipts[0].errorCode, null);
  assert.equal(receipts[1].errorCode, '131026');
  assert.equal(calls.filter(c => c.url.startsWith('/meta/')).length, 3);
  console.log('Fluxos n8n aprovados: contexto individual correto e nenhum reenvio após falha de transporte.');
} finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
