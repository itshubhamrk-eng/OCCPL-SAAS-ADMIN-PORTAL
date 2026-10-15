import { FormControl, FormGroup, Validators } from '@angular/forms';
import { ApiError } from '../../core/errors/api-error';
import { applyServerErrors, errorMessages } from './server-errors';

describe('server-errors', () => {
  const form = () =>
    new FormGroup({
      code: new FormControl(''),
      initialAdmin: new FormGroup({ email: new FormControl('') }),
    });

  const error = (
    fieldErrors: ApiError['fieldErrors'],
    message = 'Validation failed',
  ): ApiError => ({
    status: 400,
    code: 'VALIDATION_ERROR',
    message,
    fieldErrors,
  });

  it('puts server messages on matching (nested) controls and returns the rest', () => {
    const f = form();
    const rest = applyServerErrors(
      f,
      error([
        { field: 'code', code: 'Pattern', message: 'lower-case letters only' },
        { field: 'initialAdmin.email', code: 'Email', message: 'must be an email' },
        { field: 'unknown', code: 'X', message: 'other problem' },
      ]),
    );
    expect(f.get('code')?.errors?.['server']).toBe('lower-case letters only');
    expect(errorMessages(f.get('initialAdmin.email'))).toEqual(['must be an email']);
    expect(rest).toEqual(['unknown: other problem']);
  });

  it('returns the general message for errors without field details (e.g. 409 duplicate code)', () => {
    expect(
      applyServerErrors(form(), {
        ...error([], 'Company code already in use.'),
        status: 409,
        code: 'CONFLICT',
      }),
    ).toEqual(['Company code already in use.']);
  });

  it('describes client-side validation errors only after the user touched the field', () => {
    const control = new FormControl('', Validators.required);
    expect(errorMessages(control)).toEqual([]);
    control.markAsTouched();
    expect(errorMessages(control)).toEqual(['This field is required.']);
  });
});
