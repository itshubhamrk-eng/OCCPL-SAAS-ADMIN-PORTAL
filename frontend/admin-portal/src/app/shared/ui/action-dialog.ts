import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';

/**
 * Accessible confirmation dialog (native {@code <dialog>}, focus trapped by the browser) with an
 * optional or required reason. Open it with a template reference: {@code dialog.open()}.
 */
@Component({
  selector: 'occpl-action-dialog',
  imports: [FormsModule],
  template: `
    <dialog #dialog [attr.aria-labelledby]="titleId" (close)="onClose()">
      <form method="dialog" (submit)="confirm($event)">
        <h2 [id]="titleId">{{ heading() }}</h2>
        @if (message()) {
          <p>{{ message() }}</p>
        }
        @if (reason() !== 'none') {
          <div class="field">
            <label [for]="titleId + '-reason'"
              >Reason{{ reason() === 'optional' ? ' (optional)' : '' }}</label
            >
            <textarea
              [id]="titleId + '-reason'"
              name="reason"
              maxlength="500"
              rows="3"
              [(ngModel)]="text"
              [required]="reason() === 'required'"
            ></textarea>
          </div>
        }
        <div class="row actions">
          <span class="spacer"></span>
          <button type="button" class="btn" (click)="dialog.close()">Cancel</button>
          <button
            type="submit"
            class="btn"
            [class.danger]="danger()"
            [class.primary]="!danger()"
            [disabled]="!valid()"
          >
            {{ confirmLabel() }}
          </button>
        </div>
      </form>
    </dialog>
  `,
  styles: `
    :host {
      display: contents;
    }
    dialog {
      width: min(32rem, calc(100vw - 2rem));
      border: 1px solid var(--occpl-color-border);
      border-radius: var(--occpl-radius-md);
      background: var(--occpl-color-surface);
      color: var(--occpl-color-text);
      padding: var(--occpl-space-6);
    }
    dialog::backdrop {
      background: rgb(0 0 0 / 45%);
    }
    .actions {
      margin-top: var(--occpl-space-4);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActionDialog {
  private static nextId = 0;

  readonly heading = input.required<string>();
  readonly message = input<string | null>(null);
  readonly confirmLabel = input('Confirm');
  readonly danger = input(false);
  readonly reason = input<'none' | 'optional' | 'required'>('none');
  readonly confirmed = output<string | null>();

  protected readonly titleId = `occpl-dialog-${ActionDialog.nextId++}`;
  protected readonly textValue = signal('');
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  protected get text(): string {
    return this.textValue();
  }

  protected set text(value: string) {
    this.textValue.set(value);
  }

  open(): void {
    this.textValue.set('');
    const el = this.dialog().nativeElement;
    if (typeof el.showModal === 'function') {
      el.showModal();
    } else {
      el.setAttribute('open', '');
    }
  }

  protected valid(): boolean {
    return this.reason() !== 'required' || this.textValue().trim().length > 0;
  }

  protected confirm(event: Event): void {
    event.preventDefault();
    if (!this.valid()) {
      return;
    }
    const value = this.textValue().trim();
    this.confirmed.emit(value === '' ? null : value);
    this.close();
  }

  protected onClose(): void {
    this.textValue.set('');
  }

  private close(): void {
    const el = this.dialog().nativeElement;
    if (typeof el.close === 'function') {
      el.close();
    } else {
      el.removeAttribute('open');
    }
  }
}
