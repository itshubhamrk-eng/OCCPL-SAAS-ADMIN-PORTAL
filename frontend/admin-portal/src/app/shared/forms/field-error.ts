import { ChangeDetectionStrategy, Component, computed, effect, input, signal } from '@angular/core';
import { AbstractControl } from '@angular/forms';
import { errorMessages } from './server-errors';

/** Error text under a form field; give it the id referenced by the input's aria-describedby. */
@Component({
  selector: 'occpl-field-error',
  template: `
    @for (message of messages(); track message) {
      <p class="field-error" [id]="errorId()">{{ message }}</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FieldError {
  readonly control = input.required<AbstractControl | null>();
  readonly errorId = input<string | null>(null);

  /** Bumped on every value, status, touched or pristine change of the control. */
  private readonly version = signal(0);

  protected readonly messages = computed(() => {
    this.version();
    return errorMessages(this.control());
  });

  constructor() {
    effect((onCleanup) => {
      const control = this.control();
      if (!control) {
        return;
      }
      const subscription = control.events.subscribe(() => this.version.update((v) => v + 1));
      onCleanup(() => subscription.unsubscribe());
    });
  }
}
