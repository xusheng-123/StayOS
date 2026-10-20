package com.stayos.auth;

import java.io.Serializable;

// No password hash or submitted credential is retained in a session.
public record SessionIdentity(long userId, long securityVersion) implements Serializable {}
