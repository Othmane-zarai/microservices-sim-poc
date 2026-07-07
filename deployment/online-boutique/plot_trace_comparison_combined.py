#!/usr/bin/env python3
"""Render trace-comparison data for both USERS=200 and USERS=500 captures as
a publication-quality grouped horizontal-bar chart of
log10(p95_sim / p95_real) per operation.

Output: paper/figures/boutique-trace-comparison-combined.pdf
Numbers are hard-coded from the source-of-truth trace-comparison.md files so
that the script is auditable from the LaTeX directly.
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    import matplotlib.pyplot as plt
    import numpy as np
except ImportError:
    print('matplotlib and numpy required: pip install matplotlib numpy', file=sys.stderr)
    sys.exit(1)

ROWS_200 = {
    'ListRecommendations':     -0.41,
    'Charge':                  +0.61,
    'PlaceOrder':              -1.03,
    'GetSupportedCurrencies':  +0.87,
    'Convert':                 +0.97,
    'SendOrderConfirmation':   +0.93,
    'ListProducts':            +1.68,
    'GetProduct':              +1.84,
}
ROWS_500 = {
    'ListRecommendations':     -1.36,
    'Charge':                  +0.21,
    'SendOrderConfirmation':   +0.54,
    'GetSupportedCurrencies':  +1.20,
    'ListProducts':            +1.41,
    'Convert':                 +1.46,
    'GetProduct':              +1.50,
    'PlaceOrder':              -2.24,
}
THRESHOLD = 0.5


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else (
        Path(__file__).resolve().parents[2]
        / 'paper' / 'figures' / 'boutique-trace-comparison-combined.pdf')
    out.parent.mkdir(parents=True, exist_ok=True)

    ops = list(ROWS_200.keys())
    v200 = [ROWS_200[o] for o in ops]
    v500 = [ROWS_500.get(o, float('nan')) for o in ops]
    # Sort by USERS=200 magnitude so the strongest match is on top.
    order = sorted(range(len(ops)), key=lambda i: abs(v200[i]))
    ops = [ops[i] for i in order]
    v200 = [v200[i] for i in order]
    v500 = [v500[i] for i in order]

    y = np.arange(len(ops))
    h = 0.38

    fig, ax = plt.subplots(figsize=(5.8, 3.6), constrained_layout=True)

    bars_200 = ax.barh(y + h/2, v200, h,
                       color=['#2ca02c' if abs(v) < THRESHOLD else '#9ecae1' for v in v200],
                       edgecolor='black', linewidth=0.3,
                       label='USERS=200 (moderate)')
    bars_500 = ax.barh(y - h/2,
                       [0 if (isinstance(v, float) and v != v) else v for v in v500],
                       h,
                       color=['#2ca02c' if (isinstance(v, float) and v == v and abs(v) < THRESHOLD)
                              else '#fc9272' for v in v500],
                       edgecolor='black', linewidth=0.3,
                       label='USERS=500 (high)')

    ax.axvspan(-THRESHOLD, +THRESHOLD, color='#d0e6c5', alpha=0.35, zorder=0,
               label=f'|log10| < {THRESHOLD}')
    ax.axvline(0, color='black', linewidth=0.5)

    for bar, v in zip(bars_200, v200):
        ax.text(v + (0.05 if v >= 0 else -0.05),
                bar.get_y() + bar.get_height() / 2,
                f'{v:+.2f}', va='center',
                ha='left' if v >= 0 else 'right', fontsize=7)
    for bar, v in zip(bars_500, v500):
        if isinstance(v, float) and v != v:
            ax.text(0.05, bar.get_y() + bar.get_height() / 2,
                    'n/a (not in trace)', va='center', ha='left',
                    fontsize=7, color='#888888', style='italic')
        else:
            ax.text(v + (0.05 if v >= 0 else -0.05),
                    bar.get_y() + bar.get_height() / 2,
                    f'{v:+.2f}', va='center',
                    ha='left' if v >= 0 else 'right', fontsize=7)

    ax.set_yticks(y)
    ax.set_yticklabels(ops)
    ax.set_xlabel(r'$\log_{10}(p95_{\mathrm{sim}} / p95_{\mathrm{real}})$')
    ax.set_xlim(-2.6, 2.7)
    ax.tick_params(axis='y', labelsize=8)
    ax.set_title('Per-operation latency fidelity, two load levels', fontsize=9)
    ax.legend(loc='lower right', fontsize=7, framealpha=0.9)
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)

    fig.savefig(out)
    print(f'wrote {out}')


if __name__ == '__main__':
    main()
