import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/** Simple message pages (signed out, no access, forbidden, not found); text comes from route data. */
@Component({
  selector: 'occpl-status-page',
  imports: [RouterLink],
  template: `
    <div class="status-page">
      <h1>{{ title() }}</h1>
      <p class="muted">{{ text() }}</p>
      <div class="row">
        @if (action() === 'signIn') {
          <button type="button" class="btn primary" (click)="signIn()">Sign in</button>
        } @else if (action() === 'mfa') {
          <button type="button" class="btn primary" (click)="signInWithMfa()">
            Sign in with authenticator
          </button>
        } @else {
          <a class="btn" routerLink="/">Go to start page</a>
        }
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusPage {
  private readonly auth = inject(AuthService);
  // Bound from route data; the router sets inputs missing from the data to undefined.
  readonly heading = input<string>();
  readonly message = input<string>();
  readonly action = input<'home' | 'signIn' | 'mfa'>();

  protected readonly title = computed(() => this.heading() ?? 'Page not found');
  protected readonly text = computed(
    () => this.message() ?? 'The page you requested does not exist.',
  );

  protected signIn(): void {
    void this.auth.login('/');
  }

  protected signInWithMfa(): void {
    void this.auth.login('/', { forceLogin: true });
  }
}
