import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { BusinessVocabulary } from './api.models';

/**
 * The words for this business's trade ("client" and "appointment" for a salon,
 * "customer" and "visit" for a cafe), loaded once after sign-in.
 *
 * It only ever changes labels and worked examples. Until the config arrives, or
 * if it cannot be reached, the cafe wording stands in, which is what the screens
 * said before there was a choice, so a slow request never leaves a blank label.
 */
@Injectable({ providedIn: 'root' })
export class VocabularyService {
  private readonly api = inject(ApiService);

  readonly words = signal<BusinessVocabulary>({
    customerNoun: 'customer',
    customerNounPlural: 'customers',
    visitNoun: 'visit',
    usualLabel: 'Usual order',
    freeItemLabel: 'free item',
    typicalCadenceDays: 7,
  });

  load(): void {
    this.api.getConfig().subscribe({
      next: (config) => this.words.set(config.vocabulary),
      error: () => {
        // Keep the defaults: the labels are a convenience, not the data.
      },
    });
  }
}
