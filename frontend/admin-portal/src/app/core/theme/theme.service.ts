import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'occpl.theme';

/**
 * Light/dark theme. Until the user picks one the browser setting decides (the CSS media query);
 * a pick is stored in this browser and applied as {@code data-theme} on the root element.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly picked = signal<Theme | null>(null);
  private readonly systemDark = signal(false);

  /** The theme in effect: the user's pick, otherwise the browser's. */
  readonly effective = computed<Theme>(
    () => this.picked() ?? (this.systemDark() ? 'dark' : 'light'),
  );

  init(): void {
    const view = this.document.defaultView;
    const saved = this.read();
    if (saved) {
      this.picked.set(saved);
    }
    const media = view?.matchMedia?.('(prefers-color-scheme: dark)');
    this.systemDark.set(media?.matches ?? false);
    media?.addEventListener?.('change', (event) => this.systemDark.set(event.matches));
    this.apply();
  }

  toggle(): void {
    this.set(this.effective() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this.picked.set(theme);
    try {
      this.document.defaultView?.localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // Storage can be blocked (private mode); the choice then lasts until the page is closed.
    }
    this.apply();
  }

  private read(): Theme | null {
    try {
      const value = this.document.defaultView?.localStorage.getItem(STORAGE_KEY);
      return value === 'light' || value === 'dark' ? value : null;
    } catch {
      return null;
    }
  }

  private apply(): void {
    const root = this.document.documentElement;
    const theme = this.picked();
    if (theme) {
      root.setAttribute('data-theme', theme);
    } else {
      root.removeAttribute('data-theme');
    }
  }
}
