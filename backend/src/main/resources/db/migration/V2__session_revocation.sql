-- Revoke old sessions even when account updates are performed by a maintenance script.
CREATE FUNCTION bump_user_security_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.enabled IS DISTINCT FROM OLD.enabled OR NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
        NEW.security_version := OLD.security_version + 1;
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER user_security_changed BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION bump_user_security_version();

CREATE FUNCTION revoke_sessions_on_role_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        UPDATE users SET security_version = security_version + 1 WHERE id = OLD.user_id;
    END IF;
    IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.user_id <> OLD.user_id) THEN
        UPDATE users SET security_version = security_version + 1 WHERE id = NEW.user_id;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER user_roles_changed AFTER INSERT OR UPDATE OR DELETE ON user_roles
    FOR EACH ROW EXECUTE FUNCTION revoke_sessions_on_role_change();
