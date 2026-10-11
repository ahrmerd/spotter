<?php
// Counts a download, then sends the file. Only files listed in releases.json can be fetched.
require __DIR__ . '/api/db.php';
$rel = json_decode(file_get_contents(__DIR__ . '/releases.json'), true);
$name = basename($_GET['f'] ?? '');
$ok = false;
foreach ($rel['releases'] ?? [] as $r) foreach ($r['files'] as $f) if ($f['name'] === $name) $ok = true;
$path = __DIR__ . '/files/' . $name;
if (!$ok || !is_file($path)) { http_response_code(404); echo 'Not found'; exit; }
db()->prepare('INSERT INTO downloads (day, file, n) VALUES (?,?,1) ON CONFLICT(day,file) DO UPDATE SET n=n+1')
    ->execute([gmdate('Y-m-d'), $name]);
header('Content-Type: ' . (str_ends_with($name, '.apk') ? 'application/vnd.android.package-archive' : 'application/zip'));
header('Content-Disposition: attachment; filename="' . $name . '"');
header('Content-Length: ' . filesize($path));
readfile($path);
