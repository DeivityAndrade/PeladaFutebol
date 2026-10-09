import { expect, Page, test } from '@playwright/test';

test.use({
  permissions: ['microphone'],
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
});
const owner = { id: 'agent-owner', name: 'Pessoa de teste', email: 'agent@example.invalid' };
const club = {
  id: 'agent-club',
  name: 'Grupo do Corrêa',
  ownerId: owner.id,
  description: '',
  invite: 'invite',
  memberCount: 14,
  timeZone: 'America/Sao_Paulo',
  demo: false,
  barbecueFrequency: 'NONE',
  barbecueSeriesActive: false,
  monthlyAmountCents: null,
  billingDueDay: 10,
  occasionalAmountCents: null,
  pixInstructions: '',
};
const proposal = {
  timeZone: club.timeZone,
  draft: {
    title: 'Pelada de sexta',
    location: 'Gools',
    date: '2026-10-16',
    time: '20:00:00',
    teamCount: 2,
    teamSize: 7,
    recurring: false,
    recurrenceEndsOn: null,
    chargeOccasional: false,
    occasionalAmountCents: null,
  },
};
function reply(version = 1) {
  return {
    conversationId: 'conversation',
    version,
    message: 'Partida pronta. Confira para eu criar.',
    clubId: club.id,
    games: [],
    action: {
      id: 'action-' + version,
      type: 'CREATE',
      clubId: club.id,
      label: 'Criar jogo',
      summary: 'Criar no Grupo do Corrêa.',
      proposal,
    },
    changedGameId: null as string | null,
  };
}
async function setup(page: Page, options: { audio?: boolean; member?: boolean } = {}) {
  await page.route('**/api/auth/me', (r) =>
    r.fulfill({ json: options.member ? { ...owner, id: 'member' } : owner }),
  );
  await page.route('**/api/auth/csrf', (r) => r.fulfill({ json: { token: 'agent-csrf' } }));
  await page.route('**/api/visits', (r) => r.fulfill({ status: 204 }));
  await page.route('**/api/groups', (r) => r.fulfill({ json: [club] }));
  for (const suffix of ['games', 'friendlies', 'barbecues'])
    await page.route('**/api/groups/' + club.id + '/' + suffix, (r) => r.fulfill({ json: [] }));
  await page.route('**/api/assistant/agent', (r) =>
    r.fulfill({ json: { available: true, audioAvailable: options.audio ?? true } }),
  );
  await page.route('**/api/assistant/conversations', (r) => r.fulfill({ json: reply() }));
  await page.route('**/api/assistant/audio', (r) =>
    r.fulfill({ json: { text: 'Marque sexta às 20h no Gools, no grupo do Corrêa.' } }),
  );
  await page.goto('/#group/' + club.id);
  await page.getByRole('button', { name: 'Abrir agente por áudio ou mensagem' }).click();
}
for (const mobile of [false, true])
  test(
    'áudio enviado continua automaticamente no agente — ' + (mobile ? 'celular' : 'desktop'),
    async ({ page }) => {
      await page.setViewportSize(
        mobile ? { width: 390, height: 844 } : { width: 1365, height: 1000 },
      );
      let confirms = 0;
      let messages = 0;
      await setup(page);
      await page.route('**/api/assistant/conversations/conversation/confirm', (r) => {
        confirms++;
        return r.fulfill({
          json: {
            ...reply(2),
            action: null,
            message: 'Jogo criado.',
            changedGameId: 'created-game',
          },
        });
      });
      page.on('request', (r) => {
        if (r.method() === 'POST' && r.url().endsWith('/assistant/conversations')) messages++;
      });
      await page.getByRole('button', { name: 'Gravar pedido', exact: true }).click();
      await expect(page.getByRole('status').filter({ hasText: 'Gravando' })).toContainText('1s', {
        timeout: 6000,
      });
      const upload = page.waitForRequest(
        (r) => r.url().endsWith('/assistant/audio') && r.method() === 'POST',
      );
      await page.getByRole('button', { name: 'Enviar áudio', exact: true }).click();
      expect((await upload).headers()['x-csrf-token']).toBe('agent-csrf');
      await expect(page.getByRole('log')).toContainText('Marque sexta às 20h no Gools');
      await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toBeVisible();
      await expect(
        page.getByRole('button', { name: 'Transcrever áudio', exact: true }),
      ).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Revisar detalhes', exact: true })).toHaveCount(
        0,
      );
      expect(messages).toBe(1);
      expect(confirms).toBe(0);
      await page.screenshot({
        path: '../docs/screenshots/agente-conversa-' + (mobile ? 'celular' : 'desktop') + '.png',
      });
      await page.getByRole('button', { name: 'Criar jogo', exact: true }).click();
      await expect(page.getByRole('log')).toContainText('Jogo criado');
      await expect(page.getByRole('button', { name: 'Abrir partida' })).toBeVisible();
      expect(confirms).toBe(1);
    },
  );
