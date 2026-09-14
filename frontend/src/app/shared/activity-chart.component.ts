import { DatePipe } from '@angular/common';
import { Component, Input, computed, inject, signal } from '@angular/core';
import { SeriesPoint } from '../core/api.models';

interface Bar {
  point: SeriesPoint;
  x: number;
  raisedY: number;
  raisedHeight: number;
  recoveredY: number;
  recoveredHeight: number;
  labelX: number;
  /** False where the axis label is thinned out to stop the ticks colliding. */
  showLabel: boolean;
}

/**
 * Flags raised against customers recovered, over the reporting period.
 *
 * <p>Hand-rolled SVG rather than a charting library: two series over at most
 * thirty points doesn't justify the bundle, and the axis rules here (every
 * bucket always occupying its slot, zero-height bars still drawn) are exactly
 * the ones a general-purpose library would want to optimise away.
 *
 * <p>The bucket is a day or a week depending on the reporting period, so the
 * column width and the axis tick rate are both derived from the point count
 * rather than fixed. A fixed bar width would silently overlap its neighbours the
 * moment the series grew past the eight weeks it was written for.
 */
@Component({
  selector: 'app-activity-chart',
  standalone: true,
  imports: [DatePipe],
  providers: [DatePipe],
  template: `
    <figure>
      <figcaption class="legend">
        <span class="key"><span class="swatch swatch-raised"></span>Flags raised</span>
        <span class="key"><span class="swatch swatch-recovered"></span>Came back</span>
      </figcaption>

      <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" role="img"
           [attr.aria-label]="summary()" preserveAspectRatio="xMidYMid meet">
        <!-- Gridlines first, so bars sit on top of them. -->
        @for (line of gridlines(); track line.value) {
          <g>
            <line [attr.x1]="padLeft" [attr.x2]="width - padRight"
                  [attr.y1]="line.y" [attr.y2]="line.y" class="grid" />
            <text [attr.x]="padLeft - 8" [attr.y]="line.y + 4" class="axis-label numeric">
              {{ line.value }}
            </text>
          </g>
        }

        @for (bar of bars(); track bar.point.bucketStart) {
          <g>
            <rect [attr.x]="bar.x" [attr.y]="bar.raisedY"
                  [attr.width]="barWidth()" [attr.height]="bar.raisedHeight"
                  rx="2" class="bar-raised" />
            <rect [attr.x]="bar.x + barWidth() + barGap" [attr.y]="bar.recoveredY"
                  [attr.width]="barWidth()" [attr.height]="bar.recoveredHeight"
                  rx="2" class="bar-recovered" />
            @if (bar.showLabel) {
              <text [attr.x]="bar.labelX" [attr.y]="height - 8" class="axis-label"
                    text-anchor="middle">{{ bar.point.bucketStart | date: 'd MMM' }}</text>
            }
            <title>{{ tooltip(bar.point) }}</title>
          </g>
        }

        <line [attr.x1]="padLeft" [attr.x2]="width - padRight"
              [attr.y1]="plotBottom" [attr.y2]="plotBottom" class="axis" />
      </svg>
    </figure>
  `,
  styles: [`
    figure { margin: 0; }
    svg { width: 100%; height: auto; max-height: 280px; display: block; }
    .legend {
      display: flex;
      gap: var(--space-4);
      margin-bottom: var(--space-3);
      font-size: var(--text-sm);
      color: var(--text-muted);
    }
    .key { display: inline-flex; align-items: center; gap: var(--space-2); }
    .swatch { width: 10px; height: 10px; border-radius: 2px; }
    .swatch-raised { background: var(--warning-600); }
    .swatch-recovered { background: var(--success-600); }
    .bar-raised { fill: var(--warning-600); }
    .bar-recovered { fill: var(--success-600); }
    .grid { stroke: var(--border); stroke-width: 1; }
    .axis { stroke: var(--border-strong); stroke-width: 1; }
    .axis-label {
      fill: var(--text-subtle);
      font-size: 10px;
      font-family: var(--font-data);
    }
    .axis-label.numeric { text-anchor: end; }
  `],
})
export class ActivityChartComponent {
  private readonly datePipe = inject(DatePipe);

