import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { ErrorNotifier } from '../../core/errors/error-notifier';

@Component({
  selector: 'occpl-shell',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly notifier = inject(ErrorNotifier);
}
