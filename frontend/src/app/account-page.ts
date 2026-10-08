import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { User } from './models';
import { CareerPage } from './career-page';

@Component({
  selector: 'app-account-page',
  standalone: true,
  imports: [FormsModule, CareerPage],
  templateUrl: './account-page.html',
  styleUrl: './account-page.css',
})
export class AccountPage implements OnInit {
  user = input.required<User>();
  updated = output<User>();
  private api = inject(Api);
  name = '';
  file = signal<File | null>(null);
  preview = signal('');
  busy = signal(false);
  error = signal('');
  notice = signal('');
  private selectionVersion = 0;
  ngOnInit() {
    this.name = this.user().name;
  }
  initials() {
    return this.user()
      .name.trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((n) => n[0])
      .join('')
      .toLocaleUpperCase('pt-BR');
  }
  choose(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    const version = ++this.selectionVersion;
    this.file.set(null);
    this.preview.set('');
    this.notice.set('');
    this.error.set('');
    if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 2 * 1024 * 1024) {
      this.error.set('Escolha uma foto JPG ou PNG de até 2 MB.');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      if (version !== this.selectionVersion) return;
      this.file.set(file);
      this.preview.set(String(reader.result));
    };
    reader.onerror = () => {
      if (version === this.selectionVersion)
        this.error.set('Não foi possível abrir essa foto. Escolha outro arquivo.');
    };
    reader.readAsDataURL(file);
  }
  cancelPhoto() {
    this.selectionVersion++;
    this.file.set(null);
    this.preview.set('');
  }
  async saveName() {
    const name = this.name.trim();
    if (!name) {
      this.error.set('Informe seu nome.');
      return;
    }
    await this.change(
      () => this.api.request<User>('/auth/profile', 'PUT', { name }),
      'Nome atualizado.',
    );
  }
  async savePhoto() {
    const file = this.file();
    if (!file) return;
    await this.change(
      () => this.api.upload<User>('/auth/profile/photo', file),
      'Foto atualizada.',
      true,
    );
  }
  async removePhoto() {
    await this.change(
      () => this.api.request<User>('/auth/profile/photo', 'DELETE'),
      'Foto removida.',
      true,
    );
  }
  private async change(action: () => Promise<User>, message: string, photo = false) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const user = await action();
      this.updated.emit(user);
      if (photo) this.cancelPhoto();
      else this.name = user.name;
      this.notice.set(message);
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
}
