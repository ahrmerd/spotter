<?php
require __DIR__ . '/db.php';
header('Content-Type: application/json');
if ($_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(405); echo '{"ok":false}'; exit; }
$raw = file_get_contents('php://input', false, null, 0, 1024);
$j = json_decode($raw, true);
if (!is_array($j)
    || !isset($j['id']) || !preg_match('/^[0-9a-f-]{36}$/', $j['id'])
    || !isset($j['v']) || !preg_match('/^[0-9A-Za-z.\-]{1,20}$/', $j['v'])
    || !isset($j['c'], $j['sdk']) || !is_int($j['c']) || !is_int($j['sdk'])
    || !isset($j['abi']) || !preg_match('/^[0-9A-Za-z_\-]{1,20}$/', $j['abi'])) {
    http_response_code(400); echo '{"ok":false}'; exit;
}
$now = time(); $day = gmdate('Y-m-d', $now);
$pdo = db();
$st = $pdo->prepare('INSERT INTO installs (id, first_seen, last_seen, version, code, sdk, abi) VALUES (?,?,?,?,?,?,?)
  ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen, version=excluded.version, code=excluded.code,
  sdk=excluded.sdk, abi=excluded.abi, pings=pings+1');
$st->execute([$j['id'], $now, $now, $j['v'], $j['c'], $j['sdk'], $j['abi']]);
$pdo->prepare('INSERT OR IGNORE INTO pings (day, id) VALUES (?,?)')->execute([$day, $j['id']]);
echo '{"ok":true}';
