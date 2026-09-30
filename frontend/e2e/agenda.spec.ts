import { expect, Page, test } from '@playwright/test';
test.use({ timezoneId: 'America/Sao_Paulo' });

const owner = { id: 'organizador', name: 'Rafael Silva', email: 'rafael@example.com' };
const club = {
  id: 'quinta',
  name: 'Resenha de quinta',
  description: 'Futebol entre amigos.',
  ownerId: owner.id,
  memberCount: 14,
  demo: false,
  invite: 'quinta-convite',
  barbecueFrequency: 'NONE',
  barbecueSeriesActive: false,
  monthlyAmountCents: null,
  billingDueDay: 10,
  occasionalAmountCents: null,
  pixInstructions: '',
  timeZone: 'America/Sao_Paulo',
};
const secondClub = {
  ...club,
  id: 'sabado',
  name: 'Boleiros do sábado',
  ownerId: 'outro-organizador',
  timeZone: 'America/Manaus',
};
function game(id: string, day: string, title: string, options: Record<string, unknown> = {}) {
  return {
    id,
    clubId: club.id,
    title,
    location: 'Arena da Vila · Quadra 02',
    startsAt: `2026-10-${day}T22:00:00Z`,
    teamCount: 2,
    teamSize: 7,
    confirmed: 12,
    waiting: 2,
    cancelled: false,
    editable: true,
    teamEditable: true,
    liveEnabled: true,
    matchStatus: 'SCHEDULED',
    matchStartedAt: null,
    matchEndedAt: null,
    matchDurationSeconds: null,
    correctionOpen: false,
    serverNow: '2026-10-06T15:00:00Z',
    recurring: true,
    occurrenceIndex: 1,
    seriesException: false,
    chargeOccasional: false,
    occasionalAmountCents: null,
    ...options,
  };
}
const games = [
  game('passado', '01', 'Quinta que passou', { matchStatus: 'FINISHED' }),
  game('proximo', '08', 'Quinta na Arena da Vila'),
  game('noite', '08', 'Segundo horário', { startsAt: '2026-10-09T00:00:00Z' }),
  game('extra', '08', 'Treino extra', { startsAt: '2026-10-09T01:00:00Z', recurring: false }),
  game('cancelado', '15', 'Quinta cancelada', { cancelled: true, matchStatus: 'CANCELLED' }),
  game('ajustado', '22', 'Quinta em outra quadra', { seriesException: true }),
  game('fim-mes', '29', 'Última quinta do mês'),
];
const otherGames = [
  game('sabado', '10', 'Sábado com os boleiros', {
    clubId: secondClub.id,
    startsAt: '2026-10-10T12:00:00Z',
    recurring: false,
  }),
];
const friendly = {
  id: 'amistoso',
  clubId: club.id,
  opponentName: 'União FC',
  startsAt: '2026-10-09T22:30:00Z',
  location: 'Campo do Parque',
  status: 'SCHEDULED',
};

async function setup(
  page: Page,
  options: { user?: typeof owner; clubs?: (typeof club)[]; games?: typeof games } = {},
) {
  await page.clock.install({ time: new Date('2026-10-06T15:00:00Z') });
  await page.route('**/api/auth/me', (route) => route.fulfill({ json: options.user || owner }));
  await page.route('**/api/groups', (route) =>
    route.fulfill({ json: options.clubs ?? [club, secondClub] }),
  );
  await page.route(`**/api/groups/${club.id}/games`, (route) =>
    route.fulfill({ json: options.games || games }),
  );
  await page.route(`**/api/groups/${secondClub.id}/games`, (route) =>
    route.fulfill({ json: otherGames }),
  );
  await page.route('**/api/groups/*/friendlies', (route) =>
    route.fulfill({ json: route.request().url().includes('/quinta/') ? [friendly] : [] }),
  );
}

