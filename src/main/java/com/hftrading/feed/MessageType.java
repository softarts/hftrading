package com.hftrading.feed;

public enum MessageType {
    NEW_ORDER('N'),
    REPLACE('R'),
    CANCEL('X'),
    MODIFY('M');

    private final char code;

    // Direct lookup table indexed by ASCII char code.
    // Avoids values() array allocation on every fromCode() call.
    private static final MessageType[] LOOKUP = new MessageType[128];
    static {
        for (MessageType t : values()) {
            LOOKUP[t.code] = t;
        }
    }

    MessageType(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    public static MessageType fromCode(char code) {
        if (code >= 0 && code < LOOKUP.length) {
            MessageType t = LOOKUP[code];
            if (t != null) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unsupported message type: " + code);
    }
}
