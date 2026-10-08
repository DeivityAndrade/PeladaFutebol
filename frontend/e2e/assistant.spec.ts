import { expect, Page, test } from '@playwright/test';

const owner = { id: 'owner', name: 'Pessoa de teste', email: 'owner@assistant.invalid' };
const club = {
  id: 'assistant-club',
  name: 'Pelada entre amigos',
  description: 'Futebol de toda semana.',
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
const draft = {
  title: 'Pelada de sábado',
  location: 'Arena da Vila',
  date: '2026-10-10',
  time: '19:00:00',
  teamCount: 2,
  teamSize: 7,
  recurring: false,
  recurrenceEndsOn: null,
  chargeOccasional: false,
  occasionalAmountCents: null,
};
function proposal() {
  return {
    id: 'proposal',
    version: 0,
    timeZone: club.timeZone,
    expiresAt: '2026-10-08T22:00:00Z',
    draft: { ...draft },
    questions: [] as string[],
    ready: true,
  };
}

async function setup(
  page: Page,
  options: { available?: boolean; member?: boolean; missing?: boolean } = {},
) {
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({ json: options.member ? { ...owner, id: 'member' } : owner }),
  );
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'test-csrf' } }));
  await page.route('**/api/visits', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/groups', (route) => route.fulfill({ json: [club] }));
  for (const suffix of ['games', 'friendlies', 'barbecues'])
    await page.route(`**/api/groups/${club.id}/${suffix}`, (route) => route.fulfill({ json: [] }));
  await page.route(`**/api/groups/${club.id}/assistant`, (route) =>
    route.fulfill({ json: { available: options.available ?? true } }),
  );
  await page.route(`**/api/groups/${club.id}/assistant/proposals`, (route) => {
    const p = proposal();
    if (options.missing) {
      p.draft.location = '';
      p.questions = ['Em qual local será o jogo?'];
      p.ready = false;
    }
    return route.fulfill({ json: p });
  });
  await page.route('**/api/assistant/proposals/proposal', (route) => {
    const input = route.request().postDataJSON();
    return route.fulfill({
      json: { ...proposal(), version: input.version + 1, draft: input.draft },
    });
  });
  await page.goto(`/#group/${club.id}`);
}

