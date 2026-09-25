import { expect, test } from '@playwright/test';
import path from 'node:path';

const club = {
  id: 'club-visual',
  name: 'Pelada de quinta',
  description: 'Futebol toda semana e churrasco com a galera.',
  ownerId: 'owner',
  invite: 'convite-visual',
  memberCount: 14,
  demo: false,
  barbecueFrequency: 'MONTHLY',
  barbecueSeriesActive: true,
};

const game = {
  id: 'game-visual',
  clubId: club.id,
  title: 'Quinta no campo do bairro',
  location: 'Arena do Parque · Quadra 2',
  startsAt: '2099-10-10T19:30:00Z',
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
  serverNow: '2099-10-01T12:00:00Z',
};

const teams = [
  {
    id: 'verde',
    name: 'Boleiros FC',
    color: '#d8f36a',
    captainId: 'p1',
    formation: '2-2',
    version: 1,
  },
  {
    id: 'azul',
    name: 'Resenha FC',
    color: '#9abbe9',
    captainId: 'p7',
    formation: '1-2-1',
    version: 1,
  },
];

const attendees = Array.from({ length: 14 }, (_, i) => ({
  id: `p${i + 1}`,
  name: [
    'João Pereira',
    'Lucas Almeida',
    'Matheus Souza',
    'Pedro Lima',
    'Gabriel Costa',
    'Rafael Martins',
    'Bruno Oliveira',
    'Felipe Santos',
    'André Ribeiro',
    'Diego Ferreira',
    'Caio Rodrigues',
    'Thiago Gomes',
    'Marcelo Nunes',
    'Vitor Carvalho',
  ][i],
  status: i < 12 ? 'CONFIRMED' : 'WAITING',
  teamId: i < 6 ? 'verde' : i < 12 ? 'azul' : null,
  slot: i < 5 ? i : i === 5 ? null : i < 11 ? i - 6 : null,
}));

const detail = {
  club,
  game,
  attendees,
  teams,
  score: teams.map((team) => ({ teamId: team.id, goals: 0 })),
  goals: [],
  ratings: [],
  myRatings: [],
  ratingsVisibleAt: null,
};

const finishedDetail = {
  ...detail,
  game: {
    ...game,
    matchStatus: 'FINISHED',
    matchStartedAt: '2099-10-10T19:30:00Z',
    matchEndedAt: '2099-10-10T21:00:00Z',
    matchDurationSeconds: 5400,
    editable: false,
    teamEditable: false,
    serverNow: '2099-10-12T12:00:00Z',
  },
  score: [
    { teamId: 'verde', goals: 2 },
    { teamId: 'azul', goals: 1 },
  ],
  goals: [
    {
      id: 'g1',
      teamId: 'verde',
      scorerId: 'p2',
      scorerName: 'Lucas Almeida',
      minute: 12,
      ownGoal: false,
      voided: false,
    },
    {
      id: 'g2',
      teamId: 'azul',
      scorerId: 'p8',
      scorerName: 'Felipe Santos',
      minute: 27,
      ownGoal: false,
      voided: false,
    },
    {
      id: 'g3',
      teamId: 'verde',
      scorerId: 'p4',
      scorerName: 'Pedro Lima',
      minute: 38,
      ownGoal: false,
      voided: false,
    },
  ],
  ratings: attendees
    .slice(0, 12)
    .map((player) => ({ playerId: player.id, average: 4.2, count: 4 })),
  ratingsVisibleAt: '2099-10-11T21:00:00Z',
};

