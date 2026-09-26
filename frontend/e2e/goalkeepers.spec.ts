import { test, expect, request, APIRequestContext, Page } from '@playwright/test';

const baseURL = process.env['BASE_URL'] || 'http://127.0.0.1:8080';

async function mutate(ctx: APIRequestContext, url: string, method = 'POST', data?: unknown) {
  const token = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.fetch('/api' + url, { method, data, headers: { [token.headerName]: token.token } });
}

async function account(name: string) {
  const ctx = await request.newContext({ baseURL });
  const email = `e2e-gk-${crypto.randomUUID()}@example.com`;
  const password = 'PeladaTeste123!';
  expect(
    (await mutate(ctx, '/auth/register', 'POST', { name, email, password })).ok(),
  ).toBeTruthy();
  const login = await mutate(ctx, '/auth/login', 'POST', { email, password });
  expect(login.ok()).toBeTruthy();
  return { ctx, email, user: await login.json() };
}

async function noHorizontalScroll(page: Page) {
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBe(true);
}

test('goleiro publica perfil, organizador convida, gol fica reservado e convidado joga só a partida', async ({
  browser,
}) => {
  const organizer = await account('Organizadora do Gol');
  const tag = crypto.randomUUID().slice(0, 6);
  const keeperName = `Rafa Goleiro ${tag}`;
  const keeper = await account(keeperName);
  const member = await account('Beto da Linha');

  const club = await (
    await mutate(organizer.ctx, '/groups', 'POST', { name: 'Pelada do Gol Livre', description: '' })
  ).json();
  expect((await mutate(member.ctx, `/invites/${club.invite}/join`)).ok()).toBeTruthy();
  const created = await (
    await mutate(organizer.ctx, `/groups/${club.id}/games`, 'POST', {
      title: 'Pelada com goleiro convidado',
      location: 'Arena do Teste, Rua Um 10',
      startsAt: new Date(Date.now() + 3 * 86400000).toISOString(),
      teamCount: 2,
      teamSize: 5,
    })
  ).json();
  const gameId = created.game.id;
  expect((await mutate(member.ctx, `/games/${gameId}/attendance`)).ok()).toBeTruthy();

  // A player without a group publishes the goalkeeper profile using only the keyboard.
  const keeperUi = await browser.newContext({
    baseURL,
    storageState: await keeper.ctx.storageState(),
    viewport: { width: 390, height: 844 },
  });
  const keeperPage = await keeperUi.newPage();
  keeperPage.on('dialog', (dialog) => dialog.accept());
  await keeperPage.goto('/#social');
  await expect(keeperPage.getByRole('heading', { name: 'Perfil de goleiro' })).toBeVisible();
  await expect(keeperPage.getByRole('tab', { name: 'Buscar goleiros' })).toHaveCount(0);
  await expect(keeperPage.getByRole('tab', { name: 'Buscar times' })).toHaveCount(0);
  await keeperPage.getByLabel('Sua cidade').focus();
  await keeperPage.keyboard.type('São Paulo');
  const option = keeperPage.getByRole('option', { name: 'São Paulo — SP' });
  await expect(option).toBeVisible();
  await option.focus();
  await keeperPage.keyboard.press('Enter');
  await expect(keeperPage.getByLabel('Sua cidade')).toHaveValue('São Paulo — SP');
  await keeperPage.getByLabel('Nível').selectOption('COMPETITIVE');
  await keeperPage.getByLabel('Sobre você no gol').fill('Saio bem do gol e falo muito com a zaga.');
  await keeperPage.getByRole('button', { name: 'Sáb', exact: true }).press('Enter');
  await keeperPage.getByRole('button', { name: 'Noite', exact: true }).press('Space');
  await keeperPage.getByRole('button', { name: 'Salvar perfil de goleiro' }).click();
  await expect(keeperPage.getByRole('status')).toContainText('Perfil de goleiro publicado');
  await expect(keeperPage.locator('.keeper-state')).toHaveText('Publicado');
  await expect(keeperPage.locator('.keeper-rating')).toContainText('Sem avaliações ainda');
  await noHorizontalScroll(keeperPage);

  // Only organizers search and invite.
  expect(
    (await keeper.ctx.get('/api/social/goalkeepers?cityCode=3550308&radiusKm=10')).status(),
  ).toBe(403);
  expect((await member.ctx.get('/api/social/goalkeeper-invites/options')).status()).toBe(403);
  const found = await (
    await organizer.ctx.get(
      '/api/social/goalkeepers?cityCode=3550308&radiusKm=10&skillLevel=COMPETITIVE&days=SAT&periods=EVENING',
    )
  ).json();
  const listed = found.find((item: any) => item.name === keeperName);
  expect(listed).toBeTruthy();
  expect(listed.averageRating).toBeNull();
  expect(JSON.stringify(found)).not.toContain(keeper.email);
  expect(Object.keys(listed)).not.toContain('history');

  // The organizer finds the goalkeeper and reserves the goal on desktop.
  const organizerUi = await browser.newContext({
    baseURL,
    storageState: await organizer.ctx.storageState(),
    viewport: { width: 1365, height: 1000 },
  });
  const organizerPage = await organizerUi.newPage();
  organizerPage.on('dialog', (dialog) => dialog.accept());
  await organizerPage.goto('/#social');
  await organizerPage.getByRole('tab', { name: 'Buscar goleiros' }).click();
  await organizerPage.getByLabel('Cidade para buscar goleiros').fill('São Paulo');
  await organizerPage.getByRole('option', { name: 'São Paulo — SP' }).click();
  await organizerPage.getByRole('button', { name: 'Buscar goleiros', exact: true }).click();
  const card = organizerPage.locator('.keeper-card').filter({ hasText: keeperName });
  await expect(card).toContainText('Sem avaliações ainda');
  await expect(card).toContainText('Competitivo');
  await expect(card).not.toContainText(keeper.email);
  await card.getByRole('button', { name: `Convidar ${keeperName} para jogar` }).click();
  const dialog = organizerPage.getByRole('dialog', { name: keeperName });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('Pelada')).toContainText('Pelada com goleiro convidado');
  await expect(dialog.locator('.keeper-game-summary')).toContainText('Arena do Teste');
  await dialog.getByLabel('Time').selectOption({ index: 2 });
  await dialog.getByLabel('Mensagem').fill('Falta goleiro no time 2.');
  await expect(dialog).toContainText(`fica reservado para ${keeperName}`);
  await dialog.getByRole('button', { name: 'Enviar convite' }).click();
  await expect(organizerPage.getByRole('status')).toContainText('fica reservado');
  const sentCard = organizerPage.locator('.keeper-invite-card').first();
  await expect(sentCard).toContainText('Aguardando resposta');
  await expect(sentCard).toContainText('Gol de Time 2');
  await expect(sentCard.getByRole('button', { name: 'Cancelar convite' })).toBeVisible();

  // The lineup shows the reserved goal and blocks concurrent invites.
  await organizerPage.goto(`/#game/${gameId}`);
  await organizerPage.getByRole('tab', { name: 'Time 2' }).click();
  await expect(organizerPage.locator('.field-player.reserved')).toContainText('Reservado');
  await expect(organizerPage.locator('.pitch-reserved-note')).toContainText(keeperName);
  const detail = await (await organizer.ctx.get(`/api/games/${gameId}`)).json();
  const team2 = detail.teams[1].id;
  expect(detail.goalkeeperReservations).toHaveLength(1);
  const otherKeeper = await account(`Outro Goleiro ${tag}`);
  expect(
    (
      await mutate(otherKeeper.ctx, '/social/goalkeeper-profile', 'PUT', {
        municipalityCode: '3550308',
        skillLevel: 'INTERMEDIATE',
        preferredDays: [],
        preferredPeriods: [],
        description: '',
        published: true,
      })
    ).ok(),
  ).toBeTruthy();
  const other = (
    await (await organizer.ctx.get('/api/social/goalkeepers?cityCode=3550308&radiusKm=10')).json()
  ).find((item: any) => item.name === `Outro Goleiro ${tag}`);
  const concurrent = await mutate(organizer.ctx, '/social/goalkeeper-invites', 'POST', {
    profileId: other.profileId,
    gameId,
    teamId: team2,
    message: '',
  });
  expect(concurrent.status()).toBe(409);

  // The goalkeeper accepts on a phone with the keyboard and sees only the accepted match.
  await keeperPage.reload();
  await expect(keeperPage.getByRole('tab', { name: /Convites de goleiro/ })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  const received = keeperPage.locator('.keeper-invite-card').first();
  await expect(received).toContainText('Organizadora do Gol convidou você');
  await expect(received).toContainText('Arena do Teste');
  await received.getByRole('button', { name: 'Aceitar e entrar no gol' }).focus();
  await keeperPage.keyboard.press('Enter');
  await expect(keeperPage.getByRole('status')).toContainText('Você está no gol de Time 2');
  await noHorizontalScroll(keeperPage);
  await received.getByRole('link', { name: 'Abrir partida' }).click();
  await expect(
    keeperPage.getByRole('heading', { name: 'Pelada com goleiro convidado' }),
  ).toBeVisible();
  await expect(keeperPage.locator('.guest-context')).toContainText('Goleiro convidado');
  await expect(keeperPage.getByRole('button', { name: 'Convidar galera' })).toHaveCount(0);
  await expect(keeperPage.locator('.presence-state')).toContainText('Time 2');
  await noHorizontalScroll(keeperPage);

  const asGuest = await (await keeper.ctx.get(`/api/games/${gameId}`)).json();
  expect(asGuest.viewerGuest).toBe(true);
  expect(asGuest.club.invite).toBeNull();
  const me = asGuest.attendees.find((item: any) => item.name === keeperName);
  expect(me).toMatchObject({ status: 'CONFIRMED', teamId: team2, slot: 0, guestGoalkeeper: true });
  expect((await keeper.ctx.get(`/api/groups/${club.id}/games`)).status()).toBe(403);
  expect((await keeper.ctx.get(`/api/groups/${club.id}/finance`)).status()).toBe(403);
  expect(await (await keeper.ctx.get('/api/groups')).json()).toHaveLength(0);

  // Leaving the match releases the goal and keeps the invite history.
  await keeperPage.getByRole('button', { name: 'Sair da partida' }).click();
  await keeperPage.getByRole('dialog').getByRole('button', { name: 'Sair da partida' }).click();
  await expect(keeperPage.getByRole('heading', { name: 'Social.' })).toBeVisible();
  expect((await keeper.ctx.get(`/api/games/${gameId}`)).status()).toBe(403);
  const history = await (await organizer.ctx.get('/api/social/goalkeeper-invites/sent')).json();
  expect(history[0].status).toBe('WITHDRAWN');
  const released = await (await organizer.ctx.get(`/api/games/${gameId}`)).json();
  expect(released.goalkeeperReservations).toHaveLength(0);
  expect(released.attendees.some((item: any) => item.guestGoalkeeper)).toBe(false);

  for (const ctx of [organizer.ctx, keeper.ctx, member.ctx, otherKeeper.ctx]) await ctx.dispose();
  await keeperUi.close();
  await organizerUi.close();
});
