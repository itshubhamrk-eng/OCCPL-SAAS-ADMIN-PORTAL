import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Page title with optional back link, subtitle and projected action buttons. */
@Component({
  selector: 'occpl-page-header',
  imports: [RouterLink],
  template: `
    @if (backLink(); as back) {
      <a class="back small" [routerLink]="back">← {{ backLabel() }}</a>
    }
    <div class="row header">
      <div class="titles">
        <h1>{{ heading() }}</h1>
        @if (subtitle()) {
          <p class="muted subtitle">{{ subtitle() }}</p>
        }
      </div>
      <span class="spacer"></span>
      <div class="row"><ng-content /></div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      margin-bottom: var(--occpl-space-6);
    }
    .back {
      display: inline-block;
      margin-bottom: var(--occpl-space-2);
    }
    .titles {
      min-width: 0;
    }
    .subtitle {
      margin: var(--occpl-space-1) 0 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageHeader {
  readonly heading = input.required<string>();
  readonly subtitle = input<string | null>(null);
  readonly backLink = input<string | unknown[] | null>(null);
  readonly backLabel = input('Back');
}