test('aparência, menu, tema e abas funcionam no desktop e celular', async ({ page }) => {
  await page.route('**/api/auth/me', (route) => route.fulfill({ status: 401, body: '{}' }));
  await page.route('**/api/demo', (route) => route.fulfill({ json: detail }));
  await page.route('**/api/demo/finished', (route) => route.fulfill({ json: finishedDetail }));
  await page.setViewportSize({ width: 1440, height: 1450 });
  await page.goto('/#demo');
  await expect(page.getByRole('heading', { name: 'O jogo começa aqui.' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(page.locator('.top-nav')).toBeVisible();
  await expect(page.locator('.sidebar')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Criar minha pelada' })).toBeVisible();
  await expect(page.locator('.jersey svg').first()).toHaveCSS('display', 'block');
  const keeperTop = await page
    .locator('.field-player')
    .first()
    .evaluate((el) => el.getBoundingClientRect().top);
  const forwardTop = await page
    .locator('.field-player')
    .last()
    .evaluate((el) => el.getBoundingClientRect().top);
  expect(keeperTop).toBeLessThan(forwardTop);
  const fieldTop = await page.locator('.field').evaluate((el) => el.getBoundingClientRect().top);
  const rosterTop = await page
    .locator('.roster-panel')
    .evaluate((el) => el.getBoundingClientRect().top);
  expect(Math.abs(fieldTop - rosterTop)).toBeLessThan(12);
  await page.screenshot({
    path: path.resolve('../docs/screenshots/desktop.png'),
    animations: 'disabled',
  });
  await page.getByRole('tab', { name: 'Jogadores' }).click();
  await expect(page.locator('.person-row')).toHaveCount(12);
  await page.screenshot({
    path: path.resolve('../docs/screenshots/jogadores.png'),
    animations: 'disabled',
  });
  await page.getByRole('tab', { name: 'Lista de espera' }).click();
  await expect(page.locator('.person-row')).toHaveCount(2);
  await page.screenshot({
    path: path.resolve('../docs/screenshots/espera.png'),
    animations: 'disabled',
  });
  await page.getByRole('tab', { name: 'Escalações' }).click();
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/acesso.png'),
    animations: 'disabled',
  });
  await page.keyboard.press('Escape');

  await page.getByRole('link', { name: 'Visão geral' }).focus();
  await expect(page.getByRole('link', { name: 'Visão geral' })).toBeFocused();
  await expect(page.getByRole('link', { name: 'Visão geral' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(11, 21, 16)');
  await expect(page.locator('.topbar')).toHaveCSS('background-color', 'rgb(11, 21, 16)');
  await expect(page.locator('.roster-panel')).toHaveCSS('background-color', 'rgb(20, 35, 27)');
  await expect(page.locator('.match-banner')).toHaveCSS('background-color', 'rgb(20, 35, 27)');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0b1510');
  await expect(page.getByRole('button', { name: 'Modo noturno' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0b1510');
  await page.screenshot({
    path: path.resolve('../docs/screenshots/desktop-noturno.png'),
    animations: 'disabled',
  });
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await page.getByLabel('E-mail', { exact: true }).fill('exemplo@pelada.com');
  await page.mouse.click(10, 100);
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCSS('background-color', 'rgb(20, 35, 27)');
  await page.screenshot({
    path: path.resolve('../docs/screenshots/acesso-noturno.png'),
    animations: 'disabled',
  });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: 'Ver partida concluída' }).click();
  await expect(page.locator('.scoreboard')).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/partida-noturno.png'),
    animations: 'disabled',
  });
  await page.getByRole('tab', { name: 'Notas' }).click();
  await expect(page.locator('.rating-row')).toHaveCount(12);
  await page.screenshot({
    path: path.resolve('../docs/screenshots/notas-noturno.png'),
    animations: 'disabled',
  });
  await page.getByRole('button', { name: 'Ver próxima pelada' }).click();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.workspace')).toHaveCSS('margin-left', '0px');
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollHeight))
    .toBeGreaterThan(844);
  await expect(page.getByRole('tab', { name: 'Notas' })).toBeVisible();
  await page.getByRole('tab', { name: 'Notas' }).click();
  await expect(page.getByRole('heading', { name: 'A resenha continua.' })).toBeVisible();
  await page.getByRole('tab', { name: 'Escalações' }).click();
  await expect(page.locator('.roster-panel')).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollHeight))
    .toBeGreaterThan(1200);
  await page.screenshot({
    path: path.resolve('../docs/screenshots/mobile-noturno.png'),
    fullPage: true,
    animations: 'disabled',
  });
  const fieldBottom = await page
    .locator('.field')
    .evaluate((el) => el.getBoundingClientRect().bottom);
  const mobileRosterTop = await page
    .locator('.roster-panel')
    .evaluate((el) => el.getBoundingClientRect().top);
  expect(mobileRosterTop).toBeGreaterThan(fieldBottom);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();
  await page.getByRole('button', { name: 'Abrir menu' }).click();
  await expect(page.locator('.sidebar')).toHaveClass(/mobile-open/);
  await page.getByRole('button', { name: 'Fechar menu' }).first().click();
  await expect(page.locator('.sidebar')).not.toHaveClass(/mobile-open/);
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#10241a');
  await page.screenshot({
    path: path.resolve('../docs/screenshots/mobile.png'),
    fullPage: true,
    animations: 'disabled',
  });
});