for (const mobile of [false, true]) {
  test(`assistente revisa campos e mantém confirmação separada — ${mobile ? 'celular' : 'desktop'}`, async ({
    page,
  }) => {
    await page.setViewportSize(
      mobile ? { width: 390, height: 844 } : { width: 1365, height: 1000 },
    );
    await setup(page);
    await page.getByRole('button', { name: 'Marcar com assistente', exact: true }).click();
    await expect(page.getByLabel('Como será a pelada?')).toBeFocused();
    await page
      .getByLabel('Como será a pelada?')
      .fill('Sábado às 19h na Arena da Vila, 2 times de 7.');
    await page.getByRole('button', { name: 'Preparar jogo', exact: true }).click();
    await expect(page.getByLabel('Local e quadra', { exact: true })).toHaveValue('Arena da Vila');
    await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toHaveCount(0);
    await page.getByLabel('Local e quadra', { exact: true }).fill('Arena da Vila · Quadra 2');
    await page.getByRole('button', { name: 'Revisar detalhes', exact: true }).click();
    await expect(page.locator('.assistant-summary')).toContainText('Arena da Vila · Quadra 2');
    await expect(page.locator('.assistant-summary')).toContainText('10/10/2026 às 19:00');
    await expect(page.locator('.assistant-summary')).toContainText('14 vagas');
    await expect(page.locator('#assistant-summary')).toBeFocused();
    await page.screenshot({
      path: `../docs/screenshots/assistente-${mobile ? 'celular' : 'desktop'}-revisao.png`,
    });
    let confirms = 0;
    await page.route('**/api/assistant/proposals/proposal/confirm', (route) => {
      confirms++;
      expect(route.request().postDataJSON()).toEqual({ version: 1 });
      return route.fulfill({
        status: 503,
        json: { message: 'Falha de teste. Você pode tentar novamente.' },
      });
    });
    await page.getByRole('button', { name: 'Criar jogo', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Falha de teste');
    await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Criar jogo', exact: true }).click();
    await expect.poll(() => confirms).toBe(2);
    await page.getByRole('button', { name: 'Editar detalhes', exact: true }).click();
    await expect(page.getByLabel('Local e quadra', { exact: true })).toHaveValue(
      'Arena da Vila · Quadra 2',
    );
    await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });
}
test('detalhe ausente pode ser preenchido sem novo pedido à IA', async ({ page }) => {
  await setup(page, { missing: true });
  await page.getByRole('button', { name: 'Marcar com assistente', exact: true }).click();
  await page.getByLabel('Como será a pelada?').fill('Sábado às 19h.');
  await page.getByRole('button', { name: 'Preparar jogo', exact: true }).click();
  await expect(page.locator('#assistant-questions')).toContainText('Em qual local');
  await page.getByLabel('Local e quadra', { exact: true }).fill('Quadra do bairro');
  await page.getByRole('button', { name: 'Revisar detalhes', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toBeVisible();
});
test('confirmação concluída abre o jogo criado e mantém foco dentro do diálogo', async ({
  page,
}) => {
  await setup(page);
  const detail = {
    club,
    game: {
      id: 'created-game',
      clubId: club.id,
      title: draft.title,
      location: draft.location,
      startsAt: '2026-10-10T22:00:00Z',
      teamCount: 2,
      teamSize: 7,
      confirmed: 0,
      waiting: 0,
      chargeOccasional: false,
      occasionalAmountCents: null,
      cancelled: false,
      editable: true,
      teamEditable: true,
      liveEnabled: true,
      matchStatus: 'SCHEDULED',
      matchStartedAt: null,
      matchEndedAt: null,
      matchDurationSeconds: null,
      correctionOpen: false,
      serverNow: '2026-10-08T19:00:00Z',
      recurring: false,
      occurrenceIndex: 1,
      seriesException: false,
    },
    attendees: [],
    teams: [
      {
        id: 'team-1',
        name: 'Time 1',
        color: '#d8f36a',
        captainId: null,
        formation: '2-2',
        version: 0,
      },
      {
        id: 'team-2',
        name: 'Time 2',
        color: '#8d9dff',
        captainId: null,
        formation: '2-2',
        version: 0,
      },
    ],
    score: [],
    goals: [],
    ratings: [],
    myRatings: [],
    ratingsVisibleAt: null,
    goalkeeperReservations: [],
    viewerGuest: false,
  };
  await page.route('**/api/assistant/proposals/proposal/confirm', (route) =>
    route.fulfill({ json: detail }),
  );
  await page.route('**/api/games/created-game', (route) => route.fulfill({ json: detail }));
  await page.getByRole('button', { name: 'Marcar com assistente', exact: true }).click();
  await page.getByLabel('Como será a pelada?').fill('Sábado às 19h na Arena da Vila.');
  await page.getByRole('button', { name: 'Preparar jogo', exact: true }).click();
  await page.getByRole('button', { name: 'Revisar detalhes', exact: true }).click();
  await expect(page.locator('#assistant-summary')).toBeFocused();
  await page.getByRole('button', { name: 'Criar jogo', exact: true }).focus();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Fechar', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Criar jogo', exact: true }).click();
  await expect(page).toHaveURL(/#game\/created-game$/);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('Pelada marcada!', { exact: true })).toBeVisible();
});
test('revisão permanece legível em celular no modo noturno e com movimento reduzido', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await setup(page);
  await page.getByRole('button', { name: 'Modo noturno', exact: true }).click();
  await page.getByRole('button', { name: 'Marcar com assistente', exact: true }).click();
  await page.getByLabel('Como será a pelada?').fill('Sábado às 19h na Arena da Vila.');
  await page.getByRole('button', { name: 'Preparar jogo', exact: true }).click();
  await page.getByRole('button', { name: 'Revisar detalhes', exact: true }).click();
  await expect(page.locator('#assistant-summary')).toBeFocused();
  await expect(page.getByRole('button', { name: 'Criar jogo', exact: true })).toBeVisible();
  await page.screenshot({ path: '../docs/screenshots/assistente-celular-noturno-revisao.png' });
});
test('indisponibilidade oferece formulário e membro não vê assistente', async ({ page }) => {
  await setup(page, { available: false });
  await page.getByRole('button', { name: 'Marcar com assistente', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('ainda não está disponível');
  await page.getByRole('button', { name: 'Usar formulário', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Marque a próxima.' })).toBeVisible();
  await page.keyboard.press('Escape');
  await setup(page, { member: true });
  await page.reload();
  await expect(
    page.getByRole('button', { name: 'Marcar com assistente', exact: true }),
  ).toHaveCount(0);
});