test('agenda mensal agrega jogos e amistosos, filtra grupos e abre a pelada real', async ({
  page,
}) => {
  await setup(page);
  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.goto('/#groups');
  await page.locator('.top-nav').getByRole('link', { name: 'Agenda de jogos' }).click();
  await expect(page).toHaveURL(/#agenda$/);
  await expect(page.locator('#calendar-month')).toHaveText('outubro de 2026');
  await expect(page.locator('.calendar-day')).toHaveCount(35);
  await expect(page.locator('.calendar-toolbar')).toContainText('8 jogos no mês');
  await expect(page.locator('.selected .more-events')).toHaveText('+1 jogo');
  await expect(page.locator('.fixture-detail')).toHaveCount(3);
  await expect(page.locator('.fixture-detail').first()).toContainText('12/14 confirmados');
  await expect(page.locator('.fixture-detail').first()).toContainText('Série semanal');
  await expect(page.locator('.selected')).toHaveAttribute('id', 'agenda-day-2026-10-08');
  await page.locator('#agenda-day-2026-10-09').click();
  await expect(page.locator('.selected')).toHaveAttribute('id', 'agenda-day-2026-10-09');
  await expect(page.locator('.fixture-detail')).toHaveCount(1);
  await expect(page.locator('.fixture-detail')).toContainText('Amistoso · União FC');
  await page.locator('#agenda-day-2026-10-15').click();
  await expect(page.locator('.fixture-status')).toHaveText('Cancelado');
  await page.locator('#agenda-day-2026-10-22').click();
  await expect(page.locator('.fixture-series')).toContainText('edição ajustada');
  await page.getByRole('combobox', { name: 'Filtrar por grupo' }).selectOption(secondClub.id);
  await expect(page.locator('.calendar-toolbar')).toContainText('1 jogo no mês');
  await expect(page.getByRole('button', { name: 'Marcar pelada', exact: true })).toHaveCount(0);
  await page.locator('#agenda-day-2026-10-10').click();
  await expect(page.locator('.fixture-time')).toHaveText('08:00');
  await expect(page.locator('.zone-note')).toContainText('America/Manaus');
  await page.getByRole('combobox', { name: 'Filtrar por grupo' }).selectOption(club.id);
  await page.getByRole('button', { name: 'Mostrar próximo jogo no calendário' }).click();
  await page.route('**/api/games/proximo', (route) =>
    route.fulfill({
      json: {
        game: games[1],
        club,
        attendees: [],
        teams: [],
        score: [],
        goals: [],
        ratings: [],
        myRatings: [],
        ratingsVisibleAt: null,
      },
    }),
  );
  await page
    .locator('.fixture-detail')
    .first()
    .getByRole('button', { name: 'Abrir pelada' })
    .click();
  await expect(page).toHaveURL(/#game\/proximo$/);
  await expect(
    page.getByRole('heading', { name: 'Quinta na Arena da Vila', exact: true }),
  ).toBeVisible();
});

test('navegação por teclado cruza semanas, meses e anos sem perder foco', async ({ page }) => {
  await setup(page);
  await page.goto('/#agenda');
  await expect(page.locator('.selected')).toHaveAttribute('id', 'agenda-day-2026-10-08');
  await page.locator('.selected').focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('#agenda-day-2026-10-09')).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('#agenda-day-2026-10-16')).toBeFocused();
  await page.keyboard.press('Home');
  await expect(page.locator('#agenda-day-2026-10-12')).toBeFocused();
  await page.keyboard.press('End');
  await expect(page.locator('#agenda-day-2026-10-18')).toBeFocused();
  await page.keyboard.press('PageDown');
  await expect(page.locator('#agenda-day-2026-11-18')).toBeFocused();
  await expect(page.locator('.calendar-day')).toHaveCount(42);
  await page.keyboard.press('PageUp');
  await expect(page.locator('#agenda-day-2026-10-18')).toBeFocused();
  await page.getByRole('button', { name: 'Próximo mês', exact: true }).click({ clickCount: 3 });
  await expect(page.locator('#calendar-month')).toHaveText('janeiro de 2027');
  await page.getByRole('button', { name: 'Hoje', exact: true }).click();
  await expect(page.locator('.selected')).toHaveAttribute('id', 'agenda-day-2026-10-06');
  await expect(page.locator('.free-day')).toBeVisible();
});