test('conta conectada abre Meus grupos e a aba Churrasco', async ({ page }) => {
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({ json: { id: 'owner', name: 'Maria Costa', email: 'maria@example.com' } }),
  );
  await page.route('**/api/groups', (route) => route.fulfill({ json: [club] }));
  await page.route(`**/api/groups/${club.id}/games`, (route) => route.fulfill({ json: [game] }));
  await page.route(`**/api/groups/${club.id}/friendlies`, (route) => route.fulfill({ json: [] }));
  await page.route(`**/api/groups/${club.id}/barbecues`, (route) =>
    route.fulfill({
      json: [
        {
          id: 'bbq-visual',
          clubId: club.id,
          clubName: club.name,
          startsAt: '2099-10-12T15:00:00Z',
          location: 'Salão da esquina',
          cancelled: false,
          recurring: true,
          confirmed: 2,
          attending: false,
          inviteToken: 'bbq-invite',
          attendees: [
            { id: 'p1', name: 'João Pereira' },
            { id: 'p2', name: 'Lucas Almeida' },
          ],
        },
      ],
    }),
  );
  await page.goto('/');
  await expect(page).toHaveURL(/#groups$/);
  await expect(page.getByRole('heading', { name: 'Seus grupos.' })).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/grupos.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: /Pelada de quinta/ }).click();
  await expect(page.getByRole('button', { name: 'Convidar galera' })).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/agenda.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: 'Marcar pelada' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/formulario.png'),
    animations: 'disabled',
  });
  await page.keyboard.press('Escape');
  await page.getByRole('tab', { name: 'Churrasco' }).click();
  await expect(page.getByRole('button', { name: 'Convidar galera' })).toHaveCount(0);
  await expect(page.locator('.barbecue-card')).toBeVisible();
  await expect(
    page.getByRole('button', { name: /Convidar alguém para o churrasco de/ }),
  ).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/churrasco.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: /Editar churrasco de/ }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/churrasco-formulario.png'),
    animations: 'disabled',
  });
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/churrasco-noturno.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.locator('.sidebar').evaluate((el) => el.getBoundingClientRect().right))
    .toBeLessThanOrEqual(0);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/churrasco-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.route('**/api/barbecue-invites/bbq-invite', (route) =>
    route.fulfill({
      json: {
        id: 'bbq-visual',
        clubId: club.id,
        clubName: club.name,
        startsAt: '2099-10-12T15:00:00Z',
        location: 'Salão da esquina',
        cancelled: false,
        recurring: true,
        confirmed: 2,
        attending: false,
        inviteToken: 'bbq-invite',
        attendees: [
          { id: 'p1', name: 'João Pereira' },
          { id: 'p2', name: 'Lucas Almeida' },
        ],
      },
    }),
  );
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/#barbecue-invite/bbq-invite');
  await expect(page.getByRole('heading', { name: 'Você está convidado.' })).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/convite.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/convite-mobile-noturno.png'),
    fullPage: true,
    animations: 'disabled',
  });
});

