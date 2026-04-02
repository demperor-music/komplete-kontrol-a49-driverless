#!/usr/bin/env python3
import argparse, csv, datetime as dt, json, os, sys, termios, tty
from typing import List

DEFAULT_VALUES = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0xFF]


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def parse_range(spec: str, max_idx: int) -> List[int]:
    out = []
    for part in spec.split(','):
        part = part.strip()
        if not part:
            continue
        if '-' in part:
            a,b = part.split('-',1)
            a=int(a); b=int(b)
            out.extend(range(a,b+1))
        else:
            out.append(int(part))
    return [i for i in out if 0 <= i <= max_idx]


def send_packet(fd, pkt: bytes):
    os.write(fd, pkt)


def clear_packet(length: int, report_id: int) -> bytes:
    pkt = bytearray(length)
    pkt[0] = report_id
    return bytes(pkt)


def build_packet(length: int, report_id: int, index: int, value: int) -> bytes:
    pkt = bytearray(length)
    pkt[0] = report_id
    pkt[index] = value
    return bytes(pkt)


def read_single_key(prompt: str) -> str:
    print(prompt, end='', flush=True)
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        ch = sys.stdin.read(1)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
    print(ch)
    return ch


def append_jsonl(path, obj):
    with open(path, 'a', encoding='utf-8') as f:
        f.write(json.dumps(obj, ensure_ascii=False) + '\n')


def append_csv(path, row, write_header=False):
    fieldnames = [
        'timestamp','hid','packet_len','report_id','byte_index','test_value','packet_hex',
        'result','label','note'
    ]
    exists = os.path.exists(path)
    with open(path, 'a', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        if write_header and not exists:
            w.writeheader()
        w.writerow(row)


def main():
    ap = argparse.ArgumentParser(description='Focused mapper for A49 output report 0x80 (button/backlight family).')
    ap.add_argument('--hid', required=True)
    ap.add_argument('--length', type=int, default=91)
    ap.add_argument('--report-id', type=lambda s:int(s,0), default=0x80)
    ap.add_argument('--bytes', dest='byte_spec', default='1-90')
    ap.add_argument('--values', default='01,02,04,08,10,20,40,80,FF')
    ap.add_argument('--delay-ms', type=int, default=800)
    ap.add_argument('--jsonl', default='a49_light_probe.jsonl')
    ap.add_argument('--csv', default='a49_light_probe.csv')
    ap.add_argument('--restore-off', action='store_true', default=True)
    ap.add_argument('--no-restore-off', dest='restore_off', action='store_false')
    ap.add_argument('--sweep-one-byte', type=int, default=None)
    args = ap.parse_args()

    if not os.path.exists(args.hid):
        print(f'No such HID node: {args.hid}', file=sys.stderr)
        sys.exit(1)

    test_values = [int(x,16) if not x.lower().startswith('0x') else int(x,0) for x in args.values.split(',') if x.strip()]
    max_idx = args.length - 1
    indices = [args.sweep_one_byte] if args.sweep_one_byte is not None else parse_range(args.byte_spec, max_idx)
    indices = [i for i in indices if i not in (None, 0)]

    print(f'Opening {args.hid}')
    print(f'Focused report family: report_id=0x{args.report_id:02X}, length={args.length}')
    print('This script tests ONE payload byte at a time. That is the behavior you wanted.')
    print('Keys: y=visible change, n=no change, s=skip rest of this byte, q=quit, then optional text note.')

    fd = os.open(args.hid, os.O_RDWR | os.O_CLOEXEC)
    try:
        off = clear_packet(args.length, args.report_id)
        send_packet(fd, off)
        for idx in indices:
            skip_byte = False
            for val in test_values:
                if skip_byte:
                    break
                pkt = build_packet(args.length, args.report_id, idx, val)
                print('\n' + '='*70)
                print(f'byte {idx} <- 0x{val:02X}')
                print('packet:', ' '.join(f'{b:02X}' for b in pkt))
                send_packet(fd, pkt)
                ch = read_single_key('Result [y/n/s/q]: ').lower()
                if ch == 'q':
                    send_packet(fd, off)
                    return
                if ch == 's':
                    send_packet(fd, off)
                    skip_byte = True
                    continue
                result = 'visible_change' if ch == 'y' else 'none'
                label = ''
                note = input('What changed? (blank if nothing): ').strip()
                row = {
                    'timestamp': now(),
                    'hid': args.hid,
                    'packet_len': args.length,
                    'report_id': f'0x{args.report_id:02X}',
                    'byte_index': idx,
                    'test_value': f'0x{val:02X}',
                    'packet_hex': ' '.join(f'{b:02X}' for b in pkt),
                    'result': result,
                    'label': label,
                    'note': note,
                }
                append_jsonl(args.jsonl, row)
                append_csv(args.csv, row, write_header=True)
                if args.restore_off:
                    send_packet(fd, off)
        send_packet(fd, off)
        print('\nDone. Logs written to', args.jsonl, 'and', args.csv)
    finally:
        os.close(fd)

if __name__ == '__main__':
    main()
