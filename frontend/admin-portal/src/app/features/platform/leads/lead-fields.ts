import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FieldError } from '../../../shared/forms/field-error';

export const PHONE_PATTERN = /^[0-9+()\-. ]{5,32}$/;

/** Shared fields of the register and edit lead forms. */
@Component({
  selector: 'occpl-lead-fields',
  imports: [ReactiveFormsModule, FieldError],
  template: `
    <div class="form" [formGroup]="group()">
      <div class="field">
        <label for="l-companyName">Company name</label>
        <input
          id="l-companyName"
          formControlName="companyName"
          aria-describedby="l-companyName-error"
        />
        <occpl-field-error [control]="group().get('companyName')" errorId="l-companyName-error" />
      </div>
      <div class="field">
        <label for="l-contactName">Contact name</label>
        <input
          id="l-contactName"
          formControlName="contactName"
          aria-describedby="l-contactName-error"
        />
        <occpl-field-error [control]="group().get('contactName')" errorId="l-contactName-error" />
      </div>
      <div class="field">
        <label for="l-email">Email</label>
        <input id="l-email" type="email" formControlName="email" aria-describedby="l-email-error" />
        <occpl-field-error [control]="group().get('email')" errorId="l-email-error" />
      </div>
      <div class="field">
        <label for="l-phone">Phone</label>
        <input id="l-phone" formControlName="phone" aria-describedby="l-phone-error" />
        <occpl-field-error [control]="group().get('phone')" errorId="l-phone-error" />
      </div>
      <div class="field">
        <label for="l-productInterest">Product interest</label>
        <input
          id="l-productInterest"
          formControlName="productInterest"
          aria-describedby="l-productInterest-error"
        />
        <occpl-field-error
          [control]="group().get('productInterest')"
          errorId="l-productInterest-error"
        />
      </div>
      <div class="field">
        <label for="l-source">Source</label>
        <input
          id="l-source"
          formControlName="source"
          placeholder="Website, referral, event…"
          aria-describedby="l-source-error"
        />
        <occpl-field-error [control]="group().get('source')" errorId="l-source-error" />
      </div>
      <div class="field">
        <label for="l-notes">Notes</label>
        <textarea
          id="l-notes"
          rows="4"
          formControlName="notes"
          aria-describedby="l-notes-error"
        ></textarea>
        <occpl-field-error [control]="group().get('notes')" errorId="l-notes-error" />
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LeadFields {
  readonly group = input.required<FormGroup>();
}
