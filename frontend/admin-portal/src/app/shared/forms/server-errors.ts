import { AbstractControl, FormGroup } from '@angular/forms';
import { ApiError } from '../../core/errors/api-error';

/**
 * Shows server validation errors (RFC 9457 {@code errors[]}) on the matching form controls
 * ({@code field} may be a dotted path such as {@code initialAdmin.email}). Returns messages that
 * matched no control, for display above the form.
 */
export function applyServerErrors(form: FormGroup, error: unknown): string[] {
  if (!isApiError(error)) {
    return [];
  }
  const unmatched: string[] = [];
  for (const violation of error.fieldErrors) {
    const control = findControl(form, violation.field);
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: violation.message });
      control.markAsTouched();
    } else {
      unmatched.push(
        violation.field ? `${violation.field}: ${violation.message}` : violation.message,
      );
    }
  }
  if (error.fieldErrors.length === 0) {
    unmatched.push(error.message);
  }
  return unmatched;
}

export function isApiError(error: unknown): error is ApiError {
  return typeof error === 'object' && error !== null && 'code' in error && 'fieldErrors' in error;
}

function findControl(form: FormGroup, field: string): AbstractControl | null {
  if (!field) {
    return null;
  }
  const path = field.replace(/\[(\d+)\]/g, '.$1');
  return form.get(path);
}

/** Human-readable messages for a control's current errors. */
export function errorMessages(control: AbstractControl | null): string[] {
  if (!control || !control.errors || !(control.touched || control.dirty)) {
    return [];
  }
  return Object.entries(control.errors).map(([key, value]) => {
    switch (key) {
      case 'required':
        return 'This field is required.';
      case 'email':
        return 'Enter a valid email address.';
      case 'maxlength':
        return `Use at most ${(value as { requiredLength: number }).requiredLength} characters.`;
      case 'minlength':
        return `Use at least ${(value as { requiredLength: number }).requiredLength} characters.`;
      case 'min':
        return `The minimum is ${(value as { min: number }).min}.`;
      case 'max':
        return `The maximum is ${(value as { max: number }).max}.`;
      case 'pattern':
        return 'The format is not valid.';
      case 'json':
        return String(value);
      case 'server':
        return String(value);
      default:
        return 'The value is not valid.';
    }
  });
}
