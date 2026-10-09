import { expect, Page, test } from '@playwright/test';

test.use({
  permissions: ['microphone'],
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
});
const owner = { id: 'audio-owner', name: 'Pessoa de teste', email: 'audio@example.invalid' };
const club = {
  id: 'audio-club',
  name: 'Pelada entre amigos',
  description: '',
  ownerId: owner.id,
  memberCount: 14,
  invite: 'invite',
  timeZone: 'America/Sao_Paulo',
  demo: false,
  barbecueFrequency: 'NONE',
  barbecueSeriesActive: false,
  monthlyAmountCents: null,
  billingDueDay: 10,
  occasionalAmountCents: null,
  pixInstructions: '',
};
async function setup(page: Page, available = true) {
  await page.route('**/api/auth/me', (r) => r.fulfill({ json: owner }));
  await page.route('**/api/auth/csrf', (r) => r.fulfill({ json: { token: 'audio-csrf' } }));
  await page.route('**/api/visits', (r) => r.fulfill({ status: 204 }));
  await page.route('**/api/groups', (r) => r.fulfill({ json: [club] }));
  for (const suffix of ['games', 'friendlies', 'barbecues'])
    await page.route(`**/api/groups/${club.id}/${suffix}`, (r) => r.fulfill({ json: [] }));
  await page.route(`**/api/groups/${club.id}/assistant`, (r) =>
    r.fulfill({ json: { available: true } }),
  );
  await page.route(`**/api/groups/${club.id}/assistant/audio`, (r) =>
    r.request().method() === 'GET'
      ? r.fulfill({ json: { available } })
      : r.fulfill({ json: { text: 'Sexta às 19h30 no Gools, 2 times de 7.' } }),
  );
  await page.goto(`/#group/${club.id}`);
  await page.getByRole('button', { name: 'Marcar com assistente', exact: true }).click();
}
for (const mobile of [false, true])
  test(`gravação é revisada antes de preparar ou criar — ${mobile ? 'celular' : 'desktop'}`, async ({
    page,
  }) => {
    await page.setViewportSize(
      mobile ? { width: 390, height: 844 } : { width: 1365, height: 1000 },
    );
    let proposals = 0;
    page.on('request', (r) => {
      if (r.url().endsWith('/proposals')) proposals++;
    });
    await setup(page);
    await page.getByRole('button', { name: 'Gravar pedido', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Gravando' })).toContainText('1s', {
      timeout: 6000,
    });
    await page.getByRole('button', { name: 'Parar gravação' }).click();
    await expect(page.getByLabel('Ouvir seu pedido gravado')).toBeVisible();
    const request = page.waitForRequest(
      (r) => r.url().endsWith('/assistant/audio') && r.method() === 'POST',
    );
    await page.getByRole('button', { name: 'Transcrever áudio', exact: true }).click();
    expect((await request).headers()['x-csrf-token']).toBe('audio-csrf');
    await expect(page.getByLabel('Como será a pelada?')).toHaveValue(
      'Sexta às 19h30 no Gools, 2 times de 7.',
    );
    await expect(page.getByLabel('Como será a pelada?')).toBeFocused();
    expect(proposals).toBe(0);
    await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toHaveCount(0);
    await page.getByLabel('Como será a pelada?').fill('Sexta às 20h no Gools, 2 times de 7.');
    await page.screenshot({
      path: `../docs/screenshots/assistente-audio-${mobile ? 'celular' : 'desktop'}.png`,
    });
  });
test('áudio pode ser descartado sem upload e fechar libera o microfone', async ({ page }) => {
  await page.addInitScript(() => {
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = async (constraints) => {
      const stream = await original(constraints);
      (window as any).audioTestTracks = stream.getTracks();
      return stream;
    };
  });
  let uploads = 0;
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().endsWith('/assistant/audio')) uploads++;
  });
  await setup(page);
  await page.getByRole('button', { name: 'Gravar pedido', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Parar gravação' })).toBeVisible();
  await page.getByRole('button', { name: 'Fechar', exact: true }).click();
  await expect
    .poll(() =>
      page.evaluate(() =>
        (window as any).audioTestTracks.every((t: MediaStreamTrack) => t.readyState === 'ended'),
      ),
    )
    .toBe(true);
  expect(uploads).toBe(0);
});
test('falha de transcrição preserva áudio e permite tentar novamente', async ({ page }) => {
  await setup(page);
  await page.getByRole('button', { name: 'Gravar pedido', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Gravando' })).toContainText('1s', {
    timeout: 6000,
  });
  await page.getByRole('button', { name: 'Parar gravação' }).click();
  await page.route(`**/api/groups/${club.id}/assistant/audio`, (r) =>
    r.fulfill({ status: 503, json: { message: 'Não consegui transcrever agora.' } }),
  );
  await page.getByRole('button', { name: 'Transcrever áudio', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Não consegui transcrever');
  await expect(page.getByLabel('Ouvir seu pedido gravado')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Transcrever áudio', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Descartar áudio' }).click();
  await expect(page.getByLabel('Ouvir seu pedido gravado')).toHaveCount(0);
});
test('texto continua disponível com áudio desabilitado', async ({ page }) => {
  await setup(page, false);
  await expect(page.getByRole('button', { name: 'Gravar pedido', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Como será a pelada?')).toBeEnabled();
});
test('microfone negado preserva texto e não envia gravação', async ({ page }) => {
  await page.addInitScript(() => {
    navigator.mediaDevices.getUserMedia = async () => {
      throw new DOMException('Denied', 'NotAllowedError');
    };
  });
  await setup(page);
  await page.getByLabel('Como será a pelada?').fill('Jogo sexta às 19h');
  await page.getByRole('button', { name: 'Gravar pedido', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Libere a permissão');
  await expect(page.getByLabel('Como será a pelada?')).toHaveValue('Jogo sexta às 19h');
});
