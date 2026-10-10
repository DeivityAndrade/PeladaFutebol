-- Keep only the numeric provider code, never the webhook's descriptions or payload.
ALTER TABLE whatsapp_delivery_events ADD COLUMN error_code varchar(8)
  CHECK (error_code IS NULL OR error_code ~ '^[0-9]{1,8}$');
