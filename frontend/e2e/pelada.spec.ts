import { test, expect, request, APIRequestContext } from '@playwright/test';
import path from 'node:path';

const baseURL = process.env['BASE_URL'] || 'http://127.0.0.1:8080';
async function mutate(ctx: APIRequestContext, url: string, method = 'POST', data?: unknown) {
  const token = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.fetch('/api' + url, { method, data, headers: { [token.headerName]: token.token } });
}
async function account(name: string, requestedEmail?: string) {
  const ctx = await request.newContext({ baseURL });
  const email = requestedEmail || `e2e-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  expect(
    (await mutate(ctx, '/auth/register', 'POST', { name, email, password })).ok(),
  ).toBeTruthy();
  const login = await mutate(ctx, '/auth/login', 'POST', { email, password });
  expect(login.ok()).toBeTruthy();
  return { ctx, email, password, user: await login.json() };
}

function futureLocalDateTime(daysAhead: number, hour = 19) {
  const date = new Date(Date.now() + daysAhead * 86400000);
  date.setHours(hour, 0, 0, 0);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

test('demonstração, navegação, teclado e layout desktop/mobile', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'O jogo começa aqui.' })).toBeVisible();
  await expect(page.locator('.sidebar')).toHaveCSS('position', 'fixed');
  await expect(page.locator('.field')).toHaveCSS('position', 'relative');
  await expect(page.locator('.field-player')).toHaveCount(5);
  await expect(page.locator('#formation')).toBeDisabled();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('tab', { name: 'Boleiros FC' }).click();
  await expect(page.locator('.roster-panel .compact-heading')).toContainText('Boleiros FC');
  await page.getByRole('tab', { name: /Lista de espera/ }).click();
  await expect(page.locator('.person-row')).toHaveCount(2);
  await page.getByRole('tab', { name: 'Escalações', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.field')).toBeVisible();
  await expect
    .poll(() => page.locator('.sidebar').evaluate((el) => el.getBoundingClientRect().right))
    .toBeLessThanOrEqual(0);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/mobile.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: 'Abrir menu' }).click();
  await expect(page.locator('.sidebar')).toHaveClass(/mobile-open/);
  await page.keyboard.press('Escape');
  await expect(page.locator('.sidebar')).not.toHaveClass(/mobile-open/);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByLabel('E-mail', { exact: true }).fill('teste@example.com');
  await page.mouse.click(5, 5);
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByLabel('E-mail', { exact: true })).toHaveValue('teste@example.com');
  await page.keyboard.press('Tab');
  expect(
    await page.locator('.modal').evaluate((el) => el.contains(document.activeElement)),
  ).toBeTruthy();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: 'Ver partida concluída' }).click();
  await expect(page.getByRole('tab', { name: 'Partida' })).toBeVisible();
  await expect(page.locator('.scoreboard')).toContainText('3');
  await expect(page.locator('.timeline-row')).toHaveCount(4);
  await page.getByRole('tab', { name: 'Notas' }).click();
  await expect(page.locator('.rating-row')).toHaveCount(14);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();
  await page.goto('/#groups');
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Fechar' }).click();
  await expect(page).toHaveURL(/#demo$/);
  expect(errors).toEqual([]);
});

test('segurança HTTP: CSRF, sessão, permissões, validação e OpenAPI', async () => {
  const anon = await request.newContext({ baseURL });
  expect((await anon.get('/api/groups')).status()).toBe(401);
  expect(
    (
      await anon.post('/api/auth/register', {
        data: { name: 'Sem CSRF', email: 'invalid@example.com', password: 'abc12345' },
      })
    ).status(),
  ).toBe(403);
  const a = await account('Organizador Segurança'),
    b = await account('Outro Grupo');
  const club = await (
    await mutate(a.ctx, '/groups', 'POST', { name: 'Grupo privado', description: '' })
  ).json();
  expect((await b.ctx.get(`/api/groups/${club.id}/games`)).status()).toBe(403);
  expect(
    (
      await mutate(a.ctx, `/groups/${club.id}/games`, 'POST', {
        title: 'Inválida',
        location: 'Quadra',
        startsAt: new Date(Date.now() + 86400000).toISOString(),
        teamCount: 1,
        teamSize: 1,
      })
    ).status(),
  ).toBe(400);
  expect((await a.ctx.get('/api/auth/me')).status()).toBe(200);
  const longEmail = await account(
    'E-mail longo',
    crypto.randomUUID() + '@' + 'd'.repeat(60) + '.example.com',
  );
  expect((await longEmail.ctx.get('/api/auth/me')).status()).toBe(200);
  await longEmail.ctx.dispose();
  const cookies = await a.ctx.storageState();
  expect(
    cookies.cookies.some((c) => c.name === 'SESSION' && c.httpOnly && c.sameSite === 'Lax'),
  ).toBeTruthy();
  expect((await a.ctx.get('/api/openapi')).ok()).toBeTruthy();
  expect((await mutate(a.ctx, '/auth/logout')).status()).toBe(204);
  expect((await a.ctx.get('/api/auth/me')).status()).toBe(401);
  await a.ctx.dispose();
  await b.ctx.dispose();
  await anon.dispose();
});

test('cadastro, grupo, convite, pelada, escolha de elenco e escalação persistida', async ({
  page,
  browser,
}) => {
  const email = `organizador-${crypto.randomUUID()}@example.com`;
  await page.goto('/');
  await page.getByRole('button', { name: 'Criar minha pelada' }).click();
  await page.getByLabel('Seu nome').fill('Capitão do Teste');
  await page.getByLabel('E-mail', { exact: true }).fill(email);
  await page.getByLabel('Senha', { exact: true }).fill('PeladaTeste123!');
  await page.getByRole('button', { name: 'Criar minha conta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Seus grupos.' })).toBeVisible();
  await page.getByRole('button', { name: 'Criar meu primeiro grupo' }).click();
  await page.getByLabel('Nome do grupo').fill('Pelada da integração');
  await page.getByLabel('Sobre o grupo').fill('Grupo criado pelo teste completo.');
  await page.getByRole('dialog').getByRole('button', { name: 'Criar grupo', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Pelada da integração.' })).toBeVisible();
  const clubs = await (await page.request.get('/api/groups')).json();
  const club = clubs.find((c: any) => c.name === 'Pelada da integração');
  await page.getByRole('button', { name: 'Marcar pelada', exact: true }).click();
  await page.getByLabel('Nome da pelada').fill('Jogo de integração');
  await page.getByLabel('Local e quadra').fill('Arena de Teste');
  await page.getByLabel('Data e horário').fill('2099-10-10T20:00');
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Marcar pelada', exact: true })
    .click();
  await expect(page.getByRole('heading', { name: 'Jogo de integração' })).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar presença', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Estou dentro' })).toBeVisible();
  const gameId = page.url().split('/').at(-1)!;
  const teammate = await account('Lucas da Integração');
  const teammateContext = await browser.newContext({
    storageState: await teammate.ctx.storageState(),
  });
  const other = await teammateContext.newPage();
  await other.goto('/#invite/' + club.invite);
  await expect(other.getByRole('dialog')).toBeVisible();
  await other.getByRole('button', { name: 'Entrar no grupo', exact: true }).click();
  await expect(other.getByRole('heading', { name: 'Pelada da integração.' })).toBeVisible();
  await other.getByRole('button', { name: /Jogo de integração/ }).click();
  await other.getByRole('button', { name: 'Confirmar presença', exact: true }).click();
  await expect(other.getByRole('button', { name: 'Estou dentro' })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Configurar time e capitão' }).click();
  await page.getByLabel('Nome do time').fill('Os Testadores');
  await page.getByLabel('Capitão', { exact: true }).selectOption({ label: 'Capitão do Teste' });
  await page.getByRole('button', { name: 'Salvar time', exact: true }).click();
  await page.getByRole('button', { name: 'Escolher Lucas da Integração' }).click();
  await expect(page.locator('.roster-panel')).toContainText('Lucas da Integração');
  await page.getByRole('button', { name: /Escalar GOL/ }).click();
  await page.getByLabel(/GOL · escolher jogador/).selectOption({ label: 'Capitão do Teste' });
  await expect(page.getByRole('button', { name: 'Escalar GOL: Capitão do Teste' })).toBeVisible();
  for (const formation of ['1-2-1', '3-1', '2-2']) {
    await page.getByLabel('Formação', { exact: true }).selectOption(formation);
    await expect(page.locator('#formation')).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Escalar GOL: Capitão do Teste' })).toBeVisible();
  }
  // Keyboard-accessible position picker, then swap occupied positions.
  await page.getByRole('button', { name: /Escalar FIXO E/ }).click();
  await page.getByLabel(/FIXO E · escolher jogador/).selectOption({ label: 'Lucas da Integração' });
  await expect(
    page.getByRole('button', { name: 'Escalar FIXO E: Lucas da Integração' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Escalar GOL: Capitão do Teste' }).click();
  await page.getByLabel(/GOL · escolher jogador/).selectOption({ label: 'Lucas da Integração' });
  await expect(
    page.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Escalar FIXO E: Capitão do Teste' }),
  ).toBeVisible();
  await page.setViewportSize({ width: 1280, height: 1440 });
  await page
    .getByRole('button', { name: 'Escalar GOL: Lucas da Integração' })
    .dragTo(page.locator('.bench'));
  await expect(page.getByRole('button', { name: 'Escalar GOL — posição vazia' })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: /Escalar GOL/ }).click();
  await page.getByLabel(/GOL · escolher jogador/).focus();
  await page.keyboard.press('l');
  await page.keyboard.press('Enter');
  await expect(
    page.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeVisible();
  await other.reload();
  await expect(other.locator('#formation')).toBeDisabled();
  await expect(
    other.getByRole('button', { name: 'Escalar GOL: Lucas da Integração' }),
  ).toBeDisabled();
  const detail = await (await teammate.ctx.get(`/api/games/${gameId}`)).json();
  expect(
    (
      await mutate(teammate.ctx, `/games/${gameId}/teams/${detail.teams[0].id}/lineup`, 'PUT', {
        formation: '2-2',
        slots: [null, null, null, null, null],
        version: detail.teams[0].version,
      })
    ).status(),
  ).toBe(403);
  await page.getByRole('button', { name: 'Estou dentro' }).click();
  await page.getByRole('button', { name: 'Confirmar desistência' }).click();
  await expect(page.locator('.roster-panel')).toContainText('Lucas da Integração');
  await expect(page.locator('.roster-panel')).not.toContainText('Capitão do Teste');
  await page.getByRole('button', { name: 'Configurar time e capitão' }).click();
  await expect(page.getByLabel('Capitão', { exact: true })).toHaveValue('');
  await teammateContext.close();
  await teammate.ctx.dispose();
});

test('partida ao vivo, gol, encerramento e avaliação em desktop e celular', async ({ browser }) => {
  test.setTimeout(120_000);
  const organizer = await account('Organizador Ao Vivo');
  const mate = await account('Colega Ao Vivo');
  const opponent = await account('Adversário Ao Vivo');
  const club = await (
    await mutate(organizer.ctx, '/groups', 'POST', { name: 'Grupo ao vivo', description: '' })
  ).json();
  for (const person of [mate, opponent]) {
    expect((await mutate(person.ctx, '/invites/' + club.invite + '/join')).ok()).toBeTruthy();
  }
  const kickoff = Date.now() + 20_000;
  const game = await (
    await mutate(organizer.ctx, '/groups/' + club.id + '/games', 'POST', {
      title: 'Jogo ao vivo',
      location: 'Quadra teste',
      startsAt: new Date(kickoff).toISOString(),
      teamCount: 2,
      teamSize: 5,
    })
  ).json();
  for (const person of [organizer, mate, opponent]) {
    expect((await mutate(person.ctx, '/games/' + game.game.id + '/attendance')).ok()).toBeTruthy();
  }
  const [first, second] = game.teams;
  expect(
    (
      await mutate(organizer.ctx, '/games/' + game.game.id + '/teams/' + first.id, 'PUT', {
        name: 'Verde',
        color: '#d8f36a',
        captainId: organizer.user.id,
      })
    ).ok(),
  ).toBeTruthy();
  expect(
    (
      await mutate(organizer.ctx, '/games/' + game.game.id + '/teams/' + second.id, 'PUT', {
        name: 'Roxo',
        color: '#a69aff',
        captainId: opponent.user.id,
      })
    ).ok(),
  ).toBeTruthy();
  expect(
    (
      await mutate(
        organizer.ctx,
        '/games/' + game.game.id + '/teams/' + first.id + '/players',
        'POST',
        { playerId: mate.user.id },
      )
    ).ok(),
  ).toBeTruthy();

  const desktop = await browser.newContext({
    storageState: await organizer.ctx.storageState(),
    viewport: { width: 1440, height: 900 },
  });
  const mobile = await browser.newContext({
    storageState: await opponent.ctx.storageState(),
    viewport: { width: 390, height: 844 },
  });
  const ownerPage = await desktop.newPage();
  const otherPage = await mobile.newPage();
  await ownerPage.goto('/#game/' + game.game.id);
  await otherPage.goto('/#game/' + game.game.id);
  await ownerPage.getByRole('tab', { name: 'Partida' }).click();
  await otherPage.getByRole('tab', { name: 'Partida' }).click();
  await ownerPage.waitForTimeout(Math.max(0, kickoff - Date.now() + 300));
  await ownerPage.reload();
  await ownerPage.getByRole('tab', { name: 'Partida' }).click();
  await ownerPage.getByRole('button', { name: 'Começar partida' }).click();
  await expect(ownerPage.locator('.match-clock')).not.toHaveText('0:00', { timeout: 5_000 });
  await ownerPage.reload();
  await ownerPage.getByRole('tab', { name: 'Partida' }).click();
  await expect(ownerPage.locator('.match-clock')).not.toHaveText('0:00');
  await ownerPage.getByRole('button', { name: 'Registrar gol' }).click();
  await ownerPage.getByLabel('Autor do gol').selectOption({ label: 'Colega Ao Vivo' });
  await ownerPage.getByRole('button', { name: 'Salvar gol' }).click();
  await expect(ownerPage.locator('.scoreboard')).toContainText('1');
  await otherPage.reload();
  await otherPage.getByRole('tab', { name: 'Partida' }).click();
  await expect(otherPage.locator('.scoreboard')).toContainText('1');
  expect(
    await otherPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();
  await otherPage.getByRole('button', { name: 'Encerrar partida' }).click();
  await otherPage.getByRole('button', { name: 'Confirmar fim de jogo' }).click();
  await expect(otherPage.getByRole('heading', { name: 'Fim de jogo' })).toBeVisible();
  await ownerPage.reload();
  await ownerPage.getByRole('tab', { name: 'Notas' }).click();
  await ownerPage.getByRole('button', { name: '5 estrelas para Colega Ao Vivo' }).click();
  await expect(
    ownerPage.getByRole('button', { name: '5 estrelas para Colega Ao Vivo' }),
  ).toHaveClass(/selected/);
  expect(
    (
      await mutate(opponent.ctx, '/games/' + game.game.id + '/ratings', 'PUT', {
        playerId: mate.user.id,
        stars: 5,
      })
    ).status(),
  ).toBe(403);
  await desktop.close();
  await mobile.close();
  for (const person of [organizer, mate, opponent]) await person.ctx.dispose();
});

test('sorteio manual, recorrência de churrasco e convite individual em desktop e celular', async ({
  browser,
}) => {
  test.setTimeout(120_000);
  const organizer = await account('Organizador do Sorteio');
  const players = await Promise.all(
    Array.from({ length: 5 }, (_, index) => account(`Jogador do Sorteio ${index + 1}`)),
  );
  const barbecueOnly = await account('Só vai ao churrasco');
  const club = await (
    await mutate(organizer.ctx, '/groups', 'POST', {
      name: 'Sorteio e Churrasco',
      description: 'Teste das novas atividades do grupo.',
      barbecueFrequency: 'MONTHLY',
    })
  ).json();
  for (const person of [...players, barbecueOnly]) {
    expect((await mutate(person.ctx, `/invites/${club.invite}/join`)).ok()).toBeTruthy();
  }
  const game = await (
    await mutate(organizer.ctx, `/groups/${club.id}/games`, 'POST', {
      title: 'Pelada com sorteio',
      location: 'Campo do bairro',
      startsAt: new Date(Date.now() + 86400000).toISOString(),
      teamCount: 2,
      teamSize: 5,
    })
  ).json();
  for (const person of [organizer, ...players]) {
    expect((await mutate(person.ctx, `/games/${game.game.id}/attendance`)).ok()).toBeTruthy();
  }
  const [first, second] = game.teams;
  for (const [team, captain, name] of [
    [first, organizer.user.id, 'Verde'],
    [second, players[0].user.id, 'Roxo'],
  ] as const) {
    expect(
      (
        await mutate(organizer.ctx, `/games/${game.game.id}/teams/${team.id}`, 'PUT', {
          name,
          color: name === 'Verde' ? '#d8f36a' : '#a69aff',
          captainId: captain,
        })
      ).ok(),
    ).toBeTruthy();
  }

  const desktop = await browser.newContext({
    storageState: await organizer.ctx.storageState(),
    viewport: { width: 1440, height: 1000 },
  });
  const ownerPage = await desktop.newPage();
  await ownerPage.goto('/#game/' + game.game.id);
  await ownerPage.getByRole('button', { name: 'Sortear times' }).click();
  const firstDraw = ownerPage.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/games/${game.game.id}/teams/draw`) &&
      response.request().method() === 'POST',
  );
  await ownerPage.getByRole('dialog').getByRole('button', { name: 'Sortear agora' }).click();
  expect((await firstDraw).ok()).toBeTruthy();
  let detail = await (await organizer.ctx.get(`/api/games/${game.game.id}`)).json();
  expect(detail.attendees.filter((person: any) => person.status === 'CONFIRMED')).toHaveLength(6);
  expect(
    detail.attendees.filter((person: any) => person.status === 'CONFIRMED' && person.teamId),
  ).toHaveLength(6);
  expect(detail.teams.find((team: any) => team.id === first.id).captainId).toBe(organizer.user.id);
  expect(detail.teams.find((team: any) => team.id === second.id).captainId).toBe(
    players[0].user.id,
  );
  for (const team of detail.teams) {
    expect(detail.attendees.filter((person: any) => person.teamId === team.id)).toHaveLength(3);
  }

  const latePlayer = await account('Chegou depois do sorteio');
  expect((await mutate(latePlayer.ctx, `/invites/${club.invite}/join`)).ok()).toBeTruthy();
  expect((await mutate(latePlayer.ctx, `/games/${game.game.id}/attendance`)).ok()).toBeTruthy();
  detail = await (await organizer.ctx.get(`/api/games/${game.game.id}`)).json();
  expect(
    detail.attendees.find((person: any) => person.id === latePlayer.user.id).teamId,
  ).toBeNull();
  await ownerPage.reload();
  await ownerPage.getByRole('button', { name: 'Sortear times' }).click();
  const secondDraw = ownerPage.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/games/${game.game.id}/teams/draw`) &&
      response.request().method() === 'POST',
  );
  await ownerPage.getByRole('dialog').getByRole('button', { name: 'Sortear agora' }).click();
  expect((await secondDraw).ok()).toBeTruthy();
  detail = await (await organizer.ctx.get(`/api/games/${game.game.id}`)).json();
  expect(
    detail.attendees.filter((person: any) => person.status === 'CONFIRMED' && person.teamId),
  ).toHaveLength(7);
  expect(
    await ownerPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();

  await ownerPage.goto('/#group/' + club.id);
  await ownerPage.getByRole('tab', { name: 'Churrasco' }).click();
  await ownerPage.getByRole('button', { name: 'Agendar primeira edição' }).click();
  await ownerPage.getByLabel('Data e horário').fill(futureLocalDateTime(5));
  await ownerPage.getByLabel('Local').fill('Salão da integração');
  await ownerPage.getByRole('dialog').getByRole('button', { name: 'Criar recorrência' }).click();
  await expect(ownerPage.locator('.barbecue-card')).toHaveCount(6);
  await ownerPage.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await ownerPage
    .getByRole('button', { name: /Convidar alguém para o churrasco de/ })
    .first()
    .click();
  const inviteLink = await ownerPage.evaluate(() => navigator.clipboard.readText());
  expect(inviteLink).toContain('#barbecue-invite/');

  const memberMobile = await browser.newContext({
    storageState: await barbecueOnly.ctx.storageState(),
    viewport: { width: 390, height: 844 },
  });
  const memberPage = await memberMobile.newPage();
  await memberPage.goto('/#group/' + club.id);
  await memberPage.getByRole('tab', { name: 'Churrasco' }).click();
  await memberPage
    .locator('.barbecue-card')
    .first()
    .getByRole('button', { name: 'Confirmar presença' })
    .click();
  await expect(memberPage.locator('.barbecue-card').first()).toContainText('Só vai ao churrasco');
  expect(
    await memberPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();
  const gameAttendance = await (await barbecueOnly.ctx.get(`/api/games/${game.game.id}`)).json();
  expect(
    gameAttendance.attendees.some((person: any) => person.id === barbecueOnly.user.id),
  ).toBeFalsy();

  const eventGuest = await account('Convidado do churrasco');
  const guestMobile = await browser.newContext({
    storageState: await eventGuest.ctx.storageState(),
    viewport: { width: 390, height: 844 },
  });
  const guestPage = await guestMobile.newPage();
  await guestPage.goto(inviteLink);
  await expect(guestPage.getByRole('heading', { name: 'Você está convidado.' })).toBeVisible();
  await guestPage.getByRole('button', { name: 'Confirmar presença' }).click();
  await expect(guestPage.getByRole('button', { name: /Presença confirmada/ })).toBeVisible();
  expect(
    (await (await eventGuest.ctx.get('/api/groups')).json()).map((group: any) => group.id),
  ).toEqual([]);
  expect(
    await guestPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();

  await desktop.close();
  await memberMobile.close();
  await guestMobile.close();
  for (const person of [organizer, ...players, barbecueOnly, latePlayer, eventGuest]) {
    await person.ctx.dispose();
  }
});

test('financeiro do grupo: avulsos, comprovante, revisão e privacidade no celular', async ({
  browser,
}) => {
  test.setTimeout(120_000);
  const organizer = await account('Organizador Financeiro');
  const captain = await account('Capitão Financeiro');
  const occasional = await account('Jogador Avulso Financeiro');
  const club = await (
    await mutate(organizer.ctx, '/groups', 'POST', {
      name: 'Grupo Financeiro',
      description: 'Mensalidades e peladas avulsas.',
      monthlyAmountCents: 5000,
      billingDueDay: 10,
      occasionalAmountCents: 1800,
      pixInstructions: 'Chave Pix: financeiro@example.com',
    })
  ).json();
  for (const person of [captain, occasional]) {
    expect((await mutate(person.ctx, `/invites/${club.invite}/join`)).ok()).toBeTruthy();
  }
  const kickoff = Date.now() + 5000;
  const game = await (
    await mutate(organizer.ctx, `/groups/${club.id}/games`, 'POST', {
      title: 'Pelada com cobrança',
      location: 'Quadra financeira',
      startsAt: new Date(kickoff).toISOString(),
      teamCount: 2,
      teamSize: 5,
      chargeOccasional: true,
      occasionalAmountCents: 2200,
    })
  ).json();
  for (const person of [organizer, captain, occasional]) {
    expect((await mutate(person.ctx, `/games/${game.game.id}/attendance`)).ok()).toBeTruthy();
  }
  const [firstTeam, secondTeam] = game.teams;
  expect(
    (
      await mutate(organizer.ctx, `/games/${game.game.id}/teams/${firstTeam.id}`, 'PUT', {
        name: 'Verde',
        color: '#d8f36a',
        captainId: organizer.user.id,
      })
    ).ok(),
  ).toBeTruthy();
  expect(
    (
      await mutate(organizer.ctx, `/games/${game.game.id}/teams/${secondTeam.id}`, 'PUT', {
        name: 'Azul',
        color: '#8d9dff',
        captainId: captain.user.id,
      })
    ).ok(),
  ).toBeTruthy();
  expect(
    (
      await mutate(captain.ctx, `/games/${game.game.id}/teams/${secondTeam.id}/players`, 'POST', {
        playerId: occasional.user.id,
      })
    ).ok(),
  ).toBeTruthy();
  await new Promise((resolve) => setTimeout(resolve, Math.max(0, kickoff - Date.now() + 100)));
  expect((await mutate(organizer.ctx, `/games/${game.game.id}/match/start`)).ok()).toBeTruthy();
  const classification = await mutate(
    organizer.ctx,
    `/groups/${club.id}/finance/members/${occasional.user.id}`,
    'PUT',
    { monthly: true },
  );
  expect(classification.ok()).toBeTruthy();
  const classificationData = await classification.json();
  expect(
    classificationData.members.find((member: any) => member.playerId === occasional.user.id)
      .monthlyFrom,
  ).toMatch(/^\d{4}-\d{2}-01$/);

  const period = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
  }).format(new Date(kickoff));
  const summary = await (
    await organizer.ctx.get(
      `/api/groups/${club.id}/finance?period=${period}&gameId=${game.game.id}`,
    )
  ).json();
  const gameCharges = summary.charges.filter((charge: any) => charge.type === 'GAME');
  expect(gameCharges).toHaveLength(3);
  expect(gameCharges.map((charge: any) => charge.amountCents)).toEqual([2200, 2200, 2200]);
  const occasionalCharge = gameCharges.find(
    (charge: any) => charge.playerId === occasional.user.id,
  );
  const ownerCharge = gameCharges.find((charge: any) => charge.playerId === organizer.user.id);
  expect(occasionalCharge).toBeDefined();
  expect(ownerCharge).toBeDefined();

  const ownerDesktop = await browser.newContext({
    storageState: await organizer.ctx.storageState(),
    viewport: { width: 1440, height: 1000 },
  });
  const playerMobile = await browser.newContext({
    storageState: await occasional.ctx.storageState(),
    viewport: { width: 390, height: 844 },
  });
  const ownerPage = await ownerDesktop.newPage();
  const playerPage = await playerMobile.newPage();
  await ownerPage.goto(`/#group/${club.id}`);
  await ownerPage.getByRole('tab', { name: 'Financeiro' }).click();
  await expect(ownerPage.getByText('Chave Pix: financeiro@example.com')).toBeVisible();
  await expect(ownerPage.locator('.finance-charge')).toHaveCount(3);
  await expect(ownerPage.locator('.finance-charge').first()).toContainText('Pelada com cobrança');
  await expect(
    ownerPage
      .locator('.finance-charge')
      .first()
      .getByRole('link', { name: 'Lembrar pelo WhatsApp' }),
  ).toHaveAttribute('href', /^https:\/\/wa\.me\/\?text=/);
  await ownerPage.screenshot({
    path: path.resolve('../docs/screenshots/finance-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });

  await playerPage.goto(`/#group/${club.id}`);
  await playerPage.getByRole('tab', { name: 'Financeiro' }).click();
  await expect(playerPage.locator('.finance-charge')).toHaveCount(1);
  await expect(playerPage.locator('.finance-charge')).toContainText('R$ 22,00');
  await playerPage.locator('.receipt-input').setInputFiles({
    name: 'comprovante.png',
    mimeType: 'image/png',
    buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  });
  await expect(playerPage.getByText('Comprovante enviado para conferência.')).toBeVisible();
  expect(
    (await occasional.ctx.get(`/api/finance/charges/${ownerCharge.id}/receipt`)).status(),
  ).toBe(403);
  expect(
    await playerPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();
  await playerPage.screenshot({
    path: path.resolve('../docs/screenshots/finance-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  });

  await ownerPage.reload();
  await ownerPage.getByRole('tab', { name: 'Financeiro' }).click();
  const playerRow = ownerPage
    .locator('.finance-charge')
    .filter({ hasText: 'Jogador Avulso Financeiro' });
  await expect(playerRow).toContainText('Aguardando conferência');
  await playerRow.getByRole('button', { name: 'Aprovar' }).click();
  await ownerPage.getByRole('button', { name: 'Aprovar pagamento' }).click();
  await expect(playerRow).toContainText('Pago');
  const ownerRow = ownerPage
    .locator('.finance-charge')
    .filter({ hasText: 'Organizador Financeiro' });
  await ownerRow.getByRole('button', { name: 'Registrar em dinheiro' }).click();
  await ownerPage.getByRole('button', { name: 'Confirmar pagamento' }).click();
  await expect(ownerRow).toContainText('Pago em dinheiro');
  expect(
    await ownerPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();

  await ownerDesktop.close();
  await playerMobile.close();
  for (const person of [organizer, captain, occasional]) await person.ctx.dispose();
});
