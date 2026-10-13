import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Entry page. Sign-in and role-based portal navigation are added in Phase 3 / Phase 8. */
@Component({
  selector: 'occpl-home',
  template: `
    <h1>OCCPL Admin Portal</h1>
    <p class="lead">Central management for companies, products, subscriptions and users.</p>
  `,
  styles: `
    .lead {
      color: var(--occpl-color-text-muted);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Home {}
