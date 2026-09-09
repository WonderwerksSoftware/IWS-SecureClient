import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {X509Certificate} from 'node:crypto';
import test from 'node:test';
const read = p => readFileSync(new URL('../../' + p, import.meta.url), 'utf8');
test('production endpoint and resolver are pinned without staging probes', () => {
  assert.match(read('config/checkpoint/android-poc.properties'), /iwsAllowedEndpointIpv4=100\.83\.75\.124/);
  assert.match(read('config/checkpoint/android-poc.properties'), /iwsPortalUrl=https:\/\/portal\.iws\.internal\//);
  assert.match(read('android/app/build.gradle'), /def testEndpointUrl = ''/);
  assert.match(read('android/app/src/main/java/com/impactwiring/iwsconnectpoc/PrivateDnsPolicy.java'), /"100\.83\.75\.124"/);
  assert.match(read('packaging/windows/package-device.mjs'), /iws_entrypoint: "https:\/\/portal\.iws\.internal\/"/);
  assert.doesNotMatch(read('windows/webview2/IwsClient.cs'), /ProbeNativeIsolationAsync|iws_poc_cookie|__iwsTlsSocketProof/);
  const firewall = read('windows/webview2/IwsWebViewFirewall.psm1');
  for (const value of ['100.83.75.124', '0.0.0.0-100.83.75.123', '100.83.75.125-255.255.255.255']) assert.ok(firewall.includes('"' + value + '"'));
});
test('only pinned public production certificate is shipped', () => {
  const android = read('android/app/src/main/res/raw/iws_production_ca.pem');
  assert.equal(android, read('windows/webview2/iws-production-root-ca.crt'));
  assert.doesNotMatch(android, /PRIVATE KEY/);
  assert.equal(new X509Certificate(android).fingerprint256.replaceAll(':','').toLowerCase(), '3976d486cf804696206b98fccb56c2315f29a6690a0272891c562fac2b48a781');
  assert.match(read('android/app/src/main/res/xml/network_security_config.xml'), /@raw\/iws_production_ca/);
});
test('installer supplies trust and exact host DNS with owned cleanup', () => {
  const installer = read('packaging/windows/Install-IwsWebViewShellDevice.ps1');
  assert.match(installer, /& .*Install-IwsProductionTrust\.ps1/);
  const trust = read('windows/webview2/Install-IwsProductionTrust.ps1');
  assert.match(trust, /HasPrivateKey/);
  assert.match(trust, /PRIVATE KEY/);
  assert.match(trust, /Add-DnsClientNrptRule -Namespace 'portal\.iws\.internal' -NameServers '100\.83\.75\.124'/);
  assert.match(trust, /CertificateOwned/);
  assert.match(trust, /RuleName/);
  assert.match(read('windows/webview2/Remove-IwsWebViewShellPoc.ps1'), /Install-IwsProductionTrust\.ps1.*-Remove/);
  for (const member of ['Install-IwsProductionTrust.ps1', 'iws-production-root-ca.crt', 'Remove-IwsWebViewShellPoc.ps1']) assert.ok(read('packaging/windows/prepare-payload.sh').includes(member));
});