test('agenda separa próximas, finalizadas e canceladas e abre a súmula', async ({ page }) => {
  const now = Date.now();
  const serverNow = new Date(now).toISOString();
  const listedGame = (
    id: string,
    title: string,
    startsAt: number,
    matchStatus: string,
    cancelled = false,
  ) => ({
    ...game,
    id,
    title,
    startsAt: new Date(startsAt).toISOString(),
    serverNow,
    matchStatus,
    cancelled,
    editable: matchStatus === 'SCHEDULED' && startsAt > now,
    teamEditable: matchStatus === 'SCHEDULED' && startsAt > now,
  });
  const upcoming = listedGame('upcoming-game', 'Pelada futura', now + 86400000, 'SCHEDULED');
  const transitionsToFinished = listedGame(
    'transitioning-game',
    'Pelada que vai passar',
    now + 1500,
    'SCHEDULED',
  );
  const live = listedGame('live-game', 'Pelada ao vivo', now - 3600000, 'LIVE');
  const finished = listedGame('finished-game', 'Partida encerrada', now + 172800000, 'FINISHED');
  const pastWithoutResult = listedGame(
    'past-without-result',
    'Partida sem resultado',
    now - 172800000,
    'READY',
  );
  const cancelled = listedGame(
    'cancelled-game',
    'Pelada cancelada',
    now - 345600000,
    'CANCELLED',
    true,
  );
  let groupGames = [upcoming, transitionsToFinished, live, finished, pastWithoutResult, cancelled];
  const detailsFor = (selectedGame: typeof game, hasResult: boolean) => ({
    ...finishedDetail,
    game: {
      ...finishedDetail.game,
      ...selectedGame,
      matchStartedAt: hasResult ? new Date(now - 7200000).toISOString() : null,
      matchEndedAt: hasResult ? new Date(now - 3600000).toISOString() : null,
      matchDurationSeconds: hasResult ? 3600 : null,
    },
    score: hasResult
      ? finishedDetail.score
      : [
          { teamId: 'verde', goals: 0 },
          { teamId: 'azul', goals: 0 },
        ],
    goals: hasResult ? finishedDetail.goals : [],
    ratings: hasResult ? finishedDetail.ratings : [],
    ratingsVisibleAt: hasResult ? new Date(now + 5000).toISOString() : null,
  });

  await page.route('**/api/auth/me', (route) =>
    route.fulfill({ json: { id: 'owner', name: 'Maria Costa', email: 'maria@example.com' } }),
  );
  await page.route('**/api/groups', (route) => route.fulfill({ json: [club] }));
  await page.route(`**/api/groups/${club.id}/games`, (route) =>
    route.fulfill({ json: groupGames }),
  );
  await page.route(`**/api/groups/${club.id}/friendlies`, (route) => route.fulfill({ json: [] }));
  await page.route(`**/api/groups/${club.id}/barbecues`, (route) => route.fulfill({ json: [] }));
  await page.route('**/api/games/past-without-result', (route) =>
    route.fulfill({ json: detailsFor(pastWithoutResult, false) }),
  );
  await page.route('**/api/games/finished-game', (route) =>
    route.fulfill({ json: detailsFor(finished, true) }),
  );

  await page.clock.install({ time: now });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(`/#group/${club.id}`);
  await expect(page.getByRole('heading', { name: 'Pelada de quinta.' })).toBeVisible();
  const list = page.getByRole('tabpanel', { name: 'Próximas' });
  const upcomingTab = page.getByRole('tab', { name: /Próximas 3/ });
  await expect(upcomingTab).toBeVisible();
  await expect(list.getByRole('button')).toHaveCount(3);
  await expect(list.getByRole('button', { name: /Pelada ao vivo/ })).toBeVisible();
  await expect(list.getByRole('button', { name: /Pelada futura/ })).toBeVisible();
  await page.clock.runFor(2500);
  await expect(page.getByRole('tab', { name: /Próximas 2/ })).toBeVisible();
  await expect(page.getByRole('tab', { name: /Finalizadas 3/ })).toBeVisible();

  const finishedTab = page.getByRole('tab', { name: /Finalizadas 3/ });
  await finishedTab.focus();
  await page.keyboard.press('Enter');
  await expect(finishedTab).toHaveAttribute('aria-selected', 'true');
  await page.keyboard.press('ArrowRight');
  const cancelledTab = page.getByRole('tab', { name: /Canceladas 1/ });
  await expect(cancelledTab).toBeFocused();
  await page.keyboard.press('ArrowLeft');
  await expect(finishedTab).toBeFocused();
  await expect(page.getByRole('tabpanel', { name: 'Finalizadas' }).getByRole('button')).toHaveCount(
    3,
  );
  await expect(
    page.getByRole('button', { name: /Partida sem resultado.*Resultado não registrado/ }),
  ).toBeVisible();

  await cancelledTab.click();
  await expect(
    page.getByRole('tabpanel', { name: 'Canceladas' }).getByRole('button', {
      name: /Pelada cancelada.*Cancelada/,
    }),
  ).toBeVisible();
  await page.getByRole('tab', { name: /Finalizadas 3/ }).click();
  await page.getByRole('button', { name: /Partida sem resultado/ }).click();
  await expect(page).toHaveURL(/#game\/past-without-result$/);
  await expect(page.getByRole('tab', { name: 'Partida' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('heading', { name: 'Resultado não registrado' })).toBeVisible();
  await expect(page.locator('.scoreboard')).toHaveCount(0);
  await expect(page.getByText('Nenhum gol registrado.')).toHaveCount(0);

  await page.goto(`/#group/${club.id}`);
  await page.getByRole('tab', { name: /Finalizadas 3/ }).click();
  await page.getByRole('button', { name: /Partida encerrada/ }).click();
  await expect(page.locator('.score-team strong')).toHaveText(['2', '1']);
  await expect(page.locator('.timeline-row')).toHaveCount(3);
  await page.getByRole('tab', { name: 'Notas' }).click();
  await expect(page.locator('.rating-row')).toHaveCount(12);
  await expect(page.getByText('Aguardando publicação', { exact: true })).toHaveCount(12);
  await expect(page.locator('.rating-row strong')).toHaveCount(0);
  await page.clock.runFor(6000);
  await expect(page.locator('.rating-row strong')).toHaveCount(12);
  await expect(page.getByText('Aguardando publicação', { exact: true })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();

  groupGames = [];
  await page.goto(`/#group/${club.id}`);
  await page.getByRole('tab', { name: /Próximas 0/ }).click();
  await expect(page.getByRole('status')).toContainText('A quadra está esperando.');
  await page.getByRole('tab', { name: /Finalizadas 0/ }).click();
  await expect(page.getByRole('status')).toContainText('Nenhuma partida finalizada.');
  await page.getByRole('tab', { name: /Canceladas 0/ }).click();
  await expect(page.getByRole('status')).toContainText('Nenhuma pelada cancelada.');
});
