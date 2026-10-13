import { InjectionToken } from '@angular/core';

/** Browser navigation and per-tab storage, injectable so that sign-in can be tested without leaving the page. */
export interface BrowserEnvironment {
  readonly origin: string;
  redirect(url: string): void;
  readonly sessionStorage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
}

export const BROWSER = new InjectionToken<BrowserEnvironment>('BROWSER', {
  providedIn: 'root',
  factory: () => ({
    origin: window.location.origin,
    redirect: (url: string) => window.location.assign(url),
    sessionStorage: window.sessionStorage,
  }),
});
