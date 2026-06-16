package com.bond.common.enums;

public enum TransferStatus {
    PENDING(0),
    UPLOADING(1),
    COMPLETED(2),
    FAILED(3),
    EXPIRED(4);

    private final int value;

    TransferStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
