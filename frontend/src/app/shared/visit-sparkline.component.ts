import { Component, Input, computed, signal } from '@angular/core';

interface Dot {
  x: number;
  iso: string;
}

/**
 * A customer's recent visits as dots on a time axis, with a marker where the
 * flag fired.
 *
 * <p>This is the detection logic made visible: evenly spaced dots are the
 * rhythm the engine learned, the empty stretch at the right is the break, and
 * the marker is the moment it decided the break was real.
 *
 * <p>Every point is a real transaction timestamp from
 * {@code /v1/flags/{id}/visits}. Nothing here is interpolated or inferred —
 * spacing dots evenly from an average would draw visits that never happened,
 * and the chart's whole purpose is to show that they did.
 */
@Component({
  selector: 'app-visit-sparkline',
  standalone: true,
  template: `
    @if (dots().length === 0) {
      <span class="muted small">No visits recorded</span>
    } @else {
      <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" [style.width.px]="width"
           [style.height.px]="height" role="img" [attr.aria-label]="label()">
        <title>{{ label() }}</title>

        <line [attr.x1]="0" [attr.x2]="width" [attr.y1]="midY" [attr.y2]="midY" class="axis" />

        <!-- The quiet stretch: from the last visit to the moment it was
             flagged. Drawn under the dots so it reads as background. -->
        @if (gap(); as span) {
          <rect [attr.x]="span.from" [attr.y]="midY - gapHeight / 2"
                [attr.width]="span.width" [attr.height]="gapHeight" class="gap" rx="2" />
        }

        @for (dot of dots(); track dot.iso) {
          <circle [attr.cx]="dot.x" [attr.cy]="midY" [attr.r]="radius" class="visit" />
        }

        <line [attr.x1]="flagX()" [attr.x2]="flagX()" [attr.y1]="midY - markerHeight"
              [attr.y2]="midY + markerHeight" class="flag-marker" />
      </svg>
    }
  `,
  styles: [`
    :host { display: inline-flex; align-items: center; }
    svg { display: block; overflow: visible; }
    .axis { stroke: var(--border); stroke-width: 1; }
    .gap { fill: var(--warning-50); }
    .visit { fill: var(--primary-600); }
    .flag-marker { stroke: var(--danger-600); stroke-width: 2; stroke-linecap: round; }
  `],
})
export class VisitSparklineComponent {
  @Input({ required: true }) set visits(value: string[]) {
    this.points.set(value ?? []);
  }

  @Input({ required: true }) set flaggedAt(value: string) {
    this.flagTime.set(value);
  }

  @Input() width = 132;
  @Input() height = 30;
  @Input() radius = 3;

  protected readonly points = signal<string[]>([]);
  protected readonly flagTime = signal<string>('');

  protected get midY(): number {
    return this.height / 2;
  }

  protected get markerHeight(): number {
    return this.height / 2 - 2;
  }

  protected get gapHeight(): number {
    return Math.max(6, this.height / 3);
  }

  /** Inset so the first dot and the flag marker aren't clipped at the edges. */
  private readonly inset = 5;

  private readonly times = computed(() =>
    this.points()
      .map((iso) => new Date(iso).getTime())
      .filter((time) => !Number.isNaN(time))
      .sort((a, b) => a - b)
  );

  private readonly flagMillis = computed(() => {
    const parsed = new Date(this.flagTime()).getTime();
    return Number.isNaN(parsed) ? 0 : parsed;
  });

  /** The axis spans the first visit to the flag — the whole story, no more. */
  private readonly span = computed(() => {
    const times = this.times();
    const start = times[0] ?? 0;
    const end = Math.max(this.flagMillis(), times[times.length - 1] ?? 0);
    // A single visit, or every visit at one instant, would divide by zero.
    return { start, range: Math.max(1, end - start) };
  });

  protected readonly dots = computed<Dot[]>(() => {
    const { start, range } = this.span();
    const usable = this.width - this.inset * 2;
    return this.times().map((time, index) => ({
      x: this.inset + ((time - start) / range) * usable,
      iso: `${this.points()[index] ?? time}`,
    }));
  });

  protected readonly flagX = computed(() => {
    const { start, range } = this.span();
    const usable = this.width - this.inset * 2;
    return this.inset + ((this.flagMillis() - start) / range) * usable;
  });

  protected readonly gap = computed(() => {
    const dots = this.dots();
    if (dots.length === 0) {
      return null;
    }
    const lastVisitX = dots[dots.length - 1].x;
    const width = this.flagX() - lastVisitX;
    return width > 1 ? { from: lastVisitX, width } : null;
  });

  /** A chart with no text alternative is invisible to a screen reader. */
  protected readonly label = computed(() => {
    const times = this.times();
    if (times.length === 0) {
      return 'No visits recorded';
    }
    const quietDays = Math.round((this.flagMillis() - times[times.length - 1]) / 86_400_000);
    return `${times.length} recent visits, then ${quietDays} quiet days before the flag was raised.`;
  });
}
