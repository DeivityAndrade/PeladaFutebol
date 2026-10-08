import { test, expect, APIRequestContext } from '@playwright/test';
import path from 'node:path';

async function mutate(request: APIRequestContext, url: string, data: unknown) {
  const csrf = await (await request.get('/api/auth/csrf')).json();
  return request.post('/api' + url, { data, headers: { [csrf.headerName]: csrf.token } });
}
async function group(request: APIRequestContext) {
  const email = `wa-operations-${crypto.randomUUID()}@example.com`,
    password = 'PeladaTeste123!';
  expect(
    (await mutate(request, '/auth/register', { name: 'Ana do piloto', email, password })).ok(),
  ).toBeTruthy();
  expect((await mutate(request, '/auth/login', { email, password })).ok()).toBeTruthy();
  return (
    await (await mutate(request, '/groups', { name: 'Turma do piloto', description: '' })).json()
  ).id;
}

test('organizador ativa, pausa, consulta falhas e respostas sem confundir participantes de mesmo nome', async ({
  page,
  request,
}) => {
  const club = await group(request);
  let state = {
    available: true,
    deliveryAvailable: true,
    enabled: false,
    reminderMinutes: 120,
    states: { ACCEPTED: 2, DELIVERED: 1, UNKNOWN: 1 },
    responses: [
      {
        game_id: 'game1',
        player_id: 'player1',
        title: 'Sexta',
        starts_at: '2026-10-09T22:00:00Z',
        time_zone: 'America/Sao_Paulo',
        name: 'Beto',
        response: 'GO',
        attendance: 'CONFIRMED',
      },
      {
        game_id: 'game1',
        player_id: 'player2',
        title: 'Sexta',
        starts_at: '2026-10-09T22:00:00Z',
        time_zone: 'America/Sao_Paulo',
        name: 'Beto',
        response: 'GO',
        attendance: 'WAITING',
      },
    ],
  };
  let fail = true;
  await page.route(`**/api/whatsapp/groups/${club}/automation`, (route) => {
    if (route.request().method() === 'PUT') {
      if (fail) {
        fail = false;
        return route.fulfill({ status: 503, json: { message: 'Tente novamente.' } });
      }
      state = { ...state, ...route.request().postDataJSON() };
    }
    return route.fulfill({ json: state });
  });
  await page.context().addCookies((await request.storageState()).cookies);
  await page.goto('/#agenda');
  const panel = page.locator('app-whatsapp-operations');
  await panel.locator('summary').focus();
  await page.keyboard.press('Enter');
  await expect(panel.getByRole('checkbox')).not.toBeChecked();
  await expect(panel.locator('li')).toHaveCount(2);
  await expect(panel.locator('li').first()).toContainText('19:00');
  await expect(panel.locator('li').last()).toContainText('fila de espera');
  await expect(panel.getByText('Entrega a conferir', { exact: true })).toBeVisible();
  await panel.getByRole('checkbox').check();
  await panel.getByRole('combobox').selectOption({ label: '1 hora' });
  await panel.getByRole('button', { name: 'Salvar automação' }).click();
  await expect(panel.getByRole('alert')).toContainText('Tente novamente');
  expect(state.enabled).toBeFalsy();
  await panel.getByRole('button', { name: 'Salvar automação' }).click();
  await expect(panel.getByRole('status')).toContainText('Grupo piloto ativado');
  expect(state.reminderMinutes).toBe(60);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await panel.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/whatsapp-operacao-desktop.png'),
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Modo noturno' }).click();
  await panel.scrollIntoViewIfNeeded();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBeTruthy();
  await page.screenshot({
    path: path.resolve('../docs/screenshots/whatsapp-operacao-celular.png'),
  });
  await panel.getByRole('checkbox').uncheck();
  await panel.getByRole('button', { name: 'Salvar automação' }).click();
  await expect(panel.getByRole('status')).toContainText('Automação pausada');
  await page.reload();
  await panel.locator('summary').click();
  await expect(panel.getByRole('checkbox')).not.toBeChecked();
});

test('configuração incompleta bloqueia ativação e consulta pode ser recuperada', async ({
  page,
  request,
}) => {
  const club = await group(request);
  let fail = true;
  await page.route(`**/api/whatsapp/groups/${club}/automation`, (route) => {
    if (fail) {
      fail = false;
      return route.fulfill({ status: 503, json: { message: 'Servidor indisponível.' } });
    }
    return route.fulfill({
      json: {
        available: false,
        deliveryAvailable: false,
        enabled: false,
        reminderMinutes: 120,
        states: {},
        responses: [],
      },
    });
  });
  await page.context().addCookies((await request.storageState()).cookies);
  await page.goto('/#agenda');
  const panel = page.locator('app-whatsapp-operations');
  await panel.locator('summary').click();
  await expect(panel.getByRole('alert')).toContainText('Servidor indisponível');
  await panel.getByRole('button', { name: 'Atualizar avisos' }).click();
  await expect(panel.getByRole('checkbox')).toBeDisabled();
  await expect(panel.getByText(/Configure o agente/)).toBeVisible();
  await expect(panel.getByText('Nenhum envio registrado neste período.')).toBeVisible();
});
