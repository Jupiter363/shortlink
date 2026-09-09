"""Pure opt-in renderer checks; no service or database operations."""
import pathlib
import unittest

import supervisor

ROOT = pathlib.Path(__file__).resolve().parents[2]


class ExecutionConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.manifest = (ROOT / 'deploy/apisix/apisix.yaml').read_text(encoding='utf-8-sig')
        self.config = (ROOT / 'deploy/apisix/config.yaml').read_text(encoding='utf-8-sig')

    def test_disabled_preserves_both_inputs(self):
        self.assertEqual((self.manifest, self.config),
                         supervisor.render_execution_diagnostics(self.manifest, self.config, False))

    def test_enabled_preserves_source_audit_prefix_and_adds_same_request_fields(self):
        manifest, config = supervisor.render_execution_diagnostics(self.manifest, self.config, True)
        self.assertEqual(manifest.count('        execution_diagnostics: true\n'), 1)
        self.assertEqual(config.count('access_log_format:'), 1)
        self.assertIn('$remote_addr - $remote_user [$time_local] $http_host "$request"', config)
        self.assertIn('"$http_referer" "$http_user_agent"', config)
        for field in ('$msec', '$pid', '$remote_port', '$connection_requests',
                      '$sent_http_x_request_id', '$upstream_http_x_request_id',
                      '$upstream_http_x_shortlink_handler_nanos', '$upstream_connect_time',
                      '$upstream_header_time'):
            self.assertIn(field, config)
        self.assertNotIn('execution_diagnostics:', self.manifest)

    def test_ambiguous_input_fails_before_runtime(self):
        for manifest, config in ((self.manifest + '\nexecution_diagnostics: false\n', self.config),
                                 (self.manifest, self.config + '\naccess_log_format: invalid\n'),
                                 (self.manifest, self.config.replace('  http:\n', ''))):
            with self.assertRaises(ValueError):
                supervisor.render_execution_diagnostics(manifest, config, True)
        with self.assertRaises(ValueError):
            supervisor.render_execution_diagnostics(self.manifest, self.config, 1)


if __name__ == '__main__':
    unittest.main()