test('perguntas e ajustes continuam a conversa sem formulário', async ({ page }) => {
  await setup(page, { audio: false });
  const bodies: any[] = [];
  await page.route('**/api/assistant/conversations', (r) => {
    bodies.push(r.request().postDataJSON());
    const p = reply(bodies.length);
    if (bodies.length === 1) {
      p.action = null as any;
      p.message = 'Qual o horário?';
    }
    return r.fulfill({ json: p });
  });
  await page.getByLabel('Mensagem para o agente').fill('Marque sexta no Gools');
  await page.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect(page.getByRole('log')).toContainText('Qual o horário?');
  await page.getByLabel('Mensagem para o agente').fill('Às 20h');
  await page.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toBeVisible();
  expect(bodies[1]).toMatchObject({
    message: 'Às 20h',
    conversationId: 'conversation',
    version: 1,
  });
  await page.getByLabel('Mensagem para o agente').fill('Troque para 21h');
  await page.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect(page.getByRole('log')).toContainText('Troque para 21h');
  expect(bodies[2].version).toBe(2);
});
test('membro consulta e altera presença com confirmação', async ({ page }) => {
  await setup(page, { audio: false, member: true });
  await page.route('**/api/assistant/conversations', (r) =>
    r.fulfill({
      json: {
        ...reply(),
        message: 'Confira sua partida.',
        action: {
          id: 'presence',
          type: 'ATTEND',
          clubId: club.id,
          label: 'Confirmar minha presença',
          summary: 'Gools · 16/10/2026 às 20h',
          proposal: null,
        },
      },
    }),
  );
  await page.getByLabel('Mensagem para o agente').fill('Eu vou jogar sexta');
  await page.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect(page.getByRole('button', { name: 'Confirmar minha presença' })).toBeVisible();
  await page.route('**/api/assistant/conversations/conversation/confirm', (r) =>
    r.fulfill({
      json: {
        ...reply(2),
        action: null,
        message: 'Sua presença foi confirmada.',
        changedGameId: 'game',
      },
    }),
  );
  await page.getByRole('button', { name: 'Confirmar minha presença' }).click();
  await expect(page.getByRole('log')).toContainText('Sua presença foi confirmada');
});
test('falha do agente permite repetir sem reenviar áudio', async ({ page }) => {
  await setup(page);
  let calls = 0;
  await page.route('**/api/assistant/conversations', (r) =>
    ++calls === 1
      ? r.fulfill({ status: 503, json: { message: 'Não consegui entender agora.' } })
      : r.fulfill({ json: reply() }),
  );
  await page.getByLabel('Mensagem para o agente').fill('Jogo sexta às 20h');
  await page.getByRole('button', { name: 'Enviar mensagem' }).click();
  await expect(page.getByRole('alert')).toContainText('Não consegui entender');
  await page.getByRole('button', { name: 'Tentar pedido novamente' }).click();
  await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toBeVisible();
});
test('fechar durante gravação libera microfone sem envio e restaura foco', async ({ page }) => {
  await page.addInitScript(() => {
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = async (c) => {
      const s = await original(c);
      (window as any).agentTracks = s.getTracks();
      return s;
    };
  });
  let uploads = 0;
  await setup(page);
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().endsWith('/assistant/audio')) uploads++;
  });
  await page.getByRole('button', { name: 'Gravar pedido', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Enviar áudio', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect
    .poll(() =>
      page.evaluate(() =>
        (window as any).agentTracks.every((t: MediaStreamTrack) => t.readyState === 'ended'),
      ),
    )
    .toBe(true);
  await expect(
    page.getByRole('button', { name: 'Abrir agente por áudio ou mensagem' }),
  ).toBeFocused();
  expect(uploads).toBe(0);
});
