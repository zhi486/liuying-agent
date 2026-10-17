import { DATABASE_TIME_SQL } from '../../src/cloud-task-contracts.ts';

export async function takeHttpPermit(db) {
  const result = await db.prepare(`UPDATE verify_limits SET requests=requests+1
    WHERE singleton=1 AND requests<100 AND started_at<=MAX(?,${DATABASE_TIME_SQL})
    AND expires_at>MAX(?,${DATABASE_TIME_SQL}) RETURNING requests`).bind(Date.now(),Date.now()).all();
  return result.results.length===1;
}

export async function takeTaskPermit(db, owner, key) {
  const result = await db.batch([
    db.prepare(`INSERT OR IGNORE INTO verify_admissions(owner,request_id)
      SELECT ?,? WHERE (SELECT COUNT(*) FROM verify_admissions)<20
      AND EXISTS (SELECT 1 FROM verify_limits WHERE expires_at>MAX(?,${DATABASE_TIME_SQL}))`)
      .bind(owner,key,Date.now()),
    db.prepare('SELECT request_id FROM verify_admissions WHERE owner=? AND request_id=?').bind(owner,key),
  ]);
  return result[1].results.length===1;
}

export async function verifyOuterToken(value, expectedHash) {
  if (typeof value!=='string' || value.length>128 || !/^[0-9a-f]{64}$/.test(expectedHash??'')) return false;
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)));
  let mismatch=0;
  for(let i=0;i<32;i++) mismatch |= bytes[i]^Number.parseInt(expectedHash.slice(i*2,i*2+2),16);
  return mismatch===0;
}
