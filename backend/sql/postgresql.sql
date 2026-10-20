-- watch_devices（docs/03 §2.1）PostgreSQL
-- 1 行が時計 1 台。watch_id は時計本体の識別子、device_id は作業員が入力する端末番号（表示用。一意ではない）
-- fcm_token には FCM の宛先 ID（Firebase Installation ID）が入る
CREATE TABLE watch_devices (
    watch_id         VARCHAR(64)  PRIMARY KEY,
    site_id          INTEGER      NOT NULL,
    device_id        INTEGER      NOT NULL,
    fcm_token        VARCHAR(512) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    app_version      VARCHAR(32),
    token_invalid_at TIMESTAMPTZ
);
CREATE INDEX idx_watch_devices_site ON watch_devices (site_id, device_id);
CREATE INDEX idx_watch_devices_fcm_token ON watch_devices (fcm_token);

-- 登録処理（$1 = watch_id, $2 = site_id, $3 = device_id, $4 = fcm_token, $5 = app_version）
-- 書き換えるのはその時計自身の行だけ。同じ番号の別の時計の行は消さない（D-14）
-- BEGIN;
-- DELETE FROM watch_devices WHERE fcm_token = $4 AND watch_id <> $1;
-- INSERT INTO watch_devices (watch_id, site_id, device_id, fcm_token, updated_at, app_version, token_invalid_at)
-- VALUES ($1, $2, $3, $4, CURRENT_TIMESTAMP, $5, NULL)
-- ON CONFLICT (watch_id) DO UPDATE
--    SET site_id = EXCLUDED.site_id, device_id = EXCLUDED.device_id, fcm_token = EXCLUDED.fcm_token,
--        updated_at = EXCLUDED.updated_at, app_version = EXCLUDED.app_version, token_invalid_at = NULL;
-- COMMIT;
-- 同じ業務日の重複（$6 = 業務日の開始時刻）
-- SELECT watch_id FROM watch_devices
--  WHERE site_id = $2 AND device_id = $3 AND watch_id <> $1 AND updated_at >= $6;
-- 現場への送信先
-- SELECT watch_id, device_id, fcm_token FROM watch_devices WHERE site_id = $1 AND token_invalid_at IS NULL;
