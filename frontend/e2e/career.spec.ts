import { test, expect, request, APIRequestContext, Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const baseURL = process.env['BASE_URL'] || 'http://127.0.0.1:8080';
const psql = process.env['CAREER_E2E_PSQL'];
const database = process.env['CAREER_E2E_DATABASE'];
// Time fixtures may modify only this explicitly selected, disposable database.
const liveFixtures =
  !!psql && database === 'pelada_career_dev' && new URL(baseURL).hostname === '127.0.0.1';
test.skip(
  !liveFixtures,
  'Defina CAREER_E2E_PSQL e CAREER_E2E_DATABASE=pelada_career_dev para a instância isolada de testes.',
);

async function mutate(ctx: APIRequestContext, url: string, method = 'POST', data?: unknown) {
  const csrf = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.fetch('/api' + url, { method, data, headers: { [csrf.headerName]: csrf.token } });
}
async function account(name: string) {
  const ctx = await request.newContext({ baseURL });
  const email = `career-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  expect(
    (await mutate(ctx, '/auth/register', 'POST', { name, email, password })).ok(),
  ).toBeTruthy();
  const login = await mutate(ctx, '/auth/login', 'POST', { email, password });
  expect(login.ok()).toBeTruthy();
  return { ctx, user: await login.json() };
}
function timeFixture(clubId: string, gameId: string) {
  if (!liveFixtures || !/^[a-f0-9-]{36}$/.test(clubId) || !/^[a-f0-9-]{36}$/.test(gameId))
    throw new Error('Fixture de tempo inválida.');
  const args = [
    '-h',
    '127.0.0.1',
    '-p',
    process.env['CAREER_E2E_PORT'] || '55434',
    '-U',
    'pelada',
    '-d',
    database!,
    '-v',
    'ON_ERROR_STOP=1',
    '-At',
  ];
  const current = execFileSync(psql!, [...args, '-c', 'select current_database()'], {
    encoding: 'utf8',
  }).trim();
  if (current !== 'pelada_career_dev')
    throw new Error('Use apenas o banco descartável de carreira.');
  execFileSync(psql!, [
    ...args,
    '-c',
    `UPDATE career_periods SET started_at=now()-interval '2 days' WHERE club_id='${clubId}'; UPDATE games SET starts_at=now()-interval '1 hour' WHERE id='${gameId}' AND club_id='${clubId}';`,
  ]);
}
async function fixture() {
  const owner = await account('Alice da pelada');
  const member = await account('Beto da pelada');
  const groupResponse = await mutate(owner.ctx, '/groups', 'POST', {
    name: 'Pelada entre amigos',
    description: 'Toda presença tem história.',
  });
  expect(groupResponse.ok()).toBeTruthy();
  const group = await groupResponse.json();
  expect((await mutate(member.ctx, `/invites/${group.invite}/join`)).ok()).toBeTruthy();
  return { owner, member, group };
}
async function game(f: Awaited<ReturnType<typeof fixture>>, index = 1) {
  const response = await mutate(f.owner.ctx, `/groups/${f.group.id}/games`, 'POST', {
    title: `Encontro ${index}`,
    location: 'Quadra dos amigos',
    startsAt: new Date(Date.now() + 86400000).toISOString(),
    teamCount: 3,
    teamSize: 5,
  });
  expect(response.ok()).toBeTruthy();
  const detail = await response.json();
  for (const ctx of [f.owner.ctx, f.member.ctx])
    expect((await mutate(ctx, `/games/${detail.game.id}/attendance`)).ok()).toBeTruthy();
  timeFixture(f.group.id, detail.game.id);
  return detail.game.id as string;
}
async function signIn(page: Page, ctx: APIRequestContext) {
  await page.context().addCookies((await ctx.storageState()).cookies);
}
async function prepareCapture(page: Page) {
  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    window.scrollTo(0, 0);
  });
}

test('presenças conferidas, coleção privada, aviso persistente e figurinha compartilhada', async ({
  page,
}) => {
  const f = await fixture();
  try {
    await signIn(page, f.owner.ctx);
    await page.setViewportSize({ width: 1440, height: 1080 });
    await page.goto(`/#group/${f.group.id}`);
    await page.getByRole('tab', { name: 'Minha carreira', exact: true }).click();
    await page.getByRole('button', { name: 'Ativar conquistas', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Pausar conquistas' })).toBeVisible();
    const id = await game(f);
    expect(
      (await (await f.owner.ctx.get(`/api/groups/${f.group.id}/career/me`)).json()).appearances,
    ).toBe(0);
    await page.goto(`/#game/${id}`);
    await page.getByRole('tab', { name: 'Presenças', exact: true }).click();
    await expect(page.getByLabel('Presença de Alice da pelada')).toHaveValue('0: null');
    await expect(page.getByRole('button', { name: 'Revisar resumo' })).toBeDisabled();
    await page.getByLabel('Presença de Alice da pelada').selectOption({ label: 'Participou' });
    await page.getByLabel('Presença de Beto da pelada').selectOption({ label: 'Não participou' });
    await page.getByLabel('Esta pelada aconteceu').check();
    await page.getByRole('button', { name: 'Revisar resumo' }).click();
    await expect(page.locator('#attendance-summary')).toBeFocused();
    await page.getByRole('button', { name: 'Salvar presenças', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Presenças conferidas');
    await page.getByRole('tab', { name: 'Minha carreira', exact: true }).click();
    await expect(page.getByText('Você conquistou', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: 'Ver conquistas', exact: true }).click();
    await expect(page.locator('#career-collection')).toBeFocused();
    await page.getByRole('button', { name: 'Fechar aviso', exact: true }).click();
    await page.getByText('Personalizar figurinha', { exact: true }).click();
    await page.getByLabel('Tô dentro', { exact: true }).check();
    await page.getByLabel('Mostrar minha figurinha aos membros do grupo').check();
    await page.getByRole('button', { name: 'Salvar figurinha', exact: true }).click();
    await expect(page.getByText('Figurinha salva.', { exact: true })).toBeVisible();
    const publicCard = await (
      await f.member.ctx.get(`/api/groups/${f.group.id}/career/cards/${f.owner.user.id}`)
    ).json();
    expect(Object.keys(publicCard).sort()).toEqual([
      'badges',
      'frame',
      'name',
      'photoUrl',
      'playerId',
      'shared',
      'title',
    ]);
    expect(publicCard.badges).toEqual(['FIRST_APPEARANCE']);
    expect((await f.member.ctx.get(`/api/games/${id}/attendance-review`)).status()).toBe(403);
    expect(
      (await (await f.member.ctx.get(`/api/groups/${f.group.id}/career/me`)).json()).appearances,
    ).toBe(0);
    await page.reload();
    await page.getByRole('tab', { name: 'Minha carreira', exact: true }).click();
    await expect(page.getByText('Você conquistou', { exact: false })).toHaveCount(0);
    await prepareCapture(page);
    await page.screenshot({
      path: path.resolve('../docs/screenshots/carreira-desktop-claro.png'),
      fullPage: true,
    });
  } finally {
    await f.owner.ctx.dispose();
    await f.member.ctx.dispose();
  }
});

test('marcos desbloqueiam título e moldura; correção limpa recompensa indevida', async ({
  page,
}) => {
  const f = await fixture();
  try {
    expect(
      (
        await mutate(f.owner.ctx, `/groups/${f.group.id}/career/program`, 'PUT', { active: true })
      ).ok(),
    ).toBeTruthy();
    const ids: string[] = [];
    for (let i = 1; i <= 10; i++) {
      const id = await game(f, i);
      ids.push(id);
      expect(
        (
          await mutate(f.owner.ctx, `/games/${id}/attendance-review`, 'PUT', {
            version: 0,
            happened: true,
            players: [
              { playerId: f.owner.user.id, present: true },
              { playerId: f.member.user.id, present: true },
            ],
          })
        ).ok(),
      ).toBeTruthy();
    }
    await signIn(page, f.member.ctx);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/#career');
    await expect(page.getByText('Personalizar figurinha', { exact: true })).toBeVisible();
    await page.getByText('Personalizar figurinha', { exact: true }).click();
    await page.getByLabel('Título', { exact: true }).selectOption('FIVE_APPEARANCES');
    await page.getByLabel('Moldura', { exact: true }).selectOption('TEN_APPEARANCES');
    await page.getByLabel('Tô dentro', { exact: true }).check();
    await page.getByRole('button', { name: 'Salvar figurinha' }).click();
    await expect(page.locator('.figurinha')).toHaveClass(/carimbada/);
    await expect(page.locator('.figurinha h3')).toHaveText('Beto da pelada');
    await expect(page.locator('.figurinha')).not.toContainText(/nota|gols|vitórias/i);
    await page.setViewportSize({ width: 390, height: 844 });
    await prepareCapture(page);
    await page.screenshot({
      path: path.resolve('../docs/screenshots/carreira-celular-claro.png'),
      fullPage: true,
    });
    await page.getByRole('button', { name: 'Modo noturno', exact: true }).click();
    await prepareCapture(page);
    await page.screenshot({
      path: path.resolve('../docs/screenshots/carreira-celular-noturno.png'),
      fullPage: true,
    });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBeTruthy();
    expect(
      (
        await mutate(f.owner.ctx, `/games/${ids[0]}/attendance-review`, 'PUT', {
          version: 1,
          happened: true,
          players: [
            { playerId: f.owner.user.id, present: true },
            { playerId: f.member.user.id, present: false },
          ],
        })
      ).ok(),
    ).toBeTruthy();
    await page.reload();
    await expect(
      page.getByText('Uma conferência de presença foi corrigida.', { exact: false }),
    ).toBeVisible();
    await expect(page.locator('.figurinha')).not.toHaveClass(/carimbada/);
    expect(
      (await (await f.member.ctx.get(`/api/groups/${f.group.id}/career/me`)).json()).appearances,
    ).toBe(9);
  } finally {
    await f.owner.ctx.dispose();
    await f.member.ctx.dispose();
  }
});

test('conferência por teclado e layout de celular preservam o resumo', async ({ page }) => {
  const f = await fixture();
  try {
    await mutate(f.owner.ctx, `/groups/${f.group.id}/career/program`, 'PUT', { active: true });
    const id = await game(f);
    await signIn(page, f.owner.ctx);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/#game/${id}`);
    await page.getByRole('tab', { name: 'Presenças', exact: true }).click();
    const alice = page.getByLabel('Presença de Alice da pelada');
    await alice.focus();
    await alice.press('ArrowDown');
    await alice.press('Enter');
    await page.getByLabel('Presença de Beto da pelada').selectOption({ label: 'Participou' });
    await page.getByLabel('Esta pelada aconteceu').check();
    await page.getByRole('button', { name: 'Revisar resumo' }).click();
    await expect(page.locator('#attendance-summary')).toBeFocused();
    await prepareCapture(page);
    await page.screenshot({
      path: path.resolve('../docs/screenshots/presencas-celular.png'),
      fullPage: true,
    });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBeTruthy();
    await page.getByRole('button', { name: 'Salvar presenças', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Presenças conferidas');
  } finally {
    await f.owner.ctx.dispose();
    await f.member.ctx.dispose();
  }
});
