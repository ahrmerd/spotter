<?php
require __DIR__ . '/api/db.php';
$tokenFile = dirname(__DIR__, 2) . '/spotter-data/token';
$token = is_file($tokenFile) ? trim(file_get_contents($tokenFile)) : '';
if ($token === '' || !hash_equals($token, (string)($_GET['key'] ?? ''))) { http_response_code(403); echo 'Forbidden'; exit; }
header('X-Robots-Tag: noindex');
$pdo = db();
$one = fn($q, $a = []) => (function() use ($pdo, $q, $a) { $s = $pdo->prepare($q); $s->execute($a); return $s->fetchColumn(); })();
$rows = fn($q) => $pdo->query($q)->fetchAll(PDO::FETCH_NUM);
$day = fn($n) => gmdate('Y-m-d', time() - 86400 * $n);
$active = fn($n) => $one('SELECT COUNT(DISTINCT id) FROM pings WHERE day >= ?', [$day($n - 1)]);
$total = (int)$one('SELECT COUNT(*) FROM installs');
function table($title, $head, $data) {
    echo "<h2>" . htmlspecialchars($title) . "</h2><table><tr>";
    foreach ($head as $h) echo '<th>' . htmlspecialchars($h) . '</th>';
    echo '</tr>';
    foreach ($data as $r) { echo '<tr>'; foreach ($r as $c) echo '<td>' . htmlspecialchars((string)$c) . '</td>'; echo '</tr>'; }
    echo '</table>';
}
?><!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Spotter stats</title>
<style>body{font:16px system-ui;margin:16px;max-width:760px;background:#fbf8ff;color:#1c1b20}
@media(prefers-color-scheme:dark){body{background:#1a1822;color:#f3eef7}th{background:#2b2930}}
.k{display:flex;gap:12px;flex-wrap:wrap}.k div{background:#4527a0;color:#fff;border-radius:12px;padding:12px 16px;min-width:110px}
.k b{display:block;font-size:28px}table{border-collapse:collapse;width:100%}td,th{padding:6px 8px;border-bottom:1px solid #8884;text-align:left}</style>
<h1>Spotter stats</h1>
<div class="k">
<div><b><?= $total ?></b>installs seen</div>
<div><b><?= $active(1) ?></b>active today</div>
<div><b><?= $active(7) ?></b>active 7 days</div>
<div><b><?= $active(30) ?></b>active 30 days</div>
<div><b><?= (int)$one('SELECT COALESCE(SUM(n),0) FROM downloads') ?></b>APK downloads</div>
</div>
<?php
table('New installs per day (last 14 days)', ['Day', 'New'], $rows("SELECT date(first_seen,'unixepoch') d, COUNT(*) FROM installs GROUP BY d ORDER BY d DESC LIMIT 14"));
table('Active devices per day (last 14 days)', ['Day', 'Devices'], $rows("SELECT day, COUNT(*) FROM pings GROUP BY day ORDER BY day DESC LIMIT 14"));
table('Active in 30 days by version', ['Version', 'Devices'], $rows("SELECT version, COUNT(*) FROM installs WHERE last_seen >= strftime('%s','now','-30 day') GROUP BY version ORDER BY 2 DESC"));
table('Active in 30 days by Android (API level)', ['API', 'Devices'], $rows("SELECT sdk, COUNT(*) FROM installs WHERE last_seen >= strftime('%s','now','-30 day') GROUP BY sdk ORDER BY sdk DESC"));
table('Active in 30 days by CPU', ['CPU', 'Devices'], $rows("SELECT abi, COUNT(*) FROM installs WHERE last_seen >= strftime('%s','now','-30 day') GROUP BY abi ORDER BY 2 DESC"));
table('Downloads per file', ['File', 'Downloads'], $rows("SELECT file, SUM(n) FROM downloads GROUP BY file ORDER BY 2 DESC"));
