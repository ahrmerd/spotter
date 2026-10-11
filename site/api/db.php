<?php
// SQLite lives outside the web root. No IP addresses are stored.
function db(): PDO {
    $dir = dirname(__DIR__, 3) . '/spotter-data';
    if (!is_dir($dir)) mkdir($dir, 0750, true);
    $pdo = new PDO('sqlite:' . $dir . '/spotter.sqlite');
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $pdo->exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=3000');
    $pdo->exec('CREATE TABLE IF NOT EXISTS installs (
        id TEXT PRIMARY KEY, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
        version TEXT, code INTEGER, sdk INTEGER, abi TEXT, pings INTEGER NOT NULL DEFAULT 1)');
    $pdo->exec('CREATE TABLE IF NOT EXISTS pings (day TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (day, id))');
    $pdo->exec('CREATE TABLE IF NOT EXISTS downloads (day TEXT NOT NULL, file TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, file))');
    return $pdo;
}
