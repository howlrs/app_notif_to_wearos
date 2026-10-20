-- watch_devices（docs/03 §2.1）MySQL 8.0.19 以降
-- 1 行が時計 1 台。watch_id は時計本体の識別子、device_id は作業員が入力する端末番号（表示用。一意ではない）
-- fcm_token には FCM の宛先 ID（Firebase Installation ID）が入る
CREATE TABLE watch_devices (
    watch_id         VARCHAR(64)  NOT NULL,
    site_id          INT          NOT NULL,
    device_id        INT          NOT NULL,
    fcm_token        VARCHAR(512) NOT NULL,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    app_version      VARCHAR(32)  NULL,
    token_invalid_at TIMESTAMP    NULL,
    PRIMARY KEY (watch_id),
    INDEX idx_watch_devices_site (site_id, device_id),
    INDEX idx_watch_devices_fcm_token (fcm_token)
);

-- 登録処理（同じトランザクションで実行する）。書き換えるのはその時計自身の行だけ（D-14）
-- DELETE FROM watch_devices WHERE fcm_token = ? AND watch_id <> ?;
-- INSERT INTO watch_devices (watch_id, site_id, device_id, fcm_token, updated_at, app_version, token_invalid_at)
-- VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, NULL) AS new
-- ON DUPLICATE KEY UPDATE
--     site_id = new.site_id, device_id = new.device_id, fcm_token = new.fcm_token,
--     updated_at = new.updated_at, app_version = new.app_version, token_invalid_at = NULL;
-- 同じ業務日の重複: SELECT watch_id FROM watch_devices
--   WHERE site_id = ? AND device_id = ? AND watch_id <> ? AND updated_at >= ?;
-- 現場への送信先: SELECT watch_id, device_id, fcm_token FROM watch_devices
--   WHERE site_id = ? AND token_invalid_at IS NULL;
