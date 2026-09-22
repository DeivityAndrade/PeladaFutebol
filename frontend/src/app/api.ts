import { Injectable } from '@angular/core';

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
@Injectable({ providedIn: 'root' })
export class Api {
  private csrf = '';
  async token() {
    const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    if (!response.ok)
      throw new ApiError(
        response.status,
        'Não foi possível iniciar uma sessão segura. Atualize a página.',
      );
    this.csrf = (await response.json()).token;
  }
  async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    if (method !== 'GET' && !this.csrf) await this.token();
    let response: Response;
    try {
      response = await fetch('/api' + path, {
        method,
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          ...(method !== 'GET' ? { 'X-CSRF-TOKEN': this.csrf } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw new ApiError(0, 'Sem conexão com o servidor. Confira sua internet e tente novamente.');
    }
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      if (response.status === 403) this.csrf = '';
      throw new ApiError(
        response.status,
        data.message || 'Não foi possível concluir a ação. Tente novamente.',
      );
    }
    if (path === '/auth/login' || path === '/auth/logout') this.csrf = '';
    return response.status === 204 ? (undefined as T) : response.json();
  }
}
