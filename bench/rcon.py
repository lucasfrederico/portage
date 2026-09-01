#!/usr/bin/env python3
"""Sends one console command over RCON and prints the reply.

Usage: rcon.py <host> <port> <password> <command...>
"""
import socket
import struct
import sys


def packet(request_id, kind, body):
    payload = struct.pack("<ii", request_id, kind) + body.encode() + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def read(sock):
    size = struct.unpack("<i", sock.recv(4))[0]
    data = b""
    while len(data) < size:
        data += sock.recv(size - len(data))
    request_id, kind = struct.unpack("<ii", data[:8])
    return request_id, kind, data[8:-2].decode(errors="replace")


def main():
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    host, port, password = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    command = " ".join(sys.argv[4:])
    with socket.create_connection((host, port), timeout=10) as sock:
        sock.sendall(packet(1, 3, password))
        request_id, _, _ = read(sock)
        if request_id == -1:
            sys.exit("rcon auth failed")
        sock.sendall(packet(2, 2, command))
        _, _, reply = read(sock)
        print(reply)


if __name__ == "__main__":
    main()
