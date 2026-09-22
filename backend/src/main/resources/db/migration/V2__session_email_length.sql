-- Authentication uses the normalized e-mail as principal name (up to 254 characters).
ALTER TABLE SPRING_SESSION ALTER COLUMN PRINCIPAL_NAME TYPE VARCHAR(254);
