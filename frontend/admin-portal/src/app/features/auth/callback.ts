import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/** Redirect target of the identity provider: exchanges the code and continues to the original page. */
@Component({
  selector: 'occpl-auth-callback',
  imports: [RouterLink],
  template: `
    <main class="status-page">
      @if (error(); as message) {
        <h1>Sign-in did not complete</h1>
        <p>{{ message }}</p>
        <button type="button" class="btn primary" (click)="retry()">Sign in again</button>
      } @else {
        <p role="status">Signing you in…</p>
      }
      <p><a routerLink="/signed-out" class="small">Cancel</a></p>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallback implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const returnUrl = await this.auth.handleCallback(new URLSearchParams(window.location.search));
      await this.router.navigateByUrl(returnUrl, { replaceUrl: true });
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : 'Sign-in failed.');
    }
  }

  protected retry(): void {
    void this.auth.login('/');
  }
}
