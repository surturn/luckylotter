import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ImportPreview, ImportResult } from '../core/api.models';

/** Our fields, in the order the mapping form presents them. */
interface FieldSpec {
  key: string;
  label: string;
  hint: string;
  required: boolean;
}

/**
 * Bulk import of a POS transaction export (FR-1, §12).
 *
 * <p>Three steps: choose a file, confirm which of its columns holds what, then
 * import. The mapping step exists because no two till vendors agree on column
 * names — "Cust ID", "Receipt No", "Total" — and demanding an exact header
 * would mean asking a café owner to rewrite a spreadsheet before they can use
 * the product.
 *
 * <p>Rows go through the same ingestion service as the API, so an imported
 * transaction behaves identically: same customer upsert, same cadence
 * recomputation, same idempotency, same flag resolution.
 */
@Component({
  selector: 'app-import',
  standalone: true,
  imports: [DecimalPipe, FormsModule],
  template: `
    <h1>Import POS transactions</h1>
    <p class="muted small" style="margin:4px 0 var(--space-5)">
      Upload an export from your till system. Rows you've already imported are skipped, so
      re-uploading the same file is safe.
    </p>

    <div class="grid">
      <section class="card block">
        <h2>1. Choose a file</h2>

        <div class="field" style="margin-top:var(--space-4)">
          <label for="file">CSV file</label>
          <input id="file" type="file" accept=".csv,text/csv" (change)="pick($event)" />
          <p class="hint">
            Any column names — you'll match them to ours in the next step.
          </p>
        </div>

        @if (preview(); as detected) {
          <h2 style="margin-top:var(--space-6)">2. Match your columns</h2>
          <p class="hint" style="margin-bottom:var(--space-4)">
            We've guessed where we could. Check each one — especially the transaction ID, which is
            what stops a row being counted twice.
          </p>

          @for (field of fields; track field.key) {
            <div class="field">
              <label [for]="'map-' + field.key">
                {{ field.label }}
                @if (!field.required) { <span class="muted small">(optional)</span> }
              </label>
              <select [id]="'map-' + field.key" [(ngModel)]="mapping[field.key]"
                      [name]="field.key"
                      [class.invalid]="field.required && !mapping[field.key] && attempted()">
                <option value="">— not in this file —</option>
                @for (column of detected.columns; track column) {
                  <option [value]="column">{{ column }}</option>
                }
              </select>
              <p class="hint">
                {{ field.hint }}
                @if (sampleFor(field.key); as sample) {
                  <br /><span class="sample">e.g. {{ sample }}</span>
                }
              </p>
            </div>
          }

          @if (attempted() && !complete()) {
            <p class="error-text" role="alert">
              Every required field needs a column before anything can be imported.
            </p>
          }

          <button type="button" class="btn-primary" [disabled]="busy()" (click)="upload()">
            {{ busy() ? 'Importing…' : 'Import transactions' }}
          </button>
        }

        @if (error(); as message) {
          <div class="banner banner-error" role="alert" style="margin-top:var(--space-4)">
            <span>{{ message }}</span>
          </div>
        }
      </section>

      <section class="card block">
        <h2>Result</h2>
        @if (result(); as summary) {
          <dl class="tally">
            <dt>Rows read</dt>
            <dd class="mono">{{ summary.totalRows | number }}</dd>
            <dt>Imported</dt>
            <dd class="mono good">{{ summary.imported | number }}</dd>
            <dt>Already had them</dt>
            <dd class="mono">{{ summary.duplicates | number }}</dd>
            <dt>Couldn't read</dt>
            <dd class="mono" [class.bad]="summary.failed > 0">{{ summary.failed | number }}</dd>
          </dl>

          @if (summary.failed > 0) {
            <p class="hint" style="margin-top:var(--space-4)">
              The rows below were skipped. Everything else was imported — fix these and upload
              the file again, the rows that worked won't be counted twice.
            </p>
            <div class="table-scroll" style="margin-top:var(--space-3);max-height:280px">
              <table>
                <thead>
                  <tr>
                    <th scope="col" class="numeric">Line</th>
                    <th scope="col">Problem</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of summary.errors; track row.line) {
                    <tr>
                      <td class="mono numeric">{{ row.line }}</td>
                      <td class="wrap">{{ row.message }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else if (summary.totalRows > 0) {
            <p class="hint" style="margin-top:var(--space-4)">
              Every row was read. Run a scan from the flagged customers page to pick up anyone
              who has gone quiet.
            </p>
          }
        } @else {
          <!-- Nested, since the "as" alias is only allowed on a plain if. -->
          @if (preview(); as detected) {
          <p class="muted small" style="margin:0 0 var(--space-3)">
            Found <strong>{{ detected.columns.length }}</strong> columns. First few rows:
          </p>
          <div class="table-scroll" style="max-height:320px">
            <table>
              <thead>
                <tr>
                  @for (column of detected.columns; track column) {
                    <th scope="col">{{ column }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of detected.sampleRows; track $index) {
                  <tr>
                    @for (column of detected.columns; track column) {
                      <td class="mono small">{{ row[$index] }}</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          } @else {
            <p class="muted small" style="margin:0">
              Nothing imported yet. Choose a file and its columns will appear here.
            </p>
          }
        }
      </section>
    </div>
  `,
  styles: [`
    .grid {
      display: grid;
      gap: var(--space-4);
      grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
      align-items: start;
    }
    .block { padding: var(--space-5); }
    h2 { margin-bottom: var(--space-2); }
    input[type='file'] { padding: var(--space-2); }
    .sample {
      font-family: var(--font-data);
      font-size: 0.9em;
      color: var(--text-subtle);
    }
    .tally {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: var(--space-2) var(--space-4);
      margin: var(--space-4) 0 0;
    }
    .tally dt { color: var(--text-muted); font-size: var(--text-sm); }
    .tally dd { margin: 0; text-align: right; font-weight: 600; }
    .good { color: var(--success-600); }
    .bad { color: var(--danger-600); }
    .wrap { white-space: normal; font-size: var(--text-sm); }
  `],
})
export class ImportComponent {
  private readonly api = inject(ApiService);

