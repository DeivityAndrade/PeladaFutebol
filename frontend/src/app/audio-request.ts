import { Component, OnDestroy, inject, input, output, signal } from '@angular/core';
import { Api } from './api';
import { Icon } from './icon';

@Component({
  selector: 'app-audio-request',
  standalone: true,
  imports: [Icon],
  templateUrl: './audio-request.html',
  styleUrl: './audio-request.css',
})
export class AudioRequest implements OnDestroy {
  endpoint = input.required<string>();
  disabled = input(false);
  transcript = output<string>();
  processing = output<boolean>();
  capture = output<boolean>();
  private api = inject(Api);
  private recorder?: MediaRecorder;
  private stream?: MediaStream;
  private timer?: ReturnType<typeof setInterval>;
  private destroyed = false;
  private cancelled = false;
  private file?: File;
  private chunks: Blob[] = [];
  private bytes = 0;
  recording = signal(false);
  requesting = signal(false);
  busy = signal(false);
  seconds = signal(0);
  preview = signal('');
  error = signal('');
  supported = typeof MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
  private focus(id: string) {
    requestAnimationFrame(() => {
      if (!this.destroyed) document.getElementById(id)?.focus();
    });
  }

  async record() {
    if (this.disabled() || this.recording() || this.requesting() || this.busy()) return;
    this.error.set('');
    this.requesting.set(true);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (this.destroyed) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      this.discard();
      this.stream = stream;
      this.cancelled = false;
      const mime = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus'].find((t) =>
        MediaRecorder.isTypeSupported(t),
      );
      if (!mime) throw new Error('Formato não suportado');
      const recorder = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 64000 });
      this.recorder = recorder;
      this.chunks = [];
      this.bytes = 0;
      this.seconds.set(0);
      recorder.ondataavailable = (event) => {
        this.bytes += event.data.size;
        if (this.bytes > 2 * 1024 * 1024) {
          this.cancelled = true;
          this.error.set('A gravação passou de 2 MB. Grave um pedido mais curto.');
          this.stop();
        } else this.chunks.push(event.data);
      };
      recorder.onstop = () => {
        this.release();
        this.recording.set(false);
        this.capture.emit(false);
        if (this.destroyed || this.cancelled) {
          this.chunks = [];
          return;
        }
        const blob = new Blob(this.chunks, { type: recorder.mimeType });
        this.chunks = [];
        if (!blob.size) {
          this.error.set('A gravação ficou vazia. Tente novamente.');
          return;
        }
        const ext = mime.includes('mp4') ? 'm4a' : mime.includes('ogg') ? 'ogg' : 'webm';
        this.file = new File([blob], `pedido.${ext}`, { type: blob.type });
        this.preview.set(URL.createObjectURL(blob));
        this.focus('audio-transcribe');
      };
      recorder.onerror = () => {
        this.cancelled = true;
        this.error.set('A gravação foi interrompida. Tente novamente.');
        this.stop();
        this.release();
        this.recording.set(false);
        this.capture.emit(false);
      };
      recorder.start(1000);
      this.recording.set(true);
      this.capture.emit(true);
      this.focus('audio-stop');
      this.timer = setInterval(() => {
        this.seconds.update((s) => s + 1);
        if (this.seconds() >= 60) this.stop();
      }, 1000);
    } catch {
      this.release();
      this.error.set(
        'Não consegui acessar o microfone. Libere a permissão do site ou escreva seu pedido.',
      );
    } finally {
      if (!this.destroyed) this.requesting.set(false);
    }
  }
  stop() {
    if (this.recorder?.state === 'recording') this.recorder.stop();
    this.release();
  }
  private release() {
    clearInterval(this.timer);
    this.timer = undefined;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = undefined;
  }
  discard(userAction = false) {
    this.cancelled = true;
    this.stop();
    this.file = undefined;
    this.chunks = [];
    if (this.preview()) URL.revokeObjectURL(this.preview());
    this.preview.set('');
    if (userAction) this.focus('audio-record');
  }
  async transcribe() {
    if (!this.file || this.busy() || this.disabled()) return;
    this.busy.set(true);
    this.processing.emit(true);
    this.error.set('');
    try {
      const result = await this.api.upload<{ text: string }>(this.endpoint(), this.file);
      if (this.destroyed) return;
      this.transcript.emit(result.text);
      this.discard();
    } catch (e) {
      if (!this.destroyed)
        this.error.set(
          e instanceof Error ? e.message : 'Não consegui transcrever. Tente novamente.',
        );
    } finally {
      if (!this.destroyed) {
        this.busy.set(false);
        this.processing.emit(false);
      }
    }
  }
  ngOnDestroy() {
    this.destroyed = true;
    this.discard();
  }
}
