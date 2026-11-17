#!/usr/bin/env node
// Differential check: GETs the same paths from an OLD and a NEW server and reports every difference
// the config doesn't approve. Config (.claude/verify/diff.json):
//   { "paths": ["/api/x?page=0"], "ignoreKeys": ["generatedAt"],
//     "approved": [{ "path": "/api/x?page=0", "at": "/items/0/title", "why": "renamed in PR #12" }] }
// usage: node diff-api.mjs <old-base-url> <new-base-url> [config]   (DIFF_TOKEN adds a bearer token)
import { readFileSync } from 'node:fs';

const [oldBase, newBase, configPath = '.claude/verify/diff.json'] = process.argv.slice(2);
if (!oldBase || !newBase) {
  console.error('usage: diff-api.mjs <old-base-url> <new-base-url> [config]');
  process.exit(64);
}
const config = JSON.parse(readFileSync(configPath, 'utf8'));
const ignore = new Set(config.ignoreKeys ?? []);
const approved = new Set((config.approved ?? []).map((a) => `${a.path} ${a.at}`));
const headers = process.env.DIFF_TOKEN ? { authorization: `Bearer ${process.env.DIFF_TOKEN}` } : {};

async function get(base, path) {
  const res = await fetch(base + path, { headers });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text.trim().replace(/\s+/g, ' '); }
  return { status: res.status, body };
}

function diff(a, b, at, out) {
  if (typeof a !== typeof b || Array.isArray(a) !== Array.isArray(b) || a === null || b === null
      || typeof a !== 'object') {
    if (JSON.stringify(a) !== JSON.stringify(b)) out.push({ at: at || '/', old: a, new: b });
    return out;
  }
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const k of keys) if (!ignore.has(k)) diff(a[k], b[k], `${at}/${k}`, out);
  return out;
}

let failures = 0;
for (const path of config.paths) {
  const [o, n] = await Promise.all([get(oldBase, path), get(newBase, path)]);
  const changes = diff({ status: o.status, body: o.body }, { status: n.status, body: n.body }, '', [])
    .filter((c) => !approved.has(`${path} ${c.at}`));
  for (const c of changes) {
    failures++;
    console.log(`${path} ${c.at}\n  old: ${JSON.stringify(c.old)}\n  new: ${JSON.stringify(c.new)}`);
  }
}
console.log(failures ? `${failures} unapproved difference(s).` : `No differences across ${config.paths.length} path(s).`);
process.exit(failures ? 1 : 0);
