import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FieldError } from '../../../shared/forms/field-error';

/** Shared fields of the register and edit product forms. */
@Component({
  selector: 'occpl-product-fields',
  imports: [ReactiveFormsModule, FieldError],
  template: `
    <div class="form" [formGroup]="group()">
      <div class="field">
        <label for="p-name">Name</label>
        <input id="p-name" formControlName="name" aria-describedby="p-name-error" />
        <occpl-field-error [control]="group().get('name')" errorId="p-name-error" />
      </div>
      <div class="field">
        <label for="p-appUrl">App URL</label>
        <input
          id="p-appUrl"
          formControlName="appUrl"
          placeholder="https://notes.occpl.com"
          aria-describedby="p-appUrl-hint p-appUrl-error"
        />
        <span class="hint" id="p-appUrl-hint"
          >Where users open the product (HTTPS; HTTP only for localhost)</span
        >
        <occpl-field-error [control]="group().get('appUrl')" errorId="p-appUrl-error" />
      </div>
      <div class="field">
        <label for="p-apiBaseUrl">API base URL</label>
        <input
          id="p-apiBaseUrl"
          formControlName="apiBaseUrl"
          aria-describedby="p-api-hint p-apiBaseUrl-error"
        />
        <span class="hint" id="p-api-hint"
          >Where the platform calls the product to set companies up</span
        >
        <occpl-field-error [control]="group().get('apiBaseUrl')" errorId="p-apiBaseUrl-error" />
      </div>
      <div class="field">
        <label for="p-apiAudience">Token audience</label>
        <input
          id="p-apiAudience"
          formControlName="apiAudience"
          aria-describedby="p-aud-hint p-apiAudience-error"
        />
        <span class="hint" id="p-aud-hint"
          >Audience the product service accepts in tokens (default product-&lt;code&gt;)</span
        >
        <occpl-field-error [control]="group().get('apiAudience')" errorId="p-apiAudience-error" />
      </div>
      <div class="field">
        <label for="p-serviceClientId">Service client</label>
        <input
          id="p-serviceClientId"
          formControlName="serviceClientId"
          aria-describedby="p-svc-hint p-serviceClientId-error"
        />
        <span class="hint" id="p-svc-hint"
          >Keycloak client the product uses to call the platform</span
        >
        <occpl-field-error
          [control]="group().get('serviceClientId')"
          errorId="p-serviceClientId-error"
        />
      </div>
      <div class="field full">
        <label for="p-description">Description</label>
        <textarea id="p-description" formControlName="description" rows="3"></textarea>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductFields {
  readonly group = input.required<FormGroup>();
}
