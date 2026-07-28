import { DatePipe } from '@angular/common';
import { Component, Input, computed, signal } from '@angular/core';
import { WeeklyPoint } from '../core/api.models';

interface Bar {
  point: WeeklyPoint;
  x: number;
  raisedY: number;
  raisedHeight: number;
  recoveredY: number;
  recoveredHeight: number;
  labelX: number;
}

/**
 * Flags raised against customers recovered, by week.
 *
 * <p>Hand-rolled SVG rather than a charting library: two series over eight
 * points doesn't justify the bundle, and the axis rules here (always eight
 * columns, zero-height bars still occupying their slot) are exactly the ones a
 * general-purpose library would want to optimise away.
 */
@Component({
  selector: 'app-weekly-chart',
  standalone: true,
  imports: [DatePipe],
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

        @for (bar of bars(); track bar.point.weekStart) {
          <g>
            <rect [attr.x]="bar.x" [attr.y]="bar.raisedY"
                  [attr.width]="barWidth" [attr.height]="bar.raisedHeight"
                  rx="2" class="bar-raised" />
            <rect [attr.x]="bar.x + barWidth + barGap" [attr.y]="bar.recoveredY"
                  [attr.width]="barWidth" [attr.height]="bar.recoveredHeight"
                  rx="2" class="bar-recovered" />
            <text [attr.x]="bar.labelX" [attr.y]="height - 8" class="axis-label" text-anchor="middle">
              {{ bar.point.weekStart | date: 'd MMM' }}
            </text>
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
export class WeeklyChartComponent {
  @Input({ required: true }) set points(value: WeeklyPoint[]) {
    this.series.set(value ?? []);
  }

  protected readonly series = signal<WeeklyPoint[]>([]);

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
  protected readonly barWidth = 22;
  protected readonly barGap = 5;

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
    const plotWidth = this.width - this.padLeft - this.padRight;
    const slot = plotWidth / points.length;
    const pairWidth = this.barWidth * 2 + this.barGap;

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
      };
    });
  });

  /** Text alternative — a chart alone is unreadable to a screen reader. */
  protected readonly summary = computed(() => {
    const points = this.series();
    const raised = points.reduce((total, point) => total + point.flagsRaised, 0);
    const recovered = points.reduce((total, point) => total + point.customersRecovered, 0);
    return `Bar chart of the last ${points.length} weeks: ${raised} flags raised, ${recovered} customers came back.`;
  });

  protected tooltip(point: WeeklyPoint): string {
    return `Week of ${point.weekStart}: ${point.flagsRaised} flagged, ${point.customersRecovered} came back`;
  }

  private yFor(value: number): number {
    const usable = this.plotBottom - this.padTop;
    return this.plotBottom - (value / this.peak()) * usable;
  }
}
