package com.bond.common.enums;

public enum WorkspaceRole {
    OWNER(0),
    ADMIN(1),
    MEMBER(2);

    private final int value;

    WorkspaceRole(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
