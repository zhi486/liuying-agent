import assert from 'node:assert/strict';
import { test } from 'node:test';
import { openBudgetDatabase } from '../../test/sqlite-d1.ts';
import { readFileSync } from 'node:fs';

test('持久总额跨调用不超过100，20个不同任务后只允许已登记键', async () => {
  const { takeHttpPermit, takeTaskPermit } = await import('./gate.mjs');
  const database = openBudgetDatabase();
  try {
    database.sqlite.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
    const now=Date.now()-1;
    database.sqlite.prepare('INSERT INTO verify_limits VALUES (1,?, ?,0)').run(now,now+1800000);
    const all = await Promise.all(Array.from({ length: 105 }, () => takeHttpPermit(database.db)));
    assert.equal(all.filter(Boolean).length, 100);
    assert.equal(await takeHttpPermit(database.db), false);
    for (let i=0;i<20;i++) assert.equal(await takeTaskPermit(database.db,'owner',`key-${i}`),true);
    assert.equal(await takeTaskPermit(database.db,'owner','key-20'), false);
    assert.equal(await takeTaskPermit(database.db,'owner','key-0'), true);
    assert.equal(await takeTaskPermit(database.db,'other','key-0'), false);
    assert.equal(database.sqlite.prepare('SELECT COUNT(*) n FROM verify_admissions').get().n,20);
  } finally { database.close(); }
});

test('窗口到期、未配置及过长窗口均拒绝，不自动续期', async () => {
  const { takeHttpPermit } = await import('./gate.mjs');
  const database = openBudgetDatabase();
  try {
    database.sqlite.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
    assert.equal(await takeHttpPermit(database.db), false);
    database.sqlite.prepare('INSERT INTO verify_limits VALUES (1,?, ?,0)').run(Date.now()-1800000,Date.now()-1);
    assert.equal(await takeHttpPermit(database.db), false);
    assert.throws(()=>database.sqlite.prepare('UPDATE verify_limits SET expires_at=started_at+1800001').run());
  } finally { database.close(); }
});

test('固定合成清单拒绝任意新正文，不将匹配当作设备认证', async () => {
  const { fixture, matchFixture } = await import('./fixtures.mjs');
  assert.equal(matchFixture(fixture(0)),0);
  assert.equal(matchFixture({ ...fixture(0), prompt:'真实私人资料' }),null);
  assert.equal(matchFixture({ ...fixture(0), owner:'forged' }),null);
  assert.equal(matchFixture({ ...fixture(0), consent:{cloudProcessing:false,runSummaries:true} }),null);
});