test('marcar jogo reaproveita formulário e data selecionada sem incluir participantes', async ({
  page,
}) => {
  await setup(page);
  await page.goto('/#agenda');
  await page.locator('#agenda-day-2026-10-22').click();
  await page.getByRole('button', { name: 'Marcar neste dia' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByLabel('Data', { exact: true })).toHaveValue('2026-10-22');
  await expect(page.getByText('Horário do grupo:')).toContainText('Brasília');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('combobox', { name: 'Filtrar por grupo' }).selectOption(secondClub.id);
  await expect(page.getByRole('button', { name: 'Marcar neste dia' })).toHaveCount(0);
});

test('datas usam o fuso escolhido, inclusive jogos perto da meia-noite e ano bissexto', async ({
  page,
}) => {
  await setup(page, {
    games: [
      game('virada', '01', 'Jogo perto da meia-noite', {
        startsAt: '2028-03-01T02:30:00Z',
        serverNow: '2028-02-20T15:00:00Z',
      }),
    ],
  });
  await page.goto('/#agenda');
  await page.getByRole('combobox', { name: 'Filtrar por grupo' }).selectOption(club.id);
  await expect(page.locator('#calendar-month')).toHaveText('fevereiro de 2028');
  await page.locator('#agenda-day-2028-02-29').click();
  await expect(page.locator('.fixture-detail')).toContainText('Jogo perto da meia-noite');
  await expect(page.locator('.fixture-time')).toHaveText('23:30');
  await page.getByRole('button', { name: 'Próximo mês', exact: true }).click();
  await expect(page.locator('.selected')).toHaveAttribute('id', 'agenda-day-2028-03-29');
});

test('falha parcial não esconde outros grupos e pode ser recuperada', async ({ page }) => {
  await setup(page);
  let failed = true;
  await page.route(`**/api/groups/${secondClub.id}/games`, (route) =>
    route.fulfill(
      failed ? { status: 503, json: { message: 'Indisponível' } } : { json: otherGames },
    ),
  );
  await page.goto('/#agenda');
  await expect(page.getByRole('alert')).toContainText('Boleiros do sábado');
  await expect(page.locator('.fixture-detail')).toHaveCount(3);
  failed = false;
  await page.getByRole('button', { name: 'Tentar novamente', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.locator('.calendar-toolbar')).toContainText('8 jogos no mês');
});

test('amistoso compartilhado por dois grupos aparece uma vez e abre o grupo filtrado', async ({
  page,
}) => {
  await setup(page);
  await page.route(`**/api/groups/${secondClub.id}/friendlies`, (route) =>
    route.fulfill({ json: [{ ...friendly, clubId: secondClub.id, opponentName: club.name }] }),
  );
  await page.goto('/#agenda');
  await expect(page.locator('.calendar-toolbar')).toContainText('8 jogos no mês');
  await page.locator('#agenda-day-2026-10-09').click();
  await expect(page.locator('.fixture-detail')).toHaveCount(1);
  await page.getByRole('combobox', { name: 'Filtrar por grupo' }).selectOption(secondClub.id);
  await page.locator('#agenda-day-2026-10-09').click();
  await expect(page.locator('.fixture-detail')).toContainText('Amistoso · Resenha de quinta');
  await expect(page.locator('.fixture-group')).toHaveText(secondClub.name);
});

test('nomes e locais longos cabem em celular estreito e tablet', async ({ page }) => {
  const longClub = {
    ...club,
    name: 'Grupo dos amigos que se encontram na quadra do bairro para jogar toda quinta-feira',
  };
  await setup(page, {
    clubs: [longClub],
    games: [
      game(
        'longo',
        '08',
        'Pelada de confraternização dos amigos da quinta com os convidados dos outros grupos do bairro',
        {
          location:
            'Quadra localizada no complexo esportivo do bairro, entrada pela rua principal, ao lado do estacionamento dos visitantes',
        },
      ),
    ],
  });
  for (const width of [320, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/#agenda');
    await page.reload();
    await expect(page.locator('.fixture-detail')).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy();
    await expect(page.locator('.fixture-open')).toBeVisible();
  }
});

test('agenda sem grupos orienta o jogador e visitante precisa entrar', async ({ page }) => {
  await setup(page, { clubs: [] });
  await page.goto('/#agenda');
  await expect(
    page.getByRole('heading', { name: 'Sua agenda começa com um grupo.' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Ir para meus grupos' }).click();
  await expect(page).toHaveURL(/#groups$/);
  await page.route('**/api/auth/me', (route) => route.fulfill({ status: 401, body: '{}' }));
  await page.goto('/#agenda');
  await page.reload();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.locator('.calendar-grid')).toHaveCount(0);
});

for (const viewport of [
  { width: 1440, height: 1080, name: 'desktop' },
  { width: 390, height: 844, name: 'celular' },
]) {
  test(`agenda em ${viewport.name}: claro, noturno, nomes longos e detalhes acessíveis`, async ({
    page,
  }, testInfo) => {
    await setup(page);
    await page.setViewportSize(viewport);
    await page.goto('/#agenda');
    await expect(page.locator('.calendar-grid')).toBeVisible();
    for (const theme of ['claro', 'noturno']) {
      if (theme === 'noturno') await page.getByRole('button', { name: 'Modo noturno' }).click();
      await expect(page.locator('#agenda-day-2026-10-08')).toBeVisible();
      await page.screenshot({
        path: testInfo.outputPath(`agenda-${viewport.name}-${theme}.png`),
        fullPage: true,
        animations: 'disabled',
      });
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      ).toBeTruthy();
    }
    if (viewport.name === 'celular') {
      await expect(page.locator('.selected .mobile-game-marker')).toHaveText('3');
      await page.getByRole('button', { name: 'Abrir menu', exact: true }).click();
      await expect(
        page.locator('.drawer-nav').getByRole('link', { name: 'Agenda de jogos' }),
      ).toBeVisible();
      await page.keyboard.press('Escape');
      await page.locator('#agenda-day-2026-10-22').click();
      await expect(page.locator('.fixture-detail')).toContainText('edição ajustada');
      await expect(page.locator('.fixture-open')).toBeVisible();
    }
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  });
}
