package com.bond.common.enums;

public enum TransferStatus {
    PENDING(0),
    UPLOADING(1),
    COMPLETED(2),
    FAILED(3),
    EXPIRED(4),
    CANCELLED(5),
    /** task-94：接收方明确拒绝；新增数值，不改动既有 0–5 语义。 */
    REJECTED(6);

    private final int value;

    TransferStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
