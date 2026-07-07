#!/usr/bin/env python3
"""Render trace-comparison.md's numerical table as a publication-quality
horizontal-bar chart of log10(p95_sim / p95_real) per operation.

Output: paper/figures/boutique-trace-comparison.pdf (vector PDF, suitable for
LaTeX \\includegraphics).  Bar shading marks operations inside vs outside the
|log10| < 0.5 acceptance band.
"""
from __future__ import annotations

import math
import re
import sys
from pathlib import Path

try:
    import matplotlib.pyplot as plt
except ImportError:
    print('matplotlib is required: pip install matplotlib', file=sys.stderr)
    sys.exit(1)

# Hard-code the operations and ratios from the 2026-05-21 trace-comparison.md
# rather than re-parsing the report, so the script is the source-of-truth a
# reviewer can audit.
ROWS = [
    ('ListRecommendations',     +0.03),
    ('Charge',                  +0.61),
    ('PlaceOrder',              -1.03),
    ('GetSupportedCurrencies',  +0.87),
    ('Convert',                 +0.97),
    ('SendOrderConfirmation',   +0.93),
    ('ListProducts',            +1.68),
    ('GetProduct',              +1.84),
]
THRESHOLD = 0.5


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else (
        Path(__file__).resolve().parents[2]
        / 'paper' / 'figures' / 'boutique-trace-comparison.pdf')
    out.parent.mkdir(parents=True, exist_ok=True)

    # Sort by magnitude so the in-band bar appears at the top.
    rows = sorted(ROWS, key=lambda r: abs(r[1]))
    labels = [r[0] for r in rows]
    values = [r[1] for r in rows]
    colors = ['#2ca02c' if abs(v) < THRESHOLD else '#d62728' for v in values]

    fig, ax = plt.subplots(figsize=(5.6, 3.2), constrained_layout=True)
    bars = ax.barh(labels, values, color=colors, edgecolor='black', linewidth=0.4)

    # Acceptance band
    ax.axvspan(-THRESHOLD, +THRESHOLD, color='#d0e6c5', alpha=0.4, zorder=0,
               label=f'|log10| < {THRESHOLD}')
    ax.axvline(0, color='black', linewidth=0.6)

    for bar, v in zip(bars, values):
        ax.text(v + (0.04 if v >= 0 else -0.04), bar.get_y() + bar.get_height() / 2,
                f'{v:+.2f}', va='center',
                ha='left' if v >= 0 else 'right', fontsize=8)

    ax.set_xlabel(r'$\log_{10}(p95_{\mathrm{sim}} / p95_{\mathrm{real}})$')
    ax.set_xlim(-1.5, 2.7)
    ax.tick_params(axis='y', labelsize=8)
    ax.set_title('Online Boutique trace comparison (moderate, 200-user load)',
                 fontsize=9)
    ax.legend(loc='lower right', fontsize=7, framealpha=0.9)
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)

    fig.savefig(out)
    print(f'wrote {out}')


if __name__ == '__main__':
    main()
