import { Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActionDialog } from './action-dialog';

@Component({
  imports: [ActionDialog],
  template: `<occpl-action-dialog
    #d
    heading="Suspend company"
    confirmLabel="Suspend"
    reason="required"
    (confirmed)="reason = $event"
  />`,
})
class Host {
  readonly dialog = viewChild.required<ActionDialog>('d');
  reason: string | null | undefined;
}

describe('ActionDialog', () => {
  it('requires a reason before confirming and emits it trimmed', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    fixture.componentInstance.dialog().open();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const confirm = el.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);

    const textarea = el.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = '  unpaid invoices ';
    textarea.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(confirm.disabled).toBe(false);
    confirm.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.reason).toBe('unpaid invoices');
  });
});
