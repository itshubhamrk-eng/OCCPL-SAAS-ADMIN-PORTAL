import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'occpl-not-found',
  imports: [RouterLink],
  template: `
    <h1>Page not found</h1>
    <p>The page you requested does not exist or you do not have access to it.</p>
    <a routerLink="/">Go to the home page</a>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFound {}