  @Input({ required: true }) set points(value: SeriesPoint[]) {
    this.series.set(value ?? []);
  }

  /** What one point covers, for the axis labels and the text alternative. */
  @Input() set unit(value: 'day' | 'week') {
    this.bucketUnit.set(value ?? 'week');
  }

  protected readonly series = signal<SeriesPoint[]>([]);
  protected readonly bucketUnit = signal<'day' | 'week'>('week');

  /**
   * Wide viewBox on purpose. The SVG scales to its container width, so a
   * narrow one would grow tall to keep its aspect ratio and leave the card a
   * mostly-empty band.
   */
  protected readonly width = 960;
  protected readonly height = 260;
  protected readonly padLeft = 36;
  protected readonly padRight = 8;
  protected readonly padTop = 14;
  protected readonly plotBottom = 224;
  private readonly maxBarWidth = 22;
  protected readonly barGap = 5;

  /**
   * A third of the slot per bar, capped at the width eight weekly columns had.
   * Three parts to a slot — two bars and a gap — is what keeps a thirty-column
   * series legible instead of a smear.
   */
  protected readonly barWidth = computed(() => {
    const count = this.series().length;
    if (count === 0) {
      return this.maxBarWidth;
    }
    const slot = (this.width - this.padLeft - this.padRight) / count;
    return Math.min(this.maxBarWidth, Math.max(2, slot / 3));
  });

  /** Headroom so the tallest bar doesn't touch the top gridline. */
  private readonly peak = computed(() => {
    const highest = Math.max(
      1,
      ...this.series().flatMap((point) => [point.flagsRaised, point.customersRecovered])
    );
    return Math.ceil(highest * 1.15);
  });

  protected readonly gridlines = computed(() => {
    const peak = this.peak();
    // Four bands, and only whole numbers — half a flag doesn't exist.
    const step = Math.max(1, Math.ceil(peak / 4));
    const lines = [];
    for (let value = 0; value <= peak; value += step) {
      lines.push({ value, y: this.yFor(value) });
    }
    return lines;
  });

  protected readonly bars = computed<Bar[]>(() => {
    const points = this.series();
    if (points.length === 0) {
      return [];
    }
    const barWidth = this.barWidth();
    const plotWidth = this.width - this.padLeft - this.padRight;
    const slot = plotWidth / points.length;
    const pairWidth = barWidth * 2 + this.barGap;
    // Thirty "14 Sep" ticks under one axis is a grey band, not a scale. Every
    // nth label keeps enough of them to read a date off any bar's neighbour.
    const every = Math.max(1, Math.ceil(points.length / 8));

    return points.map((point, index) => {
      const x = this.padLeft + slot * index + (slot - pairWidth) / 2;
      return {
        point,
        x,
        raisedY: this.yFor(point.flagsRaised),
        raisedHeight: this.plotBottom - this.yFor(point.flagsRaised),
        recoveredY: this.yFor(point.customersRecovered),
        recoveredHeight: this.plotBottom - this.yFor(point.customersRecovered),
        labelX: x + pairWidth / 2,
        showLabel: index % every === 0,
      };
    });
  });

  /** Text alternative — a chart alone is unreadable to a screen reader. */
  protected readonly summary = computed(() => {
    const points = this.series();
    const raised = points.reduce((total, point) => total + point.flagsRaised, 0);
    const recovered = points.reduce((total, point) => total + point.customersRecovered, 0);
    const unit = this.bucketUnit();
    const span = points.length === 1 ? `1 ${unit}` : `${points.length} ${unit}s`;
    return `Bar chart of the last ${span}: ${raised} flags raised, `
      + `${recovered} customers came back.`;
  });

  protected tooltip(point: SeriesPoint): string {
    // The same pipe the axis uses, provided at component level so the wording of
    // a date is decided in one place rather than two.
    const date = this.datePipe.transform(point.bucketStart, 'd MMM yyyy');
    const label = this.bucketUnit() === 'day' ? date : `Week of ${date}`;
    return `${label}: ${point.flagsRaised} flagged, ${point.customersRecovered} came back`;
  }

  private yFor(value: number): number {
    const usable = this.plotBottom - this.padTop;
    return this.plotBottom - (value / this.peak()) * usable;
  }
}