  readonly fields: FieldSpec[] = [
    {
      key: 'customerRef',
      label: 'Customer reference',
      hint: 'Whatever identifies the same person across visits — a loyalty or account number.',
      required: true,
    },
    {
      key: 'externalTxnId',
      label: 'Transaction ID',
      hint: 'Must be unique per sale. This is how a re-uploaded row is recognised and skipped.',
      required: true,
    },
    { key: 'amount', label: 'Amount', hint: 'The sale total.', required: true },
    {
      key: 'occurredAt',
      label: 'Date of sale',
      hint: 'When the sale happened — this is what visit rhythm is measured from.',
      required: true,
    },
    {
      key: 'contactEmail',
      label: 'Email',
      hint: 'Without an email or phone, an offer is generated but has nowhere to go.',
      required: false,
    },
    { key: 'contactPhone', label: 'Phone', hint: 'Used if there is no email.', required: false },
  ];

  readonly file = signal<File | null>(null);
  readonly preview = signal<ImportPreview | null>(null);
  readonly result = signal<ImportResult | null>(null);
  readonly busy = signal(false);
  readonly attempted = signal(false);
  readonly error = signal<string | null>(null);

  /** Field key to chosen column name. Bound directly to the selects. */
  mapping: Record<string, string> = {};

  pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const chosen = input.files?.[0] ?? null;
    this.file.set(chosen);
    this.preview.set(null);
    this.result.set(null);
    this.error.set(null);
    this.attempted.set(false);
    this.mapping = {};

    if (chosen) {
      this.loadPreview(chosen);
    }
  }

  complete(): boolean {
    return this.fields
      .filter((field) => field.required)
      .every((field) => !!this.mapping[field.key]);
  }

  /** First sample value for the column currently mapped to this field. */
  sampleFor(fieldKey: string): string | null {
    const detected = this.preview();
    const column = this.mapping[fieldKey];
    if (!detected || !column || detected.sampleRows.length === 0) {
      return null;
    }
    const index = detected.columns.indexOf(column);
    const value = index >= 0 ? detected.sampleRows[0][index] : null;
    return value ? value : null;
  }

  upload(): void {
    const file = this.file();
    this.attempted.set(true);
    if (!file || !this.complete()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);

    this.api.importTransactions(file, this.mapping).subscribe({
      next: (summary) => {
        this.result.set(summary);
        this.busy.set(false);
      },
      error: (response) => {
        this.busy.set(false);
        // A 400 is about the file or the mapping, and the server's message
        // names the actual problem, so it's worth showing verbatim.
        this.error.set(
          response.status === 400
            ? response.error?.message ?? 'That file could not be read.'
            : 'The import failed. Check the file and try again.'
        );
      },
    });
  }

  private loadPreview(file: File): void {
    this.busy.set(true);
    this.api.previewImport(file).subscribe({
      next: (detected) => {
        this.preview.set(detected);
        // Pre-fill from the server's guesses; every one stays editable.
        this.mapping = { ...detected.suggested };
        this.busy.set(false);
      },
      error: (response) => {
        this.busy.set(false);
        this.error.set(
          response.status === 400
            ? response.error?.message ?? 'That file could not be read.'
            : 'The file could not be read. Check it is a CSV and try again.'
        );
      },
    });
  }
}
