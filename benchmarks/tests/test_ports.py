"""Regression coverage for immediately restarting the benchmark on macOS."""
import socket
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run import check_port_available, port_has_listener, select_server_port


class BenchmarkPortTest(unittest.TestCase):
    def test_free_port_is_used_unchanged(self):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        self.assertFalse(port_has_listener(port))
        self.assertEqual(select_server_port(port), port)

    def test_active_listener_must_not_be_used_or_silently_bypassed(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            self.assertTrue(port_has_listener(port))
            with self.assertRaisesRegex(OSError, 'Active listener'):
                select_server_port(port, wait_s=0)
            with self.assertRaises(OSError):
                check_port_available(port)

    def test_after_tcp_connection_closes_benchmark_can_rebind(self):
        # Mimic a server exiting with outstanding client-side TCP state.
        with socket.socket() as server:
            server.bind(('127.0.0.1', 0))
            port = server.getsockname()[1]
            server.listen(1)
            with socket.create_connection(('127.0.0.1', port)) as client:
                accepted, _ = server.accept()
                with accepted:
                    accepted.shutdown(socket.SHUT_RDWR)
        self.assertFalse(port_has_listener(port))
        selected = select_server_port(port, wait_s=0)
        self.assertGreater(selected, 0)
        self.assertFalse(port_has_listener(selected))
        check_port_available(selected)


if __name__ == '__main__':
    unittest.main()
