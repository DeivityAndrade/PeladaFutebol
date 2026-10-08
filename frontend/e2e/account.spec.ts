import { test, expect, APIRequestContext } from '@playwright/test';
import path from 'node:path';

async function mutate(ctx: APIRequestContext, url: string, method: string, data?: unknown) {
  const csrf = await (await ctx.get('/api/auth/csrf')).json();
  return ctx.fetch('/api' + url, { method, data, headers: { [csrf.headerName]: csrf.token } });
}
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
  'base64',
);

test('perfil geral edita nome e foto, persiste e respeita acesso próprio', async ({
  page,
  request,
  browser,
}) => {
  const email = `profile-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  expect(
    (
      await mutate(request, '/auth/register', 'POST', { name: 'Ana da pelada', email, password })
    ).ok(),
  ).toBeTruthy();
  const login = await mutate(request, '/auth/login', 'POST', { email, password });
  expect(login.ok()).toBeTruthy();
  const group = await (
    await mutate(request, '/groups', 'POST', { name: 'Turma do sábado', description: '' })
  ).json();
  await page.context().addCookies((await request.storageState()).cookies);
  await page.goto('/#groups');
  const entry = page.getByRole('link', { name: 'Abrir minha carreira e perfil' });
  await entry.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Minha carreira.', exact: true })).toBeVisible();
  await expect(page.getByLabel('Escolher grupo')).toHaveValue(group.id);
  await page.getByLabel('Nome', { exact: true }).fill('Ana dos amigos');
  await page.getByRole('button', { name: 'Salvar nome', exact: true }).click();
  await expect(page.getByText('Nome atualizado.', { exact: true })).toBeVisible();
  await expect(page.locator('app-figurinha h3')).toHaveText('Ana dos amigos');
  expect((await (await request.get('/api/auth/me')).json()).email).toBe(email);
  const photoData = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 192;
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#1e7a3c';
    context.fillRect(0, 0, 192, 192);
    context.fillStyle = '#d9f55a';
    context.font = 'bold 96px sans-serif';
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.fillText('A', 96, 102);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  await page.getByLabel('Escolher foto de perfil').setInputFiles({
    name: 'foto.png',
    mimeType: 'image/png',
    buffer: Buffer.from(photoData, 'base64'),
  });
  await expect(page.getByAltText('Sua foto de perfil')).toHaveAttribute('src', /^data:image\/png/);
  await page.getByRole('button', { name: 'Salvar foto', exact: true }).click();
  await expect(page.getByText('Foto atualizada.', { exact: true })).toBeVisible();
  const photoUrl = (await (await request.get('/api/auth/me')).json()).photoUrl;
  const photo = await request.get(photoUrl);
  expect(photo.status()).toBe(200);
  expect(photo.headers()['cache-control']).toBe('no-store');
  await expect(page.locator('.account-avatar img')).toHaveAttribute('src', photoUrl);
  await expect(page.locator('app-figurinha img')).toHaveAttribute('src', photoUrl);
  await page.reload();
  await expect(page.getByLabel('Nome', { exact: true })).toHaveValue('Ana dos amigos');
  await expect(page.getByAltText('Sua foto de perfil')).toHaveJSProperty('naturalWidth', 192);
  const anonymous = await browser.newContext();
  expect((await anonymous.request.get(photoUrl)).status()).toBe(401);
  await anonymous.close();
  // Mutations without the session's CSRF token are refused.
  expect((await request.put('/api/auth/profile', { data: { name: 'Outro' } })).status()).toBe(403);
  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.evaluate(() => {
    (document.activeElement as HTMLElement)?.blur();
    window.scrollTo(0, 0);
  });
  await page.screenshot({
    path: path.resolve('../docs/screenshots/perfil-geral-desktop.png'),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  await page.evaluate(() => {
    (document.activeElement as HTMLElement)?.blur();
    window.scrollTo(0, 0);
  });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();
  await expect(entry).toBeVisible();
  await expect(entry.locator('.account-avatar')).toBeVisible();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/perfil-geral-celular.png'),
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Remover foto', exact: true }).click();
  await expect(page.getByText('Foto removida.', { exact: true })).toBeVisible();
  await expect(page.locator('.account-avatar img')).toHaveCount(0);
  expect((await request.get(photoUrl)).status()).toBe(404);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Escolher foto', exact: true })).toBeVisible();
});

test('perfil recupera falha de gravação e permite cancelar a prévia', async ({ page, request }) => {
  const email = `profile-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  await mutate(request, '/auth/register', 'POST', { name: 'Beto da pelada', email, password });
  await mutate(request, '/auth/login', 'POST', { email, password });
  await page.context().addCookies((await request.storageState()).cookies);
  await page.goto('/#career');
  await page
    .getByLabel('Escolher foto de perfil')
    .setInputFiles({ name: 'foto.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') });
  await expect(page.getByRole('alert')).toContainText('JPG ou PNG');
  await page
    .getByLabel('Escolher foto de perfil')
    .setInputFiles({ name: 'foto.png', mimeType: 'image/png', buffer: png });
  await page.getByRole('button', { name: 'Cancelar escolha', exact: true }).click();
  await expect(page.getByAltText('Sua foto de perfil')).toHaveCount(0);
  await page.route('**/api/auth/profile', (route) =>
    route.fulfill({ status: 503, json: { message: 'Tente novamente em instantes.' } }),
  );
  await page.getByLabel('Nome', { exact: true }).fill('Beto dos amigos');
  await page.getByRole('button', { name: 'Salvar nome', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Tente novamente');
  await expect(page.getByLabel('Nome', { exact: true })).toHaveValue('Beto dos amigos');
  await page.unroute('**/api/auth/profile');
  await page.getByRole('button', { name: 'Salvar nome', exact: true }).click();
  await expect(page.getByText('Nome atualizado.', { exact: true })).toBeVisible();
});
