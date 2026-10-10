package io.orphos.eip.admin.auth.model;

public enum AuthEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    REGISTER,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_CHANGED,
    METHOD_SWITCHED,
    ACCOUNT_LOCKED,
    ACCOUNT_UNLOCKED
}
