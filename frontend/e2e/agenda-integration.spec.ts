import { APIRequestContext, expect, request, test } from '@playwright/test';
test.use({ timezoneId: 'America/Sao_Paulo' });

async function mutate(api: APIRequestContext, path: string, data: unknown) {
  const csrf = await (await api.get('/api/auth/csrf')).json();
  const response = await api.post('/api' + path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok(), `Falha em ${path}: ${response.status()}`).toBeTruthy();
  return response.json();
}

test('jogo criado pelo calendário é persistido e pode ser reaberto após recarregar', async ({
  page,
}) => {
  const api = await request.newContext({
    baseURL: process.env['BASE_URL'] || 'http://127.0.0.1:8080',
  });
  try {
    const email = `agenda-${crypto.randomUUID()}@example.com`;
    const password = 'AgendaTeste123!';
    await mutate(api, '/auth/register', { name: 'Organizador da agenda', email, password });
    await mutate(api, '/auth/login', { email, password });
    const club = await mutate(api, '/groups', {
      name: 'Agenda integrada',
      description: 'Verificação local do calendário.',
      timeZone: 'America/Sao_Paulo',
      barbecueFrequency: 'NONE',
    });
    await page.context().addCookies((await api.storageState()).cookies);
    await page.goto('/#agenda');
    await expect(page.locator('.calendar-grid')).toBeVisible();
    const selectedDate = (await page.locator('.selected').getAttribute('id'))!.replace(
      'agenda-day-',
      '',
    );
    // Move into the next month to ensure the first occurrence is in the future.
    await page.getByRole('button', { name: 'Próximo mês', exact: true }).click();
    await expect(page.locator('.calendar-day.selected')).not.toHaveAttribute(
      'id',
      'agenda-day-' + selectedDate,
    );
    const targetDate = (await page.locator('.selected').getAttribute('id'))!.replace(
      'agenda-day-',
      '',
    );
    expect(targetDate).not.toBe(selectedDate);
    await page.getByRole('button', { name: 'Marcar neste dia' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Nome da pelada', { exact: true }).fill('Jogo criado pela agenda');
    await dialog.getByLabel('Local e quadra', { exact: true }).fill('Quadra da integração');
    await expect(dialog.getByLabel('Data', { exact: true })).toHaveValue(targetDate);
    await dialog.getByLabel('Horário', { exact: true }).fill('19:30');
    await dialog.getByRole('button', { name: 'Marcar pelada', exact: true }).click();
    await expect(page).toHaveURL(/#game\//);
    const gameUrl = page.url();
    const games = await (await api.get(`/api/groups/${club.id}/games`)).json();
    expect(games).toHaveLength(1);
    expect(games[0].title).toBe('Jogo criado pela agenda');
    await page.locator('.top-nav').getByRole('link', { name: 'Agenda de jogos' }).click();
    await page.getByRole('button', { name: 'Mostrar próximo jogo no calendário' }).click();
    await expect(page.locator('.fixture-detail')).toContainText('Jogo criado pela agenda');
    await page.reload();
    await page.getByRole('button', { name: 'Mostrar próximo jogo no calendário' }).click();
    await expect(page.locator('.fixture-time')).toHaveText('19:30');
    await page.getByRole('button', { name: 'Abrir pelada', exact: true }).click();
    await expect(page).toHaveURL(gameUrl);
    await expect(page.locator('.match-meta')).toContainText('Quadra da integração');
    expect(await (await api.get(`/api/groups/${club.id}/games`)).json()).toHaveLength(1);
  } finally {
    await api.dispose();
  }
});
