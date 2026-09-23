import { Component, Input } from '@angular/core';
const paths: Record<string, string> = {
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  calendar:
    'M8 2v4 M16 2v4 M3 10h18 M5 4h14a2 2 0 0 1 2 2v14H3V6a2 2 0 0 1 2-2 M7 14h2 M12 14h2 M7 18h2',
  users:
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M9 3a4 4 0 1 1 0 8 4 4 0 0 1 0-8',
  pitch: 'M3 3h18v18H3z M3 12h18 M9 3v4h6V3 M9 21v-4h6v4 M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  chevron: 'M9 5l7 7-7 7',
  down: 'M6 9l6 6 6-6',
  plus: 'M12 5v14 M5 12h14',
  check: 'M5 12l4 4L19 6',
  close: 'M6 6l12 12 M18 6L6 18',
  pin: 'M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0 M12 7a3 3 0 1 1 0 6 3 3 0 0 1 0-6',
  clock: 'M12 8v5l3 2 M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20',
  share: 'M12 16V3 M7 8l5-5 5 5 M5 13v8h14v-8',
  logout: 'M9 5H3v14h6 M10 12h11 M17 8l4 4-4 4',
  shield: 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M9 12l2 2 4-4',
  info: 'M12 11v6 M12 7h.01 M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20',
  trophy: 'M8 3h8v7a4 4 0 0 1-8 0z M8 5H4v3a4 4 0 0 0 4 4 M16 5h4v3a4 4 0 0 1-4 4 M12 14v6 M8 21h8',
  star: 'M12 2l3.1 6.3 7 .9-5.1 5 .9 7-6.9-3.7-6.9 3.7.9-7-5.1-5 7-.9z',
  ball: 'M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20 M12 7l5 4-2 6H9l-2-6z M12 2v5 M2 9l5 2 M5 20l4-3 M19 20l-4-3 M22 9l-5 2',
  copy: 'M9 9h12v12H9z M15 9V3H3v12h6',
  edit: 'M16 3l5 5-12 12H4v-5z M13 6l5 5',
  shuffle: 'M16 3h5v5 M4 20 21 3 M21 16v5h-5 M15 15l6 6 M4 4l5 5',
  lock: 'M5 10h14v11H5z M8 10V6a4 4 0 0 1 8 0v4',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
};
@Component({
  selector: 'app-icon',
  standalone: true,
  template:
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="path"/></svg>',
  styles: [
    ':host{display:inline-flex;width:20px;height:20px;flex-shrink:0}svg{width:100%;height:100%}',
  ],
})
export class Icon {
  @Input() name = 'ball';
  get path() {
    return paths[this.name] || paths['ball'];
  }
}
