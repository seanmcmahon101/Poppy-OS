"""Frame times from `dumpsys gfxinfo <pkg> framestats`, per render stage, and true jank.

    adb shell dumpsys gfxinfo com.sean.foyer framestats | python3 scripts/framestats.py [budget_ms]

budget_ms is one refresh period: 20 on a 50 Hz panel (the default), 16.7 at 60 Hz. `homectl perf`
reads it from the TV and passes it in. Only frames with Flags == 0 count (no window or layout
change); a frame is janky when it ends more than 5% past its budget.
"""
import sys

STAGES = [
    ('input+anim', 'HandleInputStart', 'PerformTraversalsStart'),
    ('measure/layout', 'PerformTraversalsStart', 'DrawStart'),
    ('record (UI draw)', 'DrawStart', 'SyncQueued'),
    ('sync/upload', 'SyncStart', 'IssueDrawCommandsStart'),
    ('issue GL cmds', 'IssueDrawCommandsStart', 'SwapBuffers'),
    ('swap->complete', 'SwapBuffers', 'FrameCompleted'),
    ('TOTAL', 'IntendedVsync', 'FrameCompleted'),
]


def frames(lines):
    """Every Flags == 0 row of every PROFILEDATA block, as {column: int}."""
    hdr = None
    for line in lines:
        line = line.strip()
        if line.startswith('Flags,'):
            hdr = line.rstrip(',').split(',')
        elif line.startswith('---PROFILEDATA---'):
            continue
        elif hdr and line[:1].isdigit():
            v = line.rstrip(',').split(',')
            if len(v) < len(hdr):
                continue
            try:
                d = dict(zip(hdr, map(int, v)))
            except ValueError:
                continue
            if d.get('Flags') == 0:
                yield d


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))]


def stat(name, xs):
    return f'{name:18s} p50={pct(xs, .5):6.1f}  p90={pct(xs, .9):6.1f}  max={max(xs):6.1f}'


def main():
    budget = float(sys.argv[1]) if len(sys.argv) > 1 else 20.0
    rows = list(frames(sys.stdin))
    if not rows:
        print('no frames (nothing drew since the last reset?)')
        return
    ms = lambda a, b, d: (d[b] - d[a]) / 1e6
    print(f'frames={len(rows)}')
    for name, a, b in STAGES:
        xs = [ms(a, b, d) for d in rows if d.get(a, 0) > 0 and d.get(b, 0) > 0]
        if xs:
            print(stat(name, xs))
    deq = [d['DequeueBufferDuration'] / 1e6 for d in rows if 'DequeueBufferDuration' in d]
    if deq:
        print(stat('dequeueBuffer', deq))
    gpu = [ms('SwapBuffers', 'GpuCompleted', d) for d in rows if d.get('GpuCompleted', 0) > 0]
    if gpu:
        print(stat('GPU after swap', gpu))
    tot = [ms('IntendedVsync', 'FrameCompleted', d) for d in rows if d.get('FrameCompleted', 0) > 0]
    jank = sum(1 for t in tot if t > budget * 1.05)
    print(f'jank at {1000 / budget:.0f} Hz (> {budget * 1.05:.1f} ms): {jank}/{len(tot)} = {100 * jank / max(1, len(tot)):.1f}%')


if __name__ == '__main__':
    try:
        main()
    except BrokenPipeError:  # piped into head: fine
        sys.stderr.close()
