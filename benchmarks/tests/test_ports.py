"""Regression coverage for immediately restarting the benchmark on macOS."""
import socket
import sys
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock
import errno

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run import (check_port_available, port_has_listener, select_server_port,
                 _windows_listener_from_netstat)


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

    def test_macos_listener_detection_does_not_fill_accept_backlog(self):
        """Darwin detection must not connect to a possibly full accept queue."""
        completed = MagicMock(returncode=0, stdout='12345\n')
        with patch('run.sys.platform', 'darwin'), \
             patch('run.shutil.which', return_value='/usr/sbin/lsof'), \
             patch('run.subprocess.run', return_value=completed) as execute, \
             patch('run.socket.socket', side_effect=AssertionError('Unexpected TCP connect')):
            self.assertTrue(port_has_listener(49179))
            with self.assertRaisesRegex(OSError, 'Active listener'):
                select_server_port(49179, wait_s=0)
        self.assertEqual(execute.call_count, 2)
        self.assertIn('-sTCP:LISTEN', execute.call_args.args[0])

    def test_macos_lsof_no_listener_allows_recheck_without_false_positive(self):
        """A lsof result of no LISTEN socket must not itself imply a listener."""
        completed = MagicMock(returncode=1, stdout='')
        with patch('run.sys.platform', 'darwin'), \
             patch('run.shutil.which', return_value='/usr/sbin/lsof'), \
             patch('run.subprocess.run', return_value=completed), \
             patch('run.socket.socket', side_effect=AssertionError('Unexpected TCP connect')):
            self.assertFalse(port_has_listener(49179))

    def test_macos_lsof_missing_falls_back_to_tcp_probe(self):
        """Without lsof, retain support for minimal macOS environments."""
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(2)
            port = listener.getsockname()[1]
            with patch('run.sys.platform', 'darwin'), \
                 patch('run.shutil.which', return_value=None), \
                 patch('run.Path.is_file', return_value=False):
                self.assertTrue(port_has_listener(port))

    def test_windows_netstat_detects_full_backlog_without_connecting(self):
        """A LISTEN socket must be detected even when connect_ex would refuse."""
        output = ('Active Connections\n\n  Proto  Local Address          Foreign Address        State           PID\n'
                  '  TCP    127.0.0.1:49179      0.0.0.0:0              LISTENING       2345\n'
                  '  TCP    [::]:49179           [::]:0                 LISTENING       2345\n')
        completed = MagicMock(returncode=0, stdout=output)
        with patch('run.sys.platform', 'win32'), \
             patch('run.shutil.which', return_value=r'C:\Windows\System32\netstat.exe'), \
             patch('run.subprocess.run', return_value=completed) as execute, \
             patch('run.socket.socket', side_effect=AssertionError('Unexpected TCP probe')):
            self.assertTrue(port_has_listener(49179))
            with self.assertRaisesRegex(OSError, 'Active listener'):
                select_server_port(49179, wait_s=0)
        self.assertEqual(execute.call_count, 2)
        self.assertIn('-ano', execute.call_args.args[0])

    def test_windows_netstat_ignores_stale_connections_and_other_ports(self):
        output = ('  TCP    127.0.0.1:49179      127.0.0.1:50999      TIME_WAIT       0\n'
                  '  TCP    0.0.0.0:49180        0.0.0.0:0          LISTENING       2345\n')
        with patch('run.shutil.which', return_value='netstat.exe'), \
             patch('run.subprocess.run', return_value=MagicMock(returncode=0, stdout=output)):
            self.assertFalse(_windows_listener_from_netstat(49179))
            self.assertTrue(_windows_listener_from_netstat(49180))

    def test_windows_uncertain_netstat_fails_closed_when_bind_denied(self):
        """A refused bind with no reliable socket table cannot mean TIME_WAIT."""
        with patch('run.sys.platform', 'win32'), \
             patch('run.port_has_listener', return_value=False), \
             patch('run._windows_listener_from_netstat', return_value=None), \
             patch('run.check_port_available', side_effect=OSError(errno.EADDRINUSE, 'in use')):
            with self.assertRaisesRegex(OSError, 'Active listener or unverified TCP port'):
                select_server_port(49179, wait_s=0)

    def test_windows_localized_state_is_inconclusive_not_empty(self):
        output = '  TCP    0.0.0.0:49179        0.0.0.0:0          NASLUCHIWANIE    2345\n'
        with patch('run.shutil.which', return_value='netstat.exe'), \
             patch('run.subprocess.run', return_value=MagicMock(returncode=0, stdout=output)):
            self.assertIsNone(_windows_listener_from_netstat(49179))

    def test_windows_access_denied_is_never_treated_as_stale_tcp(self):
        """WinError 10013 cannot authorize switching to an unchecked server."""
        permission = PermissionError(errno.EACCES, 'Windows socket access denied')
        with patch('run.port_has_listener', return_value=False), \
             patch('run.check_port_available', side_effect=permission):
            with self.assertRaisesRegex(OSError, 'Active listener or restricted port'):
                select_server_port(18080, wait_s=0)

    def test_windows_listener_probe_handles_access_denied(self):
        fake_socket = MagicMock()
        fake_socket.__enter__.return_value.connect_ex.return_value = 10013
        with patch('run.sys.platform', 'win32'), \
             patch('run._windows_listener_from_netstat', return_value=None), \
             patch('run.socket.socket', return_value=fake_socket):
            self.assertTrue(port_has_listener(18080))
        fake_socket.__enter__.return_value.connect_ex.return_value = 10061
        with patch('run.sys.platform', 'win32'), \
             patch('run._windows_listener_from_netstat', return_value=None), \
             patch('run.socket.socket', return_value=fake_socket):
            self.assertFalse(port_has_listener(18080))

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
