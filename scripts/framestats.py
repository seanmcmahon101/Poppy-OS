import sys, statistics
# Parse `dumpsys gfxinfo <pkg> framestats` PROFILEDATA blocks; report per-stage medians/p90 and true jank at 50 Hz.
period = float(sys.argv[1]) if len(sys.argv) > 1 else 20.0
rows = []
hdr = None
for line in sys.stdin:
    line = line.strip()
    if line.startswith('Flags,'):
        hdr = line.rstrip(',').split(',')
        continue
    if hdr and line and line[0].isdigit():
        v = line.rstrip(',').split(',')
        if len(v) < len(hdr):
            continue
        d = dict(zip(hdr, map(int, v)))
        if d['Flags'] != 0:
            continue
        rows.append(d)
    elif line.startswith('---PROFILEDATA---') and hdr and rows:
        pass
if not rows:
    print('no frames'); sys.exit()
ms = lambda a, b, d: (d[b] - d[a]) / 1e6
stages = [
    ('input+anim', 'HandleInputStart', 'PerformTraversalsStart'),
    ('measure/layout', 'PerformTraversalsStart', 'DrawStart'),
    ('record (UI draw)', 'DrawStart', 'SyncQueued'),
    ('sync/upload', 'SyncStart', 'IssueDrawCommandsStart'),
    ('issue GL cmds', 'IssueDrawCommandsStart', 'SwapBuffers'),
    ('swap->complete', 'SwapBuffers', 'FrameCompleted'),
    ('TOTAL', 'IntendedVsync', 'FrameCompleted'),
]
def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(len(xs) * p))]
print(f'frames={len(rows)}')
for name, a, b in stages:
    xs = [ms(a, b, d) for d in rows if d.get(a, 0) > 0 and d.get(b, 0) > 0]
    if xs:
        print(f'{name:18s} p50={pct(xs,.5):6.1f}  p90={pct(xs,.9):6.1f}  max={max(xs):6.1f}')
deq = [d['DequeueBufferDuration'] / 1e6 for d in rows if 'DequeueBufferDuration' in d]
if deq: print(f'{"dequeueBuffer":18s} p50={pct(deq,.5):6.1f}  p90={pct(deq,.9):6.1f}  max={max(deq):6.1f}')
if 'GpuCompleted' in rows[0]:
    g = [ms('SwapBuffers', 'GpuCompleted', d) for d in rows if d['GpuCompleted'] > 0]
    if g: print(f'{"GPU after swap":18s} p50={pct(g,.5):6.1f}  p90={pct(g,.9):6.1f}  max={max(g):6.1f}')
tot = [ms('IntendedVsync', 'FrameCompleted', d) for d in rows]
jank = sum(1 for t in tot if t > period * 1.05)
print(f'jank at {1000/period:.0f} Hz (> {period*1.05:.1f} ms): {jank}/{len(rows)} = {100*jank/len(rows):.1f}%')
