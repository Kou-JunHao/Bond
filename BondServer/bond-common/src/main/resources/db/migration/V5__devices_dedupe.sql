-- ═══════════════════════════════════════════════════════════════════════════
--  V5: /api/devices 重复行修复（同一用户同一设备只保留一行）
--
--  现象：客户端每次注册设备都会 INSERT 新行，devices 表没有稳定设备标识与唯
--        一约束，导致 /api/devices 偶发返回重复设备行。
--  修复：引入稳定标识 device_uid（客户端可传 deviceId 指纹；不传时回退为
--        device_name + '|' + device_type），历史重复行按 id 最大者保留，并加
--        唯一索引 (user_id, device_uid) 让并发/重复注册在数据库层被兜住。
--  注意：device_uid 的推导规则必须与服务端 DeviceService.deriveDeviceUid 保持
--        完全一致（trim 规则相同），否则上线后首次重复注册会变成新行。
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE devices ADD COLUMN device_uid VARCHAR(128);

-- 1) 回填历史行：与 Java 回退规则一致（device_name.trim() + '|' + device_type.trim()）
UPDATE devices
   SET device_uid = btrim(device_name) || '|' || btrim(COALESCE(device_type, ''))
 WHERE device_uid IS NULL;

-- 2) 把 transfer_tasks 对重复设备的引用重定向到保留行（devices FK 是 ON DELETE SET NULL，
--    直接删重复行会把历史任务的设备引用置空，先重定向更安全）
UPDATE transfer_tasks t
   SET sender_device_id = keepers.keep_id
  FROM devices d
  JOIN (
        SELECT user_id, device_uid, MAX(id) AS keep_id
          FROM devices
         GROUP BY user_id, device_uid
       ) keepers
    ON keepers.user_id = d.user_id
   AND keepers.device_uid = d.device_uid
 WHERE t.sender_device_id = d.id
   AND d.id <> keepers.keep_id;

UPDATE transfer_tasks t
   SET receiver_device_id = keepers.keep_id
  FROM devices d
  JOIN (
        SELECT user_id, device_uid, MAX(id) AS keep_id
          FROM devices
         GROUP BY user_id, device_uid
       ) keepers
    ON keepers.user_id = d.user_id
   AND keepers.device_uid = d.device_uid
 WHERE t.receiver_device_id = d.id
   AND d.id <> keepers.keep_id;

-- 3) 删除历史重复行：同一 (user_id, device_uid) 只保留 id 最大的一行
DELETE FROM devices d
 USING (
        SELECT user_id, device_uid, MAX(id) AS keep_id
          FROM devices
         GROUP BY user_id, device_uid
       ) keepers
 WHERE d.user_id = keepers.user_id
   AND d.device_uid = keepers.device_uid
   AND d.id <> keepers.keep_id;

-- 4) 强制非空 + 唯一约束
UPDATE devices SET device_uid = 'legacy-' || id WHERE device_uid IS NULL OR btrim(device_uid) = '';
ALTER TABLE devices ALTER COLUMN device_uid SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_devices_user_uid ON devices (user_id, device_uid);

-- 修复后自检（可手工执行）：
--   SELECT device_uid, count(*) FROM devices GROUP BY user_id, device_uid HAVING count(*) > 1;
--   期望 0 行。
